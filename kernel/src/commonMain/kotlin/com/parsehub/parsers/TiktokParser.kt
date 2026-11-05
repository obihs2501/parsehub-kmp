package com.parsehub.parsers

import com.parsehub.core.ImageItem
import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.Tier
import com.parsehub.core.arr
import com.parsehub.core.ensureMime
import com.parsehub.core.extractUrl
import com.parsehub.core.fmtDate
import com.parsehub.core.int
import com.parsehub.core.lng
import com.parsehub.core.obj
import com.parsehub.core.parseJson
import com.parsehub.core.str
import com.parsehub.core.strs
import com.parsehub.core.today
import com.parsehub.core.toHttps
import com.parsehub.net.Http
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * TikTok 视频 / 图集解析（移植自 ParseHub-Studio 的 provider_api/tiktok.py 的纯 HTTP 路径）。
 *
 * 优先走移动端 feed 接口；失败时回退到网页 __UNIVERSAL_DATA_FOR_REHYDRATION__。
 * 不带任何签名，设备参数与 x-ladon 是上游公开常量；用户可按 tiktok 平台填 Cookie。
 * 下载直链需要带 https://www.tiktok.com/ 的 Referer（见 Platform.TIKTOK.referer）。
 */
object TiktokParser {
    private const val FEED = "https://api22-normal-c-alisg.tiktokv.com/aweme/v1/feed/"
    private val UA_FB = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private fun extractAwemeId(url: String): String? =
        Regex("""/(?:video|photo)/(\d+)""").find(url)?.groupValues?.get(1)

