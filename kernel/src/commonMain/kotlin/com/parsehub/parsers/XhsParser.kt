package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * 小红书页面解析，兼容桌面 noteDetailMap 和移动端 noteData。
 * 分享签名参数原样保留；移动 Safari 请求失败后仅回退一次桌面请求。
 * 无水印图片和原有视频选流策略保持不变，部分笔记仍需要登录 Cookie。
 */
object XhsParser {
    internal fun pageHeaders(mobile: Boolean, cookie: String = ""): Map<String, String> = buildMap {
        put("User-Agent", if (mobile) MOBILE_UA else Http.UA)
        put("Accept", if (mobile) MOBILE_ACCEPT else DESKTOP_ACCEPT)
        if (cookie.isNotEmpty()) put("Cookie", cookie)
    }

    private const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    private const val MOBILE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private const val DESKTOP_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
        "image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"

    // Do not use the shared extractUrl: it removes query parameters used in signed shares.
    internal fun shareUrl(text: String): String =
        Regex("https?://[^\\s\"'<>]+").find(text)?.value
            ?.trimEnd('，', '。', '；', ',', '.', ';')
            ?.replace(Regex("^(https?://)(www\\.)?xhs\\.com(?=[:/]|$)"), "$1$2xhslink.com")
            ?: throw RuntimeException("找不到链接")

