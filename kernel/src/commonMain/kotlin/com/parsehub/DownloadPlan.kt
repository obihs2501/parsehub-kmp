package com.parsehub

import com.parsehub.core.AppConfig
import com.parsehub.core.Naming
import com.parsehub.core.ParseResult
import com.parsehub.core.nowCompact
import com.parsehub.core.nowTime
import com.parsehub.core.today
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

/** 两端共享的下载选择、归档与命名规则。 */
class DownloadPlan(
    private val config: AppConfig,
    private val creator: String,
    private val pick: Set<Int>,
    private val imagesOn: Boolean,
    private val auxOn: List<Boolean>,
) {
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

}
