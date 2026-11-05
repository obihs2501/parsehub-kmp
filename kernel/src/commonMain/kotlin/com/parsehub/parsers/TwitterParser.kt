package com.parsehub.parsers

import com.parsehub.core.ImageItem
import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.Tier
import com.parsehub.core.arr
import com.parsehub.core.ensureMime
import com.parsehub.core.extractUrl
import com.parsehub.core.int
import com.parsehub.core.lng
import com.parsehub.core.obj
import com.parsehub.core.parseJson
import com.parsehub.core.str
import com.parsehub.core.today
import com.parsehub.core.toHttps
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * X / Twitter 单条推文媒体解析（移植自 ParseHub-Studio 的 provider_api/twitter.py 的纯 HTTP 路径）。
 *
 * 走 X 官方嵌入组件用的公开 syndication 接口：只需要推文 ID 与一个可计算的 token，
 * 不需要任何硬编码 bearer / 凭据，公开推文匿名即可解析。
 * 响应结构与 GraphQL 略有差异（mediaDetails / video_info 在顶层），这里做兼容。
 */
object TwitterParser {
    private const val HOME = "https://x.com/"
    private const val SYNDICATION = "https://cdn.syndication.twimg.com/tweet-result"

    private fun extractId(url: String): String? =
        Regex("""/(?:status|statuses)/(\d+)""").find(url)?.groupValues?.get(1)

    /**
     * 嵌入组件的公开 token 算法：取 (id / 1e15) * π 的小数部分，按 36 进制展开后去掉 0。
     * 纯计算，不是凭据；服务端只用它做轻量校验。
     */
    private fun syndicationToken(id: String): String {
        val n = id.toDoubleOrNull() ?: return "a"
        var frac = (n / 1e15) * 3.141592653589793
        frac -= kotlin.math.floor(frac)
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        for (i in 0 until 12) {
            frac *= 36
            val d = kotlin.math.floor(frac).toInt().coerceIn(0, 35)
            if (digits[d] != '0') sb.append(digits[d])
            frac -= kotlin.math.floor(frac)
        }
        return sb.toString().ifEmpty { "a" }
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val id = extractId(url) ?: throw RuntimeException("无法从链接提取推文 ID")
        val cookie = opts.cookie("twitter")

        val headers = mutableMapOf(
            "User-Agent" to Http.UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to HOME,
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val endpoint = "$SYNDICATION?id=$id&lang=en&token=${syndicationToken(id)}"
        val text2 = Http.getText(endpoint, headers)
        val root = parseJson(text2) as? JsonObject
            ?: throw RuntimeException("接口返回的不是 JSON")
        if (root.str("__typename").isNotEmpty() && root.arr("mediaDetails") == null &&
            root.obj("video") == null && root.str("text").isEmpty()
        ) {
            throw RuntimeException("推文不存在、已删除或仅登录可见")
        }

        val title = root.str("text").ifEmpty { "X $id" }.take(120)
        val date = Regex("\\d{4}-\\d{2}-\\d{2}").find(root.str("created_at"))?.value ?: today()
        val user = root.obj("user") ?: root.obj("core")?.obj("user_results")?.obj("result")?.obj("legacy")
        val author = user?.str("screen_name").orEmpty()
        val authorId = user?.str("id_str").orEmpty()
        val fav = root.lng("favorite_count", root.lng("favourites_count", 0L))

        var cover: String? = null
        val tiers = mutableListOf<Tier>()
        val images = mutableListOf<ImageItem>()
        val media = root.arr("mediaDetails")
            ?: root.obj("extended_entities")?.arr("media")
            ?: root.obj("entities")?.arr("media")

        media?.mapNotNull { it as? JsonObject }?.forEachIndexed { i, m ->
            val info = m.obj("video_info") ?: m.obj("video")
            val variants = info?.arr("variants")
            if (variants != null) {
                var best = ""
                var bestBps = 0
                var bw = 0
                var bh = 0
                for (v in variants) {
                    val o = v as? JsonObject ?: continue
                    val u = o.str("url").ifEmpty { o.str("src") }
                    if (u.isEmpty() || !u.contains(".mp4")) continue
                    val b = o.int("bitrate", 0)
                    if (b >= bestBps) {
                        best = u; bestBps = b
                        val sz = o.obj("aspect_ratio")
                        bw = sz?.get("width")?.toString()?.trim('"')?.toIntOrNull()
                            ?: m.obj("original_info")?.int("width", 0) ?: 0
                        bh = sz?.get("height")?.toString()?.trim('"')?.toIntOrNull()
                            ?: m.obj("original_info")?.int("height", 0) ?: 0
                    }
                }
                if (best.isNotEmpty()) {
                    tiers += Tier(
                        idx = tiers.size, gear = if (tiers.isEmpty()) "原画" else "片段${i + 1}",
                        w = bw, h = bh, bps = bestBps.toLong(), size = 0, h265 = false,
                        fps = 0.0, fpsEst = false, isOrigin = tiers.isEmpty(),
                        urls = listOf(ensureMime(toHttps(best))),
                    )
                }
                toHttps(m.str("media_url_https")).takeIf { it.isNotEmpty() }?.let { cover = it }
            } else {
                val img = toHttps(m.str("media_url_https"))
                if (img.isNotEmpty()) {
                    val sz = m.obj("sizes")?.obj("large") ?: m.obj("original_info")
                    val iw = sz?.int("w", sz.int("width", 0)) ?: 0
                    val ih = sz?.int("h", sz.int("height", 0)) ?: 0
                    images += ImageItem(i, img, "jpg", null, iw, ih)
                    if (cover == null) cover = img
                }
            }
        }

        return ParseResult(
            platform = Platform.TWITTER,
            id = id,
            title = title,
            author = author.ifEmpty { "未知作者" },
            date = date,
            durSec = infoDur(root.obj("video")),
            statLine = if (fav > 0) "赞 $fav" else "",
            width = 0, height = 0,
            cover = cover,
            referer = Platform.TWITTER.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
            authorId = authorId,
        )
    }

    private fun infoDur(video: JsonObject?): Double {
        if (video == null) return 0.0
        val ms = video.lng("duration_millis", 0L).toDouble()
        return if (ms > 0) ms / 1000.0 else 0.0
    }
}
