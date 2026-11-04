package com.parsehub.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Fmt {
    fun size(n: Long): String {
        if (n <= 0) return "-"
        var v = n.toDouble()
        for (u in listOf("B", "KB", "MB", "GB", "TB")) {
            if (v < 1024 || u == "TB") {
                return if (u == "B") "${v.roundToLong()}B" else "${fmt2(v)}$u"
            }
            v /= 1024
        }
        return "-"
    }

    fun bps(b: Long): String {
        if (b <= 0) return "-"
        return if (b >= 1_000_000) "${fmt2(b / 1e6)}M" else "${(b / 1e3).roundToLong()}K"
    }

    fun dur(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }

    fun fps(v: Double, est: Boolean): String {
        if (v <= 0) return "-"
        val s = if (abs(v - v.roundToLong()) < 0.005) "${v.roundToLong()}" else fmt2(v)
        return (if (est) "≈" else "") + s + "fps"
    }

    private fun fmt2(v: Double): String {
        val s = ((v * 100).roundToLong() / 100.0).toString()
        return s
    }
}

private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]")

fun sanitize(s: String, maxLen: Int = 80): String {
    var x = ILLEGAL.replace(s, "_")
    x = Regex("\\s+").replace(x, " ").trim().trim('.')
    if (x.length > maxLen) x = x.take(maxLen).trimEnd(' ', '.')
    return x.ifEmpty { "_" }
}

fun makeName(author: String, date: String, title: String, suffix: String, ext: String): String {
    val a = sanitize(author, 40)
    var t = sanitize(title, 100)
    val sfx = sanitize(suffix, 30)
    var name = listOf(a, date, t, sfx).filter { it.isNotEmpty() }.joinToString("_")
    val limit = 120 - ext.length - 1
    if (name.length > limit) {
        val over = name.length - limit
        t = t.dropLast(min(over, (t.length - 8).coerceAtLeast(0))).trimEnd(' ', '.')
        name = listOf(a, date, t, sfx).filter { it.isNotEmpty() }.joinToString("_")
    }
    return "$name.$ext"
}

fun qualityTag(t: Tier): String {
    if (t.isOrigin) return "原画"
    val short = min(t.w, t.h)
    return if (short > 0) "${short}p" else sanitize(t.gear, 20)
}

/** http -> https 统一 */
fun toHttps(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return if (url.startsWith("http://")) "https://" + url.substring(7) else url
}

/** 视频直链补 mime_type=video_mp4（已有则不重复） */
fun ensureMime(url: String): String {
    if (url.isEmpty() || !url.startsWith("http")) return url
    val hash = url.indexOf('#')
    val base = if (hash >= 0) url.substring(0, hash) else url
    val frag = if (hash >= 0) url.substring(hash) else ""
    val q = base.substringAfter('?', "")
    if (q.isNotEmpty() && Regex("(?:^|&)mime_type=").containsMatchIn(q)) return url
    return base + (if ('?' in base) "&" else "?") + "mime_type=video_mp4" + frag
}

fun maskSecret(s: String, keep: Int = 6): String = if (s.isEmpty()) "(未设置)" else s.take(keep) + "…(${s.length}字符)"

/** 毫秒时间戳（秒/毫秒兼容）-> yyyy-MM-dd（固定东八区） */
fun fmtDate(tsRaw: Long): String {
    var ts = tsRaw
    if (ts >= 1_000_000_000_000L) ts /= 1000
    if (ts !in 1_000_000_000L..4_102_444_800L) return ""
    val days = (ts + 8 * 3600) / 86400          // UTC+8
    return civilFromDays(days)
}

/** Howard Hinnant 的 civil_from_days 算法（纯 Kotlin，免时区依赖） */
private fun civilFromDays(z0: Long): String {
    var z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "${year}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

fun today(): String = fmtDate(nowMillis() / 1000).ifEmpty { "0000-00-00" }
