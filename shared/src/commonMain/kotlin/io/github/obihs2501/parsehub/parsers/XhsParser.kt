package io.github.obihs2501.parsehub.parsers

import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * 小红书解析（移植自 short_videos XiaohongshuParser.php，尽力而为版）：
 * 短链 -> 真实链接 -> 页面 window.__INITIAL_STATE__ -> noteDetailMap / noteData。
 * 无水印图走 xhscdn 的 imageView2 去水印路径；视频取 h265 优先、按码率排序。
 * 注意：部分笔记需要登录 Cookie（设置里可填），否则可能拿不到 INITIAL_STATE。
 */
object XhsParser {
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"

    private fun extractId(url: String): String? {
        for (pat in listOf(
            "/discovery/item/([a-zA-Z0-9]+)",
            "/explore/([a-zA-Z0-9]+)",
            "/item/([a-zA-Z0-9]+)",
            "/note/([a-zA-Z0-9]+)",
        )) {
            Regex(pat).find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun processImageUrl(url: String): String {
        if (url.isEmpty()) return url
        Regex("/oss-sg/([a-zA-Z0-9_]+)/([a-zA-Z0-9]+)!").find(url)?.let { m ->
            val dir = m.groupValues[1]
            if (!Regex("^[a-f0-9]{32}$").matches(dir) && dir.toLongOrNull() == null) {
                return "https://sns-img-hw.xhscdn.com/oss-sg/$dir/${m.groupValues[2]}?imageView2/2/w/0/format/jpg"
            }
        }
        Regex("/([a-zA-Z0-9_]+)/([a-zA-Z0-9]+)!").find(url)?.let { m ->
            val dir = m.groupValues[1]
            if (!Regex("^[a-f0-9]{32}$").matches(dir) && dir.toLongOrNull() == null) {
                return "https://sns-img-hw.xhscdn.com/$dir/${m.groupValues[2]}?imageView2/2/w/0/format/jpg"
            }
        }
        Regex("(notes_pre_post|spectrum|notes_uhdr)/([a-zA-Z0-9]+)").find(url)?.let { m ->
            return "https://sns-img-hw.xhscdn.com/${m.groupValues[1]}/${m.groupValues[2]}?imageView2/2/w/0/format/jpg"
        }
        Regex("/([a-zA-Z0-9]+)!").find(url)?.let { m ->
            return "https://ci.xiaohongshu.com/${m.groupValues[1]}?imageView2/2/w/0/format/jpg"
        }
        return toHttps(url)
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        url = url.replace("xhs.com", "xhslink.com")
        val id: String
        if ("www.xiaohongshu.com" in url) {
            id = extractId(url) ?: throw RuntimeException("链接格式错误，无法提取 ID")
        } else {
            val final = Http.followChain(url, mapOf("User-Agent" to UA))
            id = extractId(final) ?: extractId(url)
            ?: throw RuntimeException("链接格式错误，无法提取 ID")
            url = final
        }

        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        )
        if (opts.cookieXhs.isNotEmpty()) headers["Cookie"] = opts.cookieXhs
        val page = Http.getText(url, headers)

        val m = Regex(
            "<script>\\s*window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\})</script>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(page) ?: throw RuntimeException("未找到笔记数据（可能需要登录 Cookie）或链接已失效")
        val json = parseJson(m.groupValues[1].replace("undefined", "null")) as? JsonObject
            ?: throw RuntimeException("笔记数据解析失败")

        val note = json.obj("note")?.obj("noteDetailMap")?.obj(id)?.obj("note")
            ?: json.obj("noteData")?.obj("data")?.obj("noteData")
            ?: throw RuntimeException("未找到笔记详情（可能需要登录 Cookie）")

        return formatNote(note, id)
    }

    private fun formatNote(note: JsonObject, id: String): ParseResult {
        var type = note.str("type", "image")
        if (type == "normal") type = "image"
        val imageList = note.arr("imageList")?.objs() ?: emptyList()

        var cover = imageList.firstOrNull()?.let { im ->
            im.str("urlPre").ifEmpty { im.str("urlDefault") }.ifEmpty { im.str("url") }
        }.orEmpty()
        if (cover.isEmpty() && type == "video") {
            val fid = note.obj("video")?.obj("image")?.str("thumbnailFileid").orEmpty()
            if (fid.isNotEmpty()) cover = "https://sns-img-hw.xhscdn.com/$fid"
        }
        if (cover.isEmpty()) cover = note.obj("cover")?.str("url").orEmpty()
        if (cover.isEmpty()) {
            val fid = note.obj("cover")?.str("fileId").orEmpty()
            if (fid.isNotEmpty()) cover = "https://sns-img-hw.xhscdn.com/$fid?imageView2/2/w/0/format/jpg"
        }

        var videoUrl: String? = null
        var videoBackup: String? = null
        if (type == "video") {
            data class S(val codec: Int, val bps: Double, val url: String)

            val streams = mutableListOf<S>()
            val stream = note.obj("video")?.obj("media")?.obj("stream")
            for ((arr, codecPrio) in listOf(
                stream?.arr("h265") to 0,
                stream?.arr("h264") to 1,
            )) {
                arr?.objs()?.forEach { s ->
                    val u = s.str("masterUrl")
                    if (u.isNotEmpty()) {
                        streams += S(codecPrio, s.dbl("avgBitrate").takeIf { it > 0 } ?: s.dbl("videoBitrate"), u)
                    }
                }
            }
            streams.sortWith(compareBy({ it.codec }, { -it.bps }))
            if (streams.isNotEmpty()) {
                videoUrl = streams[0].url
                if (streams.size > 1) videoBackup = streams[1].url
            }
            if (videoUrl == null) {
                val key = note.obj("video")?.obj("consumer")?.str("originVideoKey").orEmpty()
                if (key.isNotEmpty()) videoUrl = "http://sns-video-bd.xhscdn.com/$key"
            }
        }

        val images = mutableListOf<ImageItem>()
        for ((i, img) in imageList.withIndex()) {
            val u = img.str("url").ifEmpty { img.str("urlDefault") }.ifEmpty { img.str("urlPre") }
            if (u.isEmpty()) continue
            val live = img.obj("stream")?.arr("h264")?.objs()?.firstOrNull()?.str("masterUrl")
                ?: img.obj("stream")?.arr("h265")?.objs()?.firstOrNull()?.str("masterUrl")
            images += ImageItem(i, processImageUrl(u), "jpg", live?.takeIf { it.isNotEmpty() }, 0, 0)
        }

        val tiers = mutableListOf<Tier>()
        if (videoUrl != null) {
            val urls = listOfNotNull(videoUrl, videoBackup).map { ensureMime(toHttps(it)) }.distinct()
            tiers += Tier(0, "原画", 0, 0, 0, 0, false, 0.0, false, true, urls)
        }
        if (tiers.isEmpty() && images.isEmpty()) throw RuntimeException("未提取到视频或图片")

        val user = note.obj("user")
        return ParseResult(
            platform = Platform.XHS,
            id = id,
            title = note.str("title").ifEmpty { note.str("desc") }.ifEmpty { "无标题" }.take(120),
            author = user?.str("nickname").orEmpty().ifEmpty { user?.str("nickName").orEmpty() }
                .ifEmpty { "未知作者" },
            date = today(),
            durSec = 0.0,
            statLine = "",
            width = 0, height = 0,
            cover = processImageUrl(cover).ifEmpty { images.firstOrNull()?.url ?: "" }.ifEmpty { null },
            referer = Platform.XHS.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
        )
    }
}
