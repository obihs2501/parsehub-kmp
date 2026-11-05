package com.parsehub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.parsehub.core.AppConfig
import com.parsehub.core.HistoryEntry
import com.parsehub.core.HistoryStore
import com.parsehub.core.Naming
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.nowCompact
import com.parsehub.core.nowMillis
import com.parsehub.core.nowTime
import com.parsehub.core.today
import com.parsehub.download.CancelledException
import com.parsehub.download.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

/** 一个待下载项：落点相对目录 + 文件名 + 候选直链 */
data class JobSpec(
    val relDir: String,
    val name: String,
    val urls: List<String>,
    val ext: String,
    val kind: String,   // image | live | video | audio | download
)

fun mimeOf(ext: String): String = when (ext.lowercase()) {
    "mp4" -> "video/mp4"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    else -> "application/octet-stream"
}

/**
 * 下载落点：各端实现。
 * exists 用于「同名不覆盖」取名，accept 把临时文件落盘（Android=MediaStore，桌面=移动进目录）。
 */
interface FileSink {
    suspend fun exists(relDir: String, fileName: String): Boolean
    suspend fun accept(tempPath: Path, relDir: String, fileName: String, mime: String)
}

/** 下载任务行（下载页列表） */
data class TaskRow(
    val name: String,
    val relDir: String,
    val status: String,          // running | done | fail
    val done: Long = 0L,
    val total: Long = 0L,
    val error: String = "",
)

/**
 * 跨端共享的状态控制器：解析 / 选择 / 命名归档 / 下载编排 / 记录。
 * UI 层只读取这里的状态并调用方法。
 */