    private fun extractId(url: String): String? {
        val path = try { Url(url).encodedPath } catch (_: IllegalArgumentException) { return null }
        return Regex("/(?:discovery/item|explore|item|note)/([a-zA-Z0-9]+)(?:/|$)")
            .find(path)?.groupValues?.get(1)
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

    suspend fun parse(text: String, opts: ParseOptions): ParseResult =
        parseWithFetcher(text, opts) { url, headers -> Http.getPage(url, headers) }

    // Retry the original signed URL, never a login/captcha redirect. At most two page requests.
    internal suspend fun parseWithFetcher(
        text: String,
        opts: ParseOptions,
        fetch: suspend (String, Map<String, String>) -> Http.Page,
    ): ParseResult {
        val url = shareUrl(text)
        val requestedId = extractId(url)
        var failure: PageFailure? = null
        for (mobile in listOf(true, false)) {
            try {
                val page = fetch(url, pageHeaders(mobile, opts.cookieXhs))
                return parsePage(page.status, page.url, page.body, requestedId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PageFailure) {
                if (failure == null || e.kind.priority > failure.kind.priority) failure = e
            } catch (_: Exception) {
                // Transport/parser errors may contain signed URLs or page content.
                if (failure == null) failure = PageFailure(FailureKind.REQUEST)
            }
        }
        throw failure ?: PageFailure(FailureKind.DATA)
    }

    internal enum class FailureKind(val messageText: String, val priority: Int) {
        REQUEST("小红书页面请求失败", 0),
        DATA("未找到可用笔记数据（可能需要登录 Cookie）", 1),
        IDENTITY("笔记身份不匹配", 2),
        LOGIN("该帖子需要登录后查看", 3),
        NOT_FOUND("帖子不存在或已失效", 4),
        RISK("被小红书风控拦截，请稍后重试", 5),
    }

    internal class PageFailure(val kind: FailureKind) : RuntimeException(kind.messageText)

    internal fun pageFailure(status: Int, url: String): FailureKind? {
        val path = try { Url(url).encodedPath.lowercase() } catch (_: IllegalArgumentException) { "" }
        return when {
            path.startsWith("/404/sec") || path.contains("/captcha") ||
                path.contains("/verify") || status == 403 || status == 429 -> FailureKind.RISK
            path == "/login" || path.startsWith("/login/") || status == 401 -> FailureKind.LOGIN
            path == "/404" || path.startsWith("/404/") || status == 404 || status == 410 -> FailureKind.NOT_FOUND
            status !in 200..299 -> FailureKind.REQUEST
            else -> null
        }
    }

    internal fun parsePage(status: Int, url: String, html: String, requestedId: String? = null): ParseResult {
        pageFailure(status, url)?.let { throw PageFailure(it) }
        val finalId = extractId(url)
        if (requestedId != null && finalId != null && requestedId != finalId) {
            throw PageFailure(FailureKind.IDENTITY)
        }
        val id = requestedId ?: finalId ?: throw PageFailure(FailureKind.DATA)
        return try {
            formatNote(selectNote(initialState(html), id), id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PageFailure) {
            throw e
        } catch (_: Exception) {
            throw PageFailure(FailureKind.DATA)
        }
    }

    internal fun selectNote(state: JsonObject, id: String): JsonObject {
        val desktop = state.obj("note")
        val details = desktop?.obj("noteDetailMap")
        fun checked(note: JsonObject?, keyedId: String? = null): JsonObject? {
            if (note == null || note.isEmpty()) return null
            val identities = listOf(note.str("noteId"), note.str("id")).filter { it.isNotEmpty() }
            if (identities.any { it != id } || (keyedId != null && keyedId != id)) return null
            // Requested map keys supply identity; H5 must carry an explicit note ID.
            if (keyedId == null && identities.isEmpty()) return null
            return note
        }
        checked(details?.obj(id)?.obj("note"), id)?.let { return it }
        val firstId = desktop?.str("firstNoteId").orEmpty()
        if (firstId.isNotEmpty()) checked(details?.obj(firstId)?.obj("note"), firstId)?.let { return it }
        checked(state.obj("noteData")?.obj("data")?.obj("noteData"))?.let { return it }
        if (details != null || state.obj("noteData") != null) throw PageFailure(FailureKind.IDENTITY)
        throw PageFailure(FailureKind.DATA)
    }

    internal fun initialState(html: String): JsonObject {
        val scripts = Regex("<script\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>(.*?)</script\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val assignment = Regex("(?:^|[;\\s])window\\.__INITIAL_STATE__\\s*=\\s*")
        for (script in scripts.findAll(html)) {
            val body = script.groupValues[1]
            val match = assignment.find(body) ?: continue
            try {
                return parseJson(StateReader(body, match.range.last + 1).value()) as? JsonObject ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A later script can contain the usable state.
            }
        }
        throw PageFailure(FailureKind.DATA)
    }

    /** Read JSON plus the two JS forms seen in state; never execute script. */
    private class StateReader(private val text: String, private var pos: Int) {
        private fun space() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun take(c: Char) { space(); require(pos < text.length && text[pos++] == c) }
        private fun word(s: String): Boolean {
            space()
            if (!text.startsWith(s, pos)) return false
            val end = pos + s.length
            if (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) return false
            pos = end
            return true
        }
        fun value(depth: Int = 0): String {
            require(depth < 128)
            space()
            require(pos < text.length)
            if (word("undefined")) return "null"
            if (word("new")) {
                require(word("Set"))
                take('(')
                space()
                val array = if (pos < text.length && text[pos] == ')') "[]" else {
                    require(pos < text.length && text[pos] == '[')
                    value(depth + 1)
                }
                take(')')
                return array
            }
            val start = pos
            return when (text[pos]) {
                '"' -> {
                    pos++
                    var escaped = false
                    while (pos < text.length) {
                        val c = text[pos++]
                        if (!escaped && c == '"') return text.substring(start, pos)
                        escaped = !escaped && c == '\\'
                    }
                    error("Unterminated string")
                }
                '{', '[' -> {
                    val objectValue = text[pos++] == '{'
                    val close = if (objectValue) '}' else ']'
                    val parts = mutableListOf<String>()
                    space()
                    while (pos < text.length && text[pos] != close) {
                        val part = if (objectValue) {
                            require(text[pos] == '"')
                            val key = value(depth + 1)
                            take(':')
                            key + ":" + value(depth + 1)
                        } else value(depth + 1)
                        parts += part
                        space()
                        if (pos < text.length && text[pos] == close) break
                        take(',')
                        space()
                    }
                    take(close)
                    (if (objectValue) "{" else "[") + parts.joinToString(",") + close
                }
                else -> {
                    while (pos < text.length && !text[pos].isWhitespace() && text[pos] !in ",]}):;") pos++
                    require(pos > start)
                    val token = text.substring(start, pos)
                    require(token in listOf("null", "true", "false") ||
                        Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(token))
                    token
                }
            }
        }
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
