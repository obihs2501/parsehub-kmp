package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.Tier
import com.parsehub.core.ensureMime
import com.parsehub.core.extractUrl
import com.parsehub.core.parseJson
import com.parsehub.core.toHttps
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 豆瓣：小组话题走移动版 rexxar 接口（无移动端 UA 会返回 418）。
 * 图床有防盗链，下载必须带 https://www.douban.com/ 的 Referer。
 */
object DoubanParser {
    private const val API = "https://m.douban.com/rexxar/api/v2/group/topic/"
    private const val IMAGE_REFERER = "https://www.douban.com/"
    private val idRe = Regex("douban[.]com/(group/)?topic/([0-9]+)")

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: text
        // 短链（douc.cc / doubanapp/dispatch）先跟到真实地址
        if ("douc.cc" in url || "doubanapp/dispatch" in url) {
            url = try {
                Http.followChain(url, mapOf("User-Agent" to Http.UA_MOBILE))
            } catch (_: Exception) {
                url
            }
        }
        val id = idRe.find(url)?.groupValues?.get(2)
            ?: throw RuntimeException("暂不支持该豆瓣链接（目前支持小组话题 /topic/<id> 与短链）")

        val headers = mutableMapOf("User-Agent" to Http.UA_MOBILE, "Referer" to "https://m.douban.com/")
        opts.cookie("douban").takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }

        val txt = try {
            Http.getText(API + id, headers)
        } catch (e: Exception) {
            throw RuntimeException(
                "获取豆瓣话题失败：" + (e.message?.take(60) ?: "未知错误") + "（话题可能不存在，或需要在 Cookie 池里填豆瓣登录态）",
            )
        }
        val json = parseJson(txt) as? JsonObject ?: throw RuntimeException("豆瓣返回内容无法解析")

        val title = json.str("title").ifBlank { "豆瓣话题 $id" }
        val content = json.str("content")
        val author = json.obj("author")?.str("name").orEmpty().ifBlank { "豆瓣用户" }
        val video = json.obj("video_info")
        val videoUrl = video?.str("video_url").orEmpty()

        val tiers = mutableListOf<Tier>()
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
        json.arr("photos")?.objs()?.forEachIndexed { i, p ->
            val image = p.obj("image") ?: return@forEachIndexed
            val animatedVideo = image.obj("video")
            val animatedUrl = if (image.bool("is_animated")) animatedVideo?.str("url").orEmpty() else ""
            if (animatedUrl.isNotEmpty()) {
                // 动图的原始 GIF 极大，豆瓣同时给了等效 mp4，优先取 mp4
                images += ImageItem(
                    i, toHttps(animatedUrl), "mp4", null,
                    animatedVideo?.int("width") ?: 0, animatedVideo?.int("height") ?: 0,
                )
            } else {
                val large = image.obj("large")
                val normal = image.obj("normal")
                val src = if (large?.str("url").orEmpty().isNotEmpty()) large else normal
                val u = src?.str("url").orEmpty()
                if (u.isNotEmpty()) {
                    images += ImageItem(i, toHttps(u), "jpg", null, src?.int("width") ?: 0, src?.int("height") ?: 0)
                }
            }
        }

        return ParseResult(
            platform = Platform.DOUBAN,
            id = id,
            title = title,
            author = author,
            date = "",
            durSec = parseDuration(video?.any("duration")),
            statLine = content.replace('\n', ' ').trim().take(140),
            width = tiers.firstOrNull()?.w ?: images.firstOrNull()?.w ?: 0,
            height = tiers.firstOrNull()?.h ?: images.firstOrNull()?.h ?: 0,
            cover = video?.str("cover_url")?.ifEmpty { null } ?: images.firstOrNull()?.url,
            referer = IMAGE_REFERER,
            tiers = tiers,
            aux = emptyList(),
            images = images,
        )
    }

    /** 时长可能是秒数，也可能是 HH:MM:SS / MM:SS */
    private fun parseDuration(el: JsonElement?): Double {
        val raw = (el as? JsonPrimitive)?.contentOrNull ?: return 0.0
        if (raw.isEmpty()) return 0.0
        raw.toDoubleOrNull()?.let { return it }
        var sec = 0.0
        for (part in raw.split(':')) {
            val v = part.toIntOrNull() ?: return 0.0
            sec = sec * 60 + v
        }
        return sec
    }
}
