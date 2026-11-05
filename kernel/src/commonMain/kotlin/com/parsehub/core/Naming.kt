package com.parsehub.core

/**
 * 命名模板（对齐原版 Studio 的 naming.py）：
 * 支持 {platform} {author} {creator} {title} {id} {publish_date} {date} {time} {index} {type}
 * 仅序号支持格式，例如 {index:03d}
 */
object Naming {
    val tokens = listOf(
        "platform", "author", "creator", "title", "id",
        "publish_date", "date", "time", "index", "type",
    )
    const val DEFAULT_FOLDER = "{platform}_{title}_{id}"
    const val DEFAULT_FILE = "{index:03d}_{title}"

    private val illegal = charArrayOf('/', '<', '>', ':', '"', '|', '?', '*')
    private val backslash = 92.toChar()
    private val reserved = listOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** 合法返回 null，否则返回错误说明 */
    fun validate(template: String): String? {
        if (template.isBlank()) return "模板不能为空"
        if (template.length > 200) return "模板不能超过 200 字符"
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' -> {
                    val end = template.indexOf('}', i)
                    if (end < 0) return "模板缺少右花括号"
                    val inner = template.substring(i + 1, end)
                    val field = inner.substringBefore(':')
                    val spec = inner.substringAfter(':', "")
                    if (field !in tokens) return "不支持的命名元素: $field"
                    if (spec.isNotEmpty()) {
                        if (field != "index") return "只有序号支持格式，例如 {index:03d}"
                        if (!Regex("0?[1-6]d").matches(spec)) return "序号格式应为 {index:03d}"
                    }
                    i = end + 1
                }
                c == '}' -> return "模板里有多余的右花括号"
                c == '/' || c == backslash || c in illegal -> return "模板不能包含路径分隔符或非法字符"
                else -> i++
            }
        }
        return null
    }

    fun render(template: String, values: Map<String, String>, index: Int = 1): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i)
                if (end < 0) {
                    sb.append(c)
                    i++
                    continue
                }
                val inner = template.substring(i + 1, end)
                val field = inner.substringBefore(':')
                val spec = inner.substringAfter(':', "")
                val text = when {
                    field == "index" -> if (spec.isEmpty()) index.toString() else padIndex(index, spec)
                    else -> values[field] ?: ""
                }
                sb.append(text)
                i = end + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun padIndex(index: Int, spec: String): String {
        val digits = spec.trimEnd('d').toIntOrNull() ?: 3
        val s = index.toString()
        return if (s.length >= digits) s else "0".repeat(digits - s.length) + s
    }

    /** 文件名清洗（对齐原版 safe_name）：非法字符替换、空白折叠、限制长度、避开 Windows 保留名 */
    fun safeName(value: String, limit: Int = 100): String {
        val cleaned = buildString {
            for (ch in value) {
                append(
                    if (ch.code < 32 || ch.code == 127 || ch in illegal || ch == backslash) '_' else ch,
                )
            }
        }
        var v = cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        v = v.trim(' ', '.')
        if (v.length > limit) v = v.take(limit).trimEnd(' ', '.')
        if (v.isEmpty()) return "未命名"
        if (v.substringBefore('.').uppercase() in reserved) v = "_" + v
        return v
    }

    /** 同名不覆盖：base 冲突时补 _1 _2 …（保留扩展名） */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "${stem}_$i$ext"
            if (!exists(candidate)) return candidate
            i++
        }
        throw IllegalStateException("同名文件过多，请修改命名模板")
    }
}
