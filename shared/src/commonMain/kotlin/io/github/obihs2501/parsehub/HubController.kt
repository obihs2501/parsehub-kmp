package io.github.obihs2501.parsehub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.download.CancelledException
import io.github.obihs2501.parsehub.download.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

data class JobSpec(
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

/** 下载落点：临时文件就绪后由各端实现搬运（Android=MediaStore，桌面=移动进选择目录） */
fun interface FileSink {
    suspend fun accept(tempPath: Path, fileName: String, mime: String)
}

/**
 * 跨端共享的状态控制器：解析 / 选择 / 下载编排。
 * UI 层（Android Miuix / 桌面 Miuix）只读取这里的 state 并调用方法。
 */
class HubController(private val tempDir: Path) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cancelRequested = false

    var link by mutableStateOf("")
    var parsing by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var error by mutableStateOf("")
    var note by mutableStateOf("")

    var result by mutableStateOf<ParseResult?>(null)
    var pick by mutableStateOf<Set<Int>>(emptySet())
    var imagesOn by mutableStateOf(false)
    var auxOn by mutableStateOf<List<Boolean>>(emptyList())

    var totalJobs by mutableStateOf(0)
    var doneJobs by mutableStateOf(0)
    var curName by mutableStateOf("")
    var curDone by mutableStateOf(0L)
    var curTotal by mutableStateOf(0L)
    var speed by mutableStateOf(0L)

    val busy: Boolean get() = parsing || downloading

    // ---------------------------------------------------------------- 解析
    fun parse(opts: ParseOptions) {
        val text = link.trim()
        if (text.isEmpty() || busy) return
        parsing = true
        error = ""
        note = "解析中…"
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Hub.parse(text, opts) }
                result = r
                pick = defaultPick(r, opts)
                imagesOn = r.images.isNotEmpty()
                auxOn = r.aux.map { a ->
                    when (a.kind) {
                        "audio" -> false
                        "download" -> false
                        else -> false
                    }
                }
                note = "解析完成 · ${r.platform.label} · " +
                    (if (r.images.isNotEmpty()) "图集 ${r.images.size} 张 / " else "") +
                    "${r.tiers.size} 档"
            } catch (e: Exception) {
                error = "解析失败: ${e.message?.take(180) ?: e.javaClass.simpleName}"
                note = ""
            } finally {
                parsing = false
            }
        }
    }

    fun defaultPick(r: ParseResult, opts: ParseOptions): Set<Int> = when (opts.defaultTier) {
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

    // ---------------------------------------------------------------- 下载
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
        error = ""
        doneJobs = 0
        totalJobs = jobs.size
        scope.launch {
            try {
                for ((i, j) in jobs.withIndex()) {
                    if (cancelRequested) throw CancelledException()
                    curName = j.name
                    curDone = 0L
                    curTotal = 0L
                    speed = 0L
                    note = "下载 ${i + 1}/${jobs.size}"
                    val tmp = tempDir / "${i}_${sanitize(j.name, 80)}"
                    withContext(Dispatchers.IO) {
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
                        sink.accept(tmp, j.name, mimeOf(j.ext))
                    }
                    doneJobs = i + 1
                }
                note = "全部完成 · ${r.platform.dirPrefix}_${sanitize(r.author, 40)}"
            } catch (e: CancelledException) {
                note = "已取消（完成 $doneJobs/$totalJobs）"
            } catch (e: Exception) {
                error = "下载失败: ${e.message?.take(160) ?: e.javaClass.simpleName}"
                note = "已保存 $doneJobs/$totalJobs 项"
            } finally {
                downloading = false
            }
        }
    }

    fun cancel() {
        if (downloading) cancelRequested = true
    }

    fun buildJobs(r: ParseResult): List<JobSpec> {
        val jobs = mutableListOf<JobSpec>()
        if (imagesOn) {
            for (im in r.images) {
                jobs += JobSpec(
                    makeName(r.author, r.date, r.title, (im.index + 1).toString().padStart(3, '0'), im.ext),
                    listOf(im.url), im.ext, "image",
                )
                val lv = im.live
                if (!lv.isNullOrEmpty()) {
                    jobs += JobSpec(
                        makeName(r.author, r.date, r.title, (im.index + 1).toString().padStart(3, '0') + "_live", "mp4"),
                        listOf(lv), "mp4", "live",
                    )
                }
            }
        }
        for (t in r.tiers) {
            if (!pick.contains(t.idx)) continue
            jobs += JobSpec(
                makeName(r.author, r.date, r.title, qualityTag(t), "mp4"),
                t.urls, "mp4", "video",
            )
        }
        r.aux.forEachIndexed { i, a ->
            if (auxOn.getOrElse(i) { false }) {
                jobs += JobSpec(
                    makeName(r.author, r.date, r.title, a.label, a.ext),
                    a.urls, a.ext, a.kind,
                )
            }
        }
        // 重名任务补序号
        val seen = mutableMapOf<String, Int>()
        return jobs.map { j ->
            val c = seen.getOrDefault(j.name, 0)
            seen[j.name] = c + 1
            if (c == 0) j else j.copy(name = j.name.replaceFirst(Regex("(\\.\\w+)$"), "_$c$1"))
        }
    }
}
