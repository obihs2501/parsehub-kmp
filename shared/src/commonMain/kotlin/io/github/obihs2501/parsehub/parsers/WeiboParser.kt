package io.github.obihs2501.parsehub.parsers

import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.net.Http
import io.github.obihs2501.parsehub.net.formEncode
import kotlinx.serialization.json.JsonObject

/**
 * 微博视频解析（移植自 short_videos weibo.php）。
 * 不使用第三方代理：直链由下载器带 https://weibo.com/ 的 Referer 请求。
 */
object WeiboParser {
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    private fun fixUrl(u: String): String {
        var s = u.trim().trim('"')
        s = when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http") -> s
            s.isEmpty() -> s
            else -> "https://$s"
        }
        return toHttps(s)
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        if ("t.cn/" in url) url = Http.followChain(url, mapOf("User-Agent" to UA))
        val fid = extractFid(url) ?: throw RuntimeException("无法从链接提取视频 ID")
        return fetchInfo(fid, opts)
    }

    private fun extractFid(url: String): String? {
        Regex("[?&]fid=([^&?]+)").find(url)?.let { return it.groupValues[1] }
        Regex("weibo\\.com/tv/(?:show|v)/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        return null
    }

    private suspend fun fetchInfo(fid: String, opts: ParseOptions): ParseResult {
        val body = "data=" + formEncode("{\"Component_Play_Playinfo\":{\"oid\":\"$fid\"}}")
        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "https://weibo.com/",
            "Accept" to "application/json, text/plain, */*",
        )
        if (opts.cookieWeibo.isNotEmpty()) headers["Cookie"] = opts.cookieWeibo
        val resp = Http.postRaw(
            "https://weibo.com/tv/api/component?page=" + formEncode("/tv/show/$fid"),
            body, "application/x-www-form-urlencoded", headers,
        )
        val j = parseJson(resp) as? JsonObject ?: throw RuntimeException("接口响应解析失败")
        val info = j.obj("data")?.obj("Component_Play_Playinfo")
            ?: throw RuntimeException("解析失败，视频不存在或接口已失效")
        val urlsMap = info.obj("urls") ?: JsonObject(emptyMap())

        data class Q(val label: String, val priority: Int, val url: String)

        val qs = urlsMap.entries.map { (label, v) ->
            val p = when {
                "2K" in label -> 4
                "1080P" in label -> 3
                "720P" in label -> 2
                "480P" in label -> 1
                else -> 0
            }
            Q(label, p, fixUrl(v.toString()))
        }.filter { it.url.isNotEmpty() }.sortedByDescending { it.priority }

        if (qs.isEmpty()) throw RuntimeException("未获取到视频直链（可能已过期）")
        val tiers = qs.mapIndexed { i, q ->
            Tier(
                i, q.label, 0, 0, 0, 0, false, 0.0, false, q.priority >= 2,
                listOf(ensureMime(q.url)),
            )
        }
        val stat = listOfNotNull(
            info.str("play_count").takeIf { it.isNotEmpty() }?.let { "播 $it" },
            info.str("attitudes_count").takeIf { it.isNotEmpty() }?.let { "赞 $it" },
            info.str("comments_count").takeIf { it.isNotEmpty() }?.let { "评 $it" },
            info.str("reposts_count").takeIf { it.isNotEmpty() }?.let { "转 $it" },
        ).joinToString(" / ")
        val date = Regex("\\d{4}-\\d{2}-\\d{2}").find(info.str("date"))?.value ?: today()
        return ParseResult(
            platform = Platform.WEIBO,
            id = fid,
            title = info.str("title").ifEmpty { "无标题" }.take(120),
            author = info.str("author").ifEmpty { "未知作者" },
            date = date,
            durSec = info.str("duration_time").toDoubleOrNull() ?: 0.0,
            statLine = stat,
            width = 0, height = 0,
            cover = fixUrl(info.str("cover_image")).ifEmpty { null },
            referer = Platform.WEIBO.referer,
            tiers = tiers,
            aux = emptyList(),
            images = emptyList(),
        )
    }
}