    private fun feedHeaders(cookie: String): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to Http.UA,
            "Referer" to "https://www.tiktok.com/",
            "x-ladon" to "Hello From Evil0ctal!",
        )
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    private fun feedUrl(id: String): String = FEED +
        "?iid=7318518857994389254&device_id=7318517321748022790&channel=googleplay" +
        "&app_name=musical_ly&version_code=300904&device_platform=android" +
        "&device_type=SM-ASUS_Z01QD&os_version=9&aweme_id=$id"

    /** 递归找 url_list / UrlList / urlList 里的第一个直链 */
    private fun findUrlList(node: JsonObject, depth: Int = 0): List<String> {
        if (depth > 8) return emptyList()
        for (k in listOf("url_list", "UrlList", "urlList")) {
            val a = node.arr(k)
            if (a != null) return a.strs()
        }
        val out = mutableListOf<String>()
        for (v in node.values) {
            when (v) {
                is JsonObject -> out += findUrlList(v, depth + 1)
                is kotlinx.serialization.json.JsonArray -> {
                    for (e in v) if (e is JsonObject) out += findUrlList(e, depth + 1)
                }
                else -> {}
            }
        }
        return out
    }

    private fun pickBestUrl(candidates: List<String>): String {
        val fixed = candidates.map { toHttps(if (it.startsWith("//")) "https:$it" else it) }
        return fixed.firstOrNull { "aweme" in it } ?: fixed.firstOrNull { it.startsWith("http") }.orEmpty()
    }

    private fun coverOf(node: JsonObject): String {
        for (k in listOf("origin_cover", "cover", "dynamic_cover", "originCover", "dynamicCover")) {
            val u = pickBestUrl(findUrlList(node.obj(k) ?: continue))
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val cookie = opts.cookie("tiktok")
        if ("vt.tiktok" in url || "vm.tiktok" in url) {
            url = Http.followChain(url, mapOf("User-Agent" to Http.UA, "Cookie" to cookie))
        }
        val id = extractAwemeId(url) ?: throw RuntimeException("无法从链接提取视频 ID")

        var item: JsonObject? = null
        repeat(3) { attempt ->
            if (item != null) return@repeat
            item = runCatching {
                val j = Http.getJson(feedUrl(id), feedHeaders(cookie)) as? JsonObject
                j?.arr("aweme_list")?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it.str("aweme_id") == id }
            }.getOrNull()
            if (item == null) delay(300L * (attempt + 1))
        }
        item = item ?: webFallback(url, id)

        val a = item!!
        val title = a.str("desc").ifEmpty { "TikTok $id" }.take(120)
        val author = a.obj("author")?.str("unique_id")
            ?.ifEmpty { a.obj("author")?.str("nickname").orEmpty() }.orEmpty()
        val authorId = a.obj("author")?.str("id").orEmpty()
        val createTime = a.lng("create_time", 0L)
        val date = if (createTime > 0) fmtDate(createTime) else today()

        var cover = coverOf(a)
        val tiers = mutableListOf<Tier>()
        val images = mutableListOf<ImageItem>()

        val imagePost = a.obj("image_post_info") ?: a.obj("imagePost")
        if (imagePost?.arr("images") != null) {
            val imgs = imagePost.arr("images")!!.mapNotNull { it as? JsonObject }
            for ((i, im) in imgs.withIndex()) {
                val u = pickBestUrl(findUrlList(im))
                if (u.isNotEmpty()) {
                    images += ImageItem(i, u, "jpg", null, im.int("width", 0), im.int("height", 0))
                    if (cover.isEmpty()) cover = u
                }
            }
        } else {
            val video = a.obj("video") ?: a.obj("videoInfo")
            var best = ""
            var bw = 0
            var bh = 0
            var dataSize = 0L
            var bitRate = 0L
            video?.arr("bit_rate")?.mapNotNull { it as? JsonObject }?.forEach { br ->
                val play = br.obj("play_addr") ?: br
                val u = pickBestUrl(findUrlList(play))
                if (u.isEmpty()) return@forEach
                val w = br.int("width", 0)
                val h = br.int("height", 0)
                val b = br.lng("bit_rate", 0L)
                val sz = br.lng("data_size", 0L)
                val score = w.toLong() * h
                val bestScore = bw.toLong() * bh
                if (score > bestScore || (score == bestScore && b > bitRate) ||
                    (score == bestScore && b == bitRate && sz > dataSize)
                ) {
                    best = u; bw = w; bh = h; bitRate = b; dataSize = sz
                }
            }
            if (best.isEmpty() && video != null) {
                for (k in listOf("play_addr", "playAddr", "download_addr", "downloadAddr")) {
                    val u = pickBestUrl(findUrlList(video.obj(k) ?: continue))
                    if (u.isNotEmpty()) { best = u; break }
                }
            }
            if (best.isNotEmpty()) {
                tiers += Tier(
                    idx = 0, gear = "原画", w = bw, h = bh, bps = bitRate, size = dataSize,
                    h265 = false, fps = 0.0, fpsEst = false, isOrigin = true,
                    urls = listOf(ensureMime(best)),
                )
            }
        }

        return ParseResult(
            platform = Platform.TIKTOK,
            id = id,
            title = title,
            author = author.ifEmpty { "未知作者" },
            date = date,
            durSec = (a.obj("video")?.lng("duration", 0L) ?: 0L).toDouble(),
            statLine = "",
            width = 0, height = 0,
            cover = cover.ifEmpty { null },
            referer = Platform.TIKTOK.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
            authorId = authorId,
        )
    }

    private suspend fun webFallback(url: String, id: String): JsonObject {
        val h = mapOf("User-Agent" to UA_FB, "Accept-Language" to "en-US,en;q=0.9")
        val html = Http.getText(url, h)
        val m = Regex(
            """<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: throw RuntimeException("feed 与网页均无法获取内容")
        val raw = m.groupValues[1]
            .replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#34;", "\"").replace("\\u002F", "/")
        val root = runCatching { parseJson(raw) as? JsonObject }.getOrNull()
            ?: throw RuntimeException("网页内嵌 JSON 解析失败")
        val scope = root.obj("__DEFAULT_SCOPE__") ?: throwRuntime(id)
        val detail = scope.obj("webapp.video-detail") ?: throwRuntime(id)
        val info = detail.obj("itemInfo") ?: throwRuntime(id)
        return info.obj("itemStruct") ?: throw RuntimeException("网页里没有 itemStruct（可能需要 Cookie）")
    }

    private fun throwRuntime(id: String): Nothing =
        throw RuntimeException("无法解析 TikTok $id（接口可能已失效或需要 Cookie）")
}
