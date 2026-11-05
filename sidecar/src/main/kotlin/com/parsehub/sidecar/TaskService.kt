package com.parsehub.sidecar

import com.parsehub.DownloadPlan
import com.parsehub.Hub
import com.parsehub.core.*
import com.parsehub.download.CancelledException
import com.parsehub.download.Downloader
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TaskService(
    private val store: Store,
    private val parseResult: suspend (String, ParseOptions) -> ParseResult = { text, options -> Hub.parse(text, options) },
) {
    private data class Parsed(val result: ParseResult, val text: String)
    private val results = ConcurrentHashMap<String, Parsed>()
    private val tasks = ConcurrentHashMap<String, TaskState>()
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turn = AtomicInteger()

    suspend fun parse(text: String): JsonObject {
        require(text.isNotBlank() && text.length <= 16384) { "请输入有效链接" }
        val platform = Platform.detect(text) ?: error("无法识别平台")
        val r = parseResult(text, store.settings.config.optionsFor(platform.key, turn.getAndIncrement()))
        val id = UUID.randomUUID().toString()
        if (results.size >= 100) results.keys.firstOrNull()?.let { results.remove(it) }
        results[id] = Parsed(r, text)
        synchronized(store.history) { store.history.add(HistoryEntry(
            ts = nowMillis(), platform = platform.key, id = r.id, title = r.title,
            author = r.author, creator = store.settings.config.creatorOf(platform.key, r.authorId, r.author), url = text,
        )) }
        return buildJsonObject { put("resultId", id); put("result", r.toDto()) }
    }

    fun list(): List<TaskState> = tasks.values.sortedByDescending { it.id }
    fun get(id: String): TaskState = tasks[id] ?: error("任务不存在")
    @Synchronized fun cancel(id: String): TaskState {
        get(id)
        cancellations[id]?.set(true)
        return tasks.computeIfPresent(id) { _, old ->
            if (old.status == "running") old.copy(status = "cancelling") else old
        }!!
    }

    @Synchronized fun start(req: TaskRequest): TaskState {
        require(tasks.values.none { it.status == "running" || it.status == "cancelling" }) { "已有下载进行中" }
        val parsed = results[req.resultId] ?: error("解析结果已过期，请重新解析")
        val r = parsed.result
        val settings = store.settings
        require(req.pick.all { idx -> r.tiers.any { it.idx == idx } }) { "档位无效" }
        val creator = req.creator.trim().ifBlank { settings.config.creatorOf(r.platform.key, r.authorId, r.author) }
        val jobs = DownloadPlan(settings.config, creator, req.pick, req.imagesOn, req.auxOn).buildJobs(r)
        require(jobs.isNotEmpty()) { "没有选中下载项" }
        val id = UUID.randomUUID().toString()
        val initial = TaskState(id, totalJobs = jobs.size)
        if (tasks.size > 100) tasks.entries.removeIf { it.value.status != "running" }
        tasks[id] = initial
        val cancelled = AtomicBoolean(false)
        cancellations[id] = cancelled
        scope.launch {
            val saved = mutableListOf<String>()
            val output = Path.of(settings.outDir).toAbsolutePath().normalize()
            var temp: Path? = null
            var status = "done"
            var failure = ""
            try {
                val work = Files.createTempDirectory(store.root, "download-")
                temp = work
                for ((index, spec) in jobs.withIndex()) {
                    if (cancelled.get()) throw CancelledException()
                    tasks[id] = get(id).copy(name = spec.name, done = 0, total = 0, speed = 0)
                    val tmp = work.resolve("$index.${spec.ext}")
                    Downloader.download(spec.urls, tmp.toString().toPath(), r.referer,
                        onProgress = { d, t, sp -> tasks.computeIfPresent(id) { _, old -> old.copy(done = d, total = t, speed = sp) } },
                        shouldCancel = cancelled::get)
                    if (cancelled.get()) throw CancelledException()
                    val folder = output.resolve(spec.relDir).normalize()
                    require(folder.startsWith(output)) { "归档路径越界" }
                    Files.createDirectories(folder)
                    val name = Naming.uniqueName(spec.name) { Files.exists(folder.resolve(it)) }
                    val destination = folder.resolve(name).normalize()
                    require(destination.parent == folder) { "文件名无效" }
                    Files.copy(tmp, destination)
                    Files.delete(tmp)
                    saved.add(destination.toString())
                    tasks[id] = get(id).copy(doneJobs = index + 1, files = saved.toList())
                }
            } catch (_: CancellationException) {
                status = "cancelled"
            } catch (_: CancelledException) {
                status = "cancelled"
            } catch (e: Exception) {
                status = "fail"; failure = e.message?.take(200) ?: "下载失败"
            } finally {
                synchronized(store.history) { store.history.add(HistoryEntry(
                    ts = nowMillis(), platform = r.platform.key, id = r.id, title = r.title,
                    author = r.author, creator = creator, url = parsed.text, kind = "download",
                    status = if (status == "done") "ok" else "fail", files = saved.size,
                    dir = output.resolve(jobs.first().relDir).toString(),
                    fileNames = encodeFileNames(saved.map { Path.of(it).fileName.toString() }),
                    error = if (status == "cancelled") "已取消" else failure,
                )) }
                cancellations.remove(id)
                temp?.let { directory -> runCatching {
                    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                } }
                tasks[id] = get(id).copy(status = status, error = failure, speed = 0, files = saved.toList())
            }
        }
        return initial
    }

    suspend fun close() {
        cancellations.values.forEach { it.set(true) }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
