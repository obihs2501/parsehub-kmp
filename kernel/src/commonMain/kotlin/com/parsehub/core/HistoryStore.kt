package com.parsehub.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** 记录里保存的文件名用换行拼接；文件名已由 Naming.safeName 去掉换行/控制字符 */
fun encodeFileNames(names: List<String>): String = names.joinToString("\n") { it.replace("\n", " ") }

fun decodeFileNames(raw: String): List<String> = if (raw.isBlank()) emptyList()
else raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** 解析 / 下载记录条目 */
@Serializable
data class HistoryEntry(
    val ts: Long = 0L,
    val platform: String = "",
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val creator: String = "",
    val url: String = "",
    val kind: String = "parse",     // parse | download
    val status: String = "ok",      // ok | fail
    val files: Int = 0,
    val dir: String = "",
    val error: String = "",
    /** 成功落盘的文件名（JSON 数组字符串），供记录页直接定位并打开媒体 */
    val fileNames: String = "",
)

private val STORE_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private fun readTextOrNull(fs: FileSystem, path: Path): String? = try {
    if (fs.exists(path)) fs.read(path) { readUtf8() } else null
} catch (_: Exception) {
    null
}

/** 原子写：先写 .tmp 再替换，避免中途失败留下半截文件 */
private fun writeTextAtomic(fs: FileSystem, path: Path, text: String) {
    path.parent?.let { fs.createDirectories(it) }
    val tmp = (path.parent ?: path) / (path.name + ".tmp")
    fs.write(tmp) { writeUtf8(text) }
    fs.delete(path, mustExist = false)
    fs.atomicMove(tmp, path)
}

/** 应用配置存储（各端提供路径：Android=filsDir，桌面=用户目录） */
class ConfigStore(private val fs: FileSystem, private val path: Path) {

    fun load(): AppConfig = try {
        readTextOrNull(fs, path)?.let { STORE_JSON.decodeFromString(AppConfig.serializer(), it) } ?: AppConfig()
    } catch (_: Exception) {
        AppConfig()
    }

    fun save(cfg: AppConfig) {
        try {
            writeTextAtomic(fs, path, STORE_JSON.encodeToString(AppConfig.serializer(), cfg))
        } catch (_: Exception) {
        }
    }
}

/** 解析 / 下载记录存储（新的在前，超过上限自动截断） */
class HistoryStore(private val fs: FileSystem, private val path: Path, private val maxEntries: Int = 1000) {

    @Serializable
    private data class Wrapper(val entries: List<HistoryEntry> = emptyList())

    fun load(): List<HistoryEntry> = try {
        readTextOrNull(fs, path)?.let { STORE_JSON.decodeFromString(Wrapper.serializer(), it).entries } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun replace(list: List<HistoryEntry>) {
        try {
            writeTextAtomic(fs, path, STORE_JSON.encodeToString(Wrapper.serializer(), Wrapper(list.take(maxEntries))))
        } catch (_: Exception) {
        }
    }

    fun add(entry: HistoryEntry) = replace(listOf(entry) + load())

    fun clear() = replace(emptyList())

    /** CSV 导出（对齐原版：两级折叠用的平台与作者列都在里面） */
    fun toCsv(list: List<HistoryEntry>): String {
        fun cell(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
        val head = listOf("时间", "平台", "作者", "归档作者", "作品 ID", "标题", "类型", "状态", "文件数", "目录", "链接", "错误")
        val sb = StringBuilder()
        sb.append(head.joinToString(",") { cell(it) }).append('\n')
        for (e in list) {
            sb.append(
                listOf(
                    dateTime(e.ts), e.platform, e.author, e.creator, e.id, e.title,
                    e.kind, e.status, e.files.toString(), e.dir, e.url, e.error,
                ).joinToString(",") { cell(it) },
            ).append('\n')
        }
        return sb.toString()
    }
}
