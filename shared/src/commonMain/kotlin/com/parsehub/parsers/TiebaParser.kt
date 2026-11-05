package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.Tier
import com.parsehub.core.ensureMime
import com.parsehub.core.extractUrl
import com.parsehub.core.md5Hex
import com.parsehub.core.parseJson
import com.parsehub.core.toHttps
import com.parsehub.net.Http
import com.parsehub.net.formEncode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 贴吧：帖子页走 /c/f/pb/page_pc 接口。
 * 需要先取一次匿名 tbs，再按「键排序拼接 + salt」做 md5 签名（对齐上游 provider_api/tieba.py）。
 */
object TiebaParser {
    private const val SALT = "36770b1f34c9bbf2e7d1a99d2b82fa9e"
    private const val REFERER = "https://tieba.baidu.com/"
    private val ID_RE = Regex("/p/(\\d+)")

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val url = extractUrl(text) ?: text
        val id = ID_RE.find(url)?.groupValues?.get(1)
            ?: throw RuntimeException("无法从链接中提取帖子 ID（需要 tieba.baidu.com/p/xxx）")

        val tbs = fetchTbs()
        val params = linkedMapOf(
            "pn" to "1",
            "lz" to "0",
            "r" to "2",
            "mark_type" to "0",
            "back" to "0",
            "fr" to "personalize_page",
            "kz" to id,
            "session_request_times" to "1",
            "tbs" to tbs,
            "subapp_type" to "pc",
            "_client_type" to "20",
        )
        params["sign"] = sign(params)
        val body = params.entries.joinToString("&") { formEncode(it.key) + "=" + formEncode(it.value) }

        val txt = Http.postRaw(
            url = "https://tieba.baidu.com/c/f/pb/page_pc",
            body = body,
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("User-Agent" to Http.UA, "Referer" to REFERER, "X-Requested-With" to "XMLHttpRequest"),
        )
        val json = parseJson(txt) as? JsonObject ?: throw RuntimeException("贴吧返回内容无法解析")
        if (json.int("error_code") != 0) {
            throw RuntimeException(json.str("error_msg").ifBlank { "贴吧接口返回错误" })
        }
        val thread = json.obj("thread") ?: throw RuntimeException("贴吧返回内容为空")
        val info = thread.obj("origin_thread_info") ?: thread

        val title = info.str("title").ifBlank { "贴吧帖子 $id" }
        val content = (info.arr("content") ?: JsonArray(emptyList()))
            .objs()
            .filter { it.int("type") == 0 }
            .mapNotNull { it.str("text").takeIf { s -> s.isNotEmpty() } }
            .joinToString("\n")

        val authorObj = info.obj("author")
        val author = info.str("author_name")
            .ifBlank { authorObj?.str("name_show").orEmpty() }
            .ifBlank { authorObj?.str("name").orEmpty() }
            .ifBlank { "贴吧用户" }

        val video = thread.obj("video_info")
        val tiers = mutableListOf<Tier>()
        val videoUrl = video?.str("video_url").orEmpty()
        if (videoUrl.isNotEmpty()) {
            tiers += Tier(
                idx = 0,
                gear = "原画",
                w = video?.int("video_width") ?: 0,
                h = video?.int("video_height") ?: 0,
                bps = 0L,
                size = 0L,
                h265 = false,
                fps = 0.0,
                fpsEst = true,
                isOrigin = true,
                urls = listOf(ensureMime(toHttps(videoUrl))),
            )
        }

        val images = mutableListOf<ImageItem>()
        info.arr("media")?.objs()?.forEachIndexed { i, m ->
            val u = m.str("big_pic").ifEmpty { m.str("url") }
            if (u.isNotEmpty()) {
                images += ImageItem(
                    index = i,
                    url = toHttps(u),
                    ext = extOf(u),
                    live = null,
                    w = m.int("width"),
                    h = m.int("height"),
                )
            }
        }

        val durSec = (video?.int("video_duration") ?: 0).toDouble()
        return ParseResult(
            platform = Platform.TIEBA,
            id = id,
            title = title,
            author = author,
            date = "",
            durSec = durSec,
            statLine = content.take(140),
            width = tiers.firstOrNull()?.w ?: images.firstOrNull()?.w ?: 0,
            height = tiers.firstOrNull()?.h ?: images.firstOrNull()?.h ?: 0,
            cover = video?.str("thumbnail_url")?.ifEmpty { null } ?: images.firstOrNull()?.url,
            referer = REFERER,
            tiers = tiers,
            aux = emptyList(),
            images = images,
        )
    }

    private suspend fun fetchTbs(): String {
        val txt = Http.getText("http://tieba.baidu.com/dc/common/tbs", mapOf("User-Agent" to Http.UA))
        val obj = parseJson(txt) as? JsonObject ?: throw RuntimeException("获取贴吧 tbs 失败")
        val tbs = obj.str("tbs")
        if (tbs.isEmpty()) throw RuntimeException("获取贴吧 tbs 失败")
        return tbs
    }

    private fun sign(params: Map<String, String>): String {
        val base = params.entries.sortedBy { it.key }.joinToString("") { it.key + "=" + it.value }
        return md5Hex((base + SALT).encodeToByteArray())
    }

    private fun extOf(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val dot = path.lastIndexOf('.')
        val e = if (dot > 0) path.substring(dot + 1).lowercase() else "jpg"
        return if (e.length in 1..5 && e.all { c -> c.isLetterOrDigit() }) e else "jpg"
    }
}
