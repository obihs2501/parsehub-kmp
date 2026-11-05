package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http
import com.parsehub.net.formEncode
import io.ktor.http.Url
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

    internal data class Route(val id: String, val isTv: Boolean)

    private fun parsedUrl(url: String): Url? = runCatching { Url(url) }.getOrNull()

    internal fun route(url: String): Route? {
        val parsed = parsedUrl(url) ?: return null
        if (parsed.protocol.name !in listOf("http", "https")) return null
        if (parsed.host.lowercase() !in setOf(
                "weibo.com", "www.weibo.com", "m.weibo.com", "video.weibo.com",
                "weibo.cn", "www.weibo.cn", "m.weibo.cn",
            )) return null
        val path = parsed.encodedPath.trimEnd('/')
        parsed.parameters["fid"]?.takeIf { Regex("[A-Za-z0-9:_-]+").matches(it) }
            ?.let { return Route(it, true) }
        Regex("^/tv/(?:show|v)/([A-Za-z0-9:_-]+)$").matchEntire(path)
            ?.let { return Route(it.groupValues[1], true) }
        val match = Regex("^/(?:[0-9]+|status|detail)/([A-Za-z0-9]+)$").matchEntire(path)
            ?: return null
        return Route(match.groupValues[1], false)
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val parsed = parsedUrl(url)
        if (parsed?.host?.lowercase() == "t.cn" ||
            (parsed?.host?.lowercase() == "mapp.api.weibo.cn" && parsed.encodedPath.startsWith("/fx/")) ||
            (parsed?.host?.lowercase() == "video.weibo.com" && parsed.encodedPath.startsWith("/show"))) {
            url = Http.followChain(url, mapOf("User-Agent" to UA))
        }
        val route = route(url) ?: throw RuntimeException("无法从链接提取微博 ID")
        if (route.isTv) return fetchInfo(route.id, opts)
        val response = Http.getJson(
            "https://weibo.com/ajax/statuses/show?id=${formEncode(route.id)}&isGetLongText=true",
            statusHeaders(opts),
        ) as? JsonObject ?: throw RuntimeException("接口响应解析失败")
        return formatStatus(response, route.id)
    }

    internal fun statusHeaders(opts: ParseOptions): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Referer", "https://weibo.com/")
        put("Accept", "application/json, text/plain, */*")
        if (opts.cookieWeibo.isNotEmpty()) put("Cookie", opts.cookieWeibo)
    }

    /** statuses/show 的纯格式化入口；不依赖网络或当前日期。 */
    internal fun formatStatus(status: JsonObject, requestedId: String): ParseResult {
        val user = status.obj("user")
        val page = status.obj("page_info")
        val media = page?.obj("media_info")
        val tiers = mutableListOf<Tier>()
        var duration = media?.dbl("duration") ?: 0.0
        media?.arr("playback_list")?.objs()?.forEach { playback ->
            val info = playback.obj("play_info") ?: return@forEach
            val url = fixUrl(info.str("url"))
            if (url.isEmpty() || tiers.any { it.urls.first() == ensureMime(url) }) return@forEach
            tiers += Tier(
                tiers.size, info.str("quality_desc").ifEmpty { "线路${tiers.size + 1}" },
                info.int("width"), info.int("height"), info.lng("bitrate"), info.lng("size"),
                false, 0.0, false, tiers.isEmpty(), listOf(ensureMime(url)),
            )
            if (duration <= 0) duration = info.dbl("duration")
        }
        if (tiers.isEmpty() && media != null) {
            val url = fixUrl(media.str("mp4_hd_url")).ifEmpty { fixUrl(media.str("mp4_sd_url")) }
            if (url.isNotEmpty()) tiers += Tier(
                0, "默认", 0, 0, 0, 0, false, 0.0, false, true, listOf(ensureMime(url)),
            )
        }

        // 转发图片优先级与恢复的 ParseHub 实现一致；pic_ids 保留服务端的图集顺序。
        val retweet = status.obj("retweeted_status")
        val photoStatus = if (retweet?.obj("pic_infos")?.isNotEmpty() == true) retweet ?: status else status
        val pictures = photoStatus.obj("pic_infos")
        val ids = photoStatus.arr("pic_ids")?.strs()?.takeIf { it.isNotEmpty() }
            ?: pictures?.keys?.toList().orEmpty()
        val images = ids.mapIndexedNotNull { index, id ->
            val picture = pictures?.obj(id) ?: return@mapIndexedNotNull null
            val largest = picture.obj("largest") ?: picture.obj("original") ?: picture.obj("large")
                ?: picture.obj("thumbnail") ?: return@mapIndexedNotNull null
            val url = fixUrl(largest.str("url"))
            if (url.isEmpty()) return@mapIndexedNotNull null
            val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "jpg")
                .lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
            ImageItem(index, url, ext, fixUrl(picture.str("video")).ifEmpty { null },
                largest.int("width"), largest.int("height"))
        }
        if (tiers.isEmpty() && images.isEmpty()) {
            throw RuntimeException("未获取到微博媒体（可能需要 Cookie 或微博已失效）")
        }
        val text = status.str("text_raw").ifEmpty { status.str("text").replace(Regex("<[^>]+>"), " ") }
            .let { text -> page?.str("short_url")?.takeIf { it.isNotEmpty() }?.let { text.replace(it, "") } ?: text }
            .replace("&nbsp;", " ").replace("&amp;", "&").trim()
        val date = status.str("created_at").let { value ->
            Regex("""\d{4}-\d{2}-\d{2}""").find(value)?.value ?: run {
                val parts = value.split(Regex("""\s+"""))
                val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                    .indexOf(parts.getOrNull(1)) + 1
                if (parts.size >= 6 && month > 0) "${parts[5]}-${month.toString().padStart(2, '0')}-${parts[2].padStart(2, '0')}"
                else "0000-00-00"
            }
        }
        return ParseResult(
            platform = Platform.WEIBO,
            id = status.str("idstr").ifEmpty { status.str("id").ifEmpty { requestedId } },
            title = text.ifEmpty { "无标题" }.take(120),
            author = user?.str("screen_name").orEmpty().ifEmpty { "未知作者" },
            authorId = user?.str("idstr").orEmpty().ifEmpty { user?.str("id").orEmpty() },
            date = date, durSec = duration,
            statLine = listOf("attitudes_count" to "赞", "comments_count" to "评", "reposts_count" to "转")
                .mapNotNull { (key, label) -> status.str(key).takeIf { it.isNotEmpty() }?.let { "$label $it" } }
                .joinToString(" / "),
            width = tiers.firstOrNull()?.w ?: 0, height = tiers.firstOrNull()?.h ?: 0,
            cover = fixUrl(page?.str("page_pic").orEmpty()).ifEmpty { images.firstOrNull()?.url },
            referer = Platform.WEIBO.referer, tiers = tiers, aux = emptyList(), images = images,
        )
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