class HubController(
    private val tempDir: Path,
    private val sink: FileSink? = null,
    initialConfig: AppConfig = AppConfig(),
    private val history: HistoryStore? = null,
    private val onConfigChanged: (AppConfig) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cancelRequested = false
    private var turn = 0

    var config by mutableStateOf(initialConfig)
        private set

    var link by mutableStateOf("")
    var parsing by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var cancelling by mutableStateOf(false)
    var error by mutableStateOf("")
    var note by mutableStateOf("")

    var result by mutableStateOf<ParseResult?>(null)
    var creator by mutableStateOf("")
    var pick by mutableStateOf<Set<Int>>(emptySet())
    var imagesOn by mutableStateOf(initialConfig.imagesByDefault)
    var auxOn by mutableStateOf<List<Boolean>>(emptyList())

    var tasks by mutableStateOf<List<TaskRow>>(emptyList())
    var totalJobs by mutableStateOf(0)
    var doneJobs by mutableStateOf(0)
    var curName by mutableStateOf("")
    var curDone by mutableStateOf(0L)
    var curTotal by mutableStateOf(0L)
    var speed by mutableStateOf(0L)

    /** 记录页刷新信号 */
    var historyTick by mutableStateOf(0)
        private set

    val busy: Boolean get() = parsing || downloading

    // ------------------------------------------------------------ 配置
    fun updateConfig(new: AppConfig) {
        config = new
        onConfigChanged(new)
    }

    /** 外部（如设置页直接改）同步进来的配置，不回写 */
    fun syncConfig(new: AppConfig) {
        config = new
    }

    // ------------------------------------------------------------ 解析
    fun parse() {
        val text = link.trim()
        if (text.isEmpty() || busy) return
        val platform = Platform.detect(text)
        if (platform == null) {
            error = "无法识别平台，支持：${Hub.platforms.joinToString("、") { it.label }}"
            note = ""
            return
        }
        val opts = config.optionsFor(platform.key, turn++)
        parsing = true
        error = ""
        note = "解析中…"
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Hub.parse(text, opts) }
                result = r
                creator = config.creatorOf(r.platform.key, r.authorId, r.author)
                pick = defaultPick(r)
                imagesOn = config.imagesByDefault && r.images.isNotEmpty()
                auxOn = r.aux.map { false }
                note = buildString {
                    append("解析完成 · ").append(r.platform.label)
                    if (r.images.isNotEmpty()) append(" · 图集 ").append(r.images.size).append(" 张")
                    if (r.tiers.isNotEmpty()) append(" · ").append(r.tiers.size).append(" 档")
                }
                addHistory(
                    HistoryEntry(
                        ts = nowMillis(), platform = r.platform.key, id = r.id, title = r.title,
                        author = r.author, creator = creator, url = text, kind = "parse", status = "ok",
                    ),
                )
            } catch (e: Exception) {
                val msg = e.message?.take(180) ?: e.javaClass.simpleName
                error = "解析失败: $msg"
                note = ""
                addHistory(
                    HistoryEntry(
                        ts = nowMillis(), platform = platform.key, title = "解析失败",
                        url = text, kind = "parse", status = "fail", error = msg,
                    ),
                )
            } finally {
                parsing = false
            }
        }
    }

    fun defaultPick(r: ParseResult): Set<Int> = when (config.defaultTier) {
        "origin" -> {
            val t = r.tiers.firstOrNull { it.isOrigin } ?: r.tiers.firstOrNull()
            t?.let { setOf(it.idx) } ?: emptySet()
        }
        "h264" -> {
            val t = r.tiers.firstOrNull { !it.h265 } ?: r.tiers.firstOrNull()
            t?.let { setOf(it.idx) } ?: emptySet()
        }
        "small" -> r.tiers.lastOrNull()?.let { setOf(it.idx) } ?: emptySet()
        else -> emptySet()
    }

    fun hasSelection(): Boolean =
        pick.isNotEmpty() ||
            (result?.images?.isNotEmpty() == true && imagesOn) ||
            auxOn.any { it }

    fun togglePick(idx: Int) {
        pick = if (pick.contains(idx)) pick - idx else pick + idx
    }

    fun toggleAux(i: Int) {
        auxOn = auxOn.toMutableList().also { it[i] = !(it.getOrElse(i) { false }) }
    }

    // ------------------------------------------------------------ 命名与归档
    private fun values(r: ParseResult, kind: String, index: Int): Map<String, String> = mapOf(
        "platform" to r.platform.label,
        "author" to r.author.ifBlank { "未知作者" },
        "creator" to creator.ifBlank { r.author.ifBlank { "未知作者" } },
        "title" to Naming.safeName(r.title.ifBlank { "无标题" }, 40),
        "id" to Naming.safeName(r.id.ifBlank { "unknown" }, 32),
        "date" to today(),
        "publish_date" to r.date.ifBlank { "未知发布日期" },
        "time" to nowTime(),
        "index" to index.toString(),
        "type" to kind,
    )

    /** 归档目录名：合并开启时同作者同作品稳定落同一目录，关闭时每次都建新目录 */
    fun folderName(r: ParseResult): String {
        val base = Naming.render(config.folderTemplate, values(r, "目录", 1), 1)
        val safe = Naming.safeName(base, 100)
        return if (config.mergeAuthorFolders) safe else "$safe-${nowCompact()}"
    }

    private fun fileName(r: ParseResult, kind: String, index: Int, ext: String): String {
        val stem = Naming.render(config.fileTemplate, values(r, kind, index), index)
        return Naming.safeName(stem, 120) + "." + ext
    }

    fun buildJobs(r: ParseResult): List<JobSpec> {
        val dir = folderName(r)
        val jobs = mutableListOf<JobSpec>()
        if (imagesOn) {
            for (im in r.images) {
                jobs += JobSpec(dir, fileName(r, "图片", im.index + 1, im.ext), listOf(im.url), im.ext, "image")
                val lv = im.live
                if (!lv.isNullOrEmpty()) {
                    jobs += JobSpec(dir, fileName(r, "实况", im.index + 1, "mp4"), listOf(lv), "mp4", "live")
                }
            }
        }
        for (t in r.tiers) {
            if (!pick.contains(t.idx)) continue
            jobs += JobSpec(dir, fileName(r, "视频", 1, "mp4"), t.urls, "mp4", "video")
        }
        r.aux.forEachIndexed { i, a ->
            if (auxOn.getOrElse(i) { false }) {
                jobs += JobSpec(dir, fileName(r, a.label.ifBlank { "附件" }, 1, a.ext), a.urls, a.ext, a.kind)
            }
        }
        // 同一批内重名补序号，保证临时文件不互相覆盖
        val seen = mutableMapOf<String, Int>()
        return jobs.map { j ->
            val n = seen.getOrDefault(j.name, 0)
            seen[j.name] = n + 1
            if (n == 0) j else j.copy(name = bumpName(j.name, n))
        }
    }

    private fun bumpName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "_" + n + name.substring(dot) else name + "_" + n
    }

    private suspend fun resolveName(sink: FileSink, relDir: String, name: String): String {
        if (!sink.exists(relDir, name)) return name
        var i = 1
        while (i < 1000) {
            val candidate = bumpName(name, i)
            if (!sink.exists(relDir, candidate)) return candidate
            i++
        }
        return bumpName(name, i)
    }

    // ------------------------------------------------------------ 下载
    /** 各端注入落点后，下载按钮走这里 */
    val canDownload: Boolean get() = sink != null && hasSelection()

    fun startDownload() {
        sink?.let { startDownload(it) }
    }

    fun startDownload(sink: FileSink) {
        val r = result ?: return
        if (busy) return
        val jobs = buildJobs(r)
        if (jobs.isEmpty()) {
            error = "没有选中任何下载项"
            return
        }
        downloading = true
        cancelRequested = false
        cancelling = false
        error = ""
        doneJobs = 0
        totalJobs = jobs.size
        val dir = jobs.first().relDir
        val urlText = link.trim()
        scope.launch {
            var ok = 0
            var files = 0
            try {
                for ((i, j) in jobs.withIndex()) {
                    if (cancelRequested) throw CancelledException()
                    curName = j.name
                    curDone = 0L
                    curTotal = 0L
                    speed = 0L
                    note = "下载 ${i + 1}/${jobs.size} · ${j.relDir}"
                    tasks = tasks + TaskRow(j.name, j.relDir, "running")
                    val rowIndex = tasks.lastIndex
                    val tmp = tempDir / Naming.safeName("${i}_${j.name}", 90)
                    try {
                        val finalName = withContext(Dispatchers.IO) {
                            Downloader.download(
                                urls = j.urls,
                                outPath = tmp,
                                referer = r.referer,
                                onProgress = { d, t, sp ->
                                    curDone = d
                                    curTotal = t
                                    speed = sp
                                },
                                shouldCancel = { cancelRequested },
                            )
                            val resolved = resolveName(sink, j.relDir, j.name)
                            sink.accept(tmp, j.relDir, resolved, mimeOf(j.ext))
                            resolved
                        }
                        ok++
                        files++
                        doneJobs = i + 1
                        note = "已保存 $files 项 · $finalName"
                        tasks = tasks.toMutableList().also { list ->
                            if (rowIndex in list.indices) {
                                list[rowIndex] = list[rowIndex].copy(status = "done", done = curDone, total = curTotal)
                            }
                        }
                    } catch (e: CancelledException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message?.take(120) ?: "下载失败"
                        tasks = tasks.toMutableList().also { list ->
                            if (rowIndex in list.indices) {
                                list[rowIndex] = list[rowIndex].copy(status = "fail", error = msg)
                            }
                        }
                        throw e
                    }
                }
                note = "全部完成 · $files 项 → $dir"
                addHistory(
                    HistoryEntry(
                        ts = nowMillis(), platform = r.platform.key, id = r.id, title = r.title,
                        author = r.author, creator = creator, url = urlText, kind = "download",
                        status = "ok", files = files, dir = dir,
                    ),
                )
            } catch (e: CancelledException) {
                note = "已取消（完成 $doneJobs/$totalJobs）"
                addHistory(
                    HistoryEntry(
                        ts = nowMillis(), platform = r.platform.key, id = r.id, title = r.title,
                        author = r.author, creator = creator, url = urlText, kind = "download",
                        status = "fail", files = files, dir = dir, error = "已取消",
                    ),
                )
            } catch (e: Exception) {
                val msg = e.message?.take(160) ?: e.javaClass.simpleName
                error = "下载失败: $msg"
                note = "已保存 $ok/$totalJobs 项"
                addHistory(
                    HistoryEntry(
                        ts = nowMillis(), platform = r.platform.key, id = r.id, title = r.title,
                        author = r.author, creator = creator, url = urlText, kind = "download",
                        status = "fail", files = files, dir = dir, error = msg,
                    ),
                )
            } finally {
                downloading = false
                cancelling = false
            }
        }
    }

    fun cancel() {
        if (!downloading) return
        cancelRequested = true
        cancelling = true
        note = "正在取消…"
    }

    // ------------------------------------------------------------ 记录
    private suspend fun addHistory(entry: HistoryEntry) {
        val store = history ?: return
        withContext(Dispatchers.IO) { store.add(entry) }
        historyTick++
    }

    fun historyEntries(): List<HistoryEntry> = history?.load() ?: emptyList()

    fun clearHistory() {
        scope.launch {
            withContext(Dispatchers.IO) { history?.clear() }
            historyTick++
        }
    }

    fun exportCsv(): String = history?.toCsv(historyEntries()) ?: ""
}
