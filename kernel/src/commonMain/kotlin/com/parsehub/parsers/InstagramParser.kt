package com.parsehub.parsers

import com.parsehub.core.ImageItem
import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.core.Tier
import com.parsehub.core.extractUrl
import com.parsehub.core.obj
import com.parsehub.core.arr
import com.parsehub.core.str
import com.parsehub.core.dbl
import com.parsehub.core.lng
import com.parsehub.core.toHttps
import com.parsehub.core.ensureMime
import com.parsehub.core.today
import com.parsehub.core.fmtDate
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * Instagram 单帖解析（移植自 ParseHub-Studio 的 provider_api/instagram.py 的纯 HTTP 路径）。
 *
 * 公开帖子匿名可解析；年龄限制 / 仅登录可见的内容需要用户在设置里按 instagram 平台填 Cookie。
 * GraphQL doc_id 是 Meta 前端常量，随前端改版可能失效，届时接口会给出明确报错。
 */
object InstagramParser {
    private const val ENDPOINT = "https://www.instagram.com/graphql/query"
    private const val HOME = "https://www.instagram.com/"
    private const val DOC_ID = "27128499623469141"

    private fun extractShortcode(url: String): String? {
        val re = Regex("""/(?:p|reel|reels|share|[^/]+/p|[^/]+/reel)/([^/?#]+)""")
        return re.find(url)?.groupValues?.get(1)
    }

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val cookie = opts.cookie("instagram")
        if ("/share/" in url || "instagram.com/share" in url) {
            url = Http.followChain(url, mapOf("User-Agent" to Http.UA, "Cookie" to cookie))
        }
        val code = extractShortcode(url) ?: throw RuntimeException("无法从链接提取帖子 shortcode")

        val variables = metaVariables(
            mapOf("shortcode" to code),
            listOf("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider"),
        )
        val root = MetaApi.graphql(
            endpoint = ENDPOINT,
            home = HOME,
            docId = DOC_ID,
            variables = variables,
            referer = HOME,
            userCookie = cookie,
            anonymous = MetaApi.ANON_IG,
        )
        val data = root.obj("data") ?: throw RuntimeException("接口无 data（可能需要登录 Cookie）")
        val media = data.obj("xdt_shortcode_media")
            ?: data.obj("xdt_api__v1__media__shortcode__web_info")?.arr("items")?.firstOrNull() as? JsonObject
            ?: throw RuntimeException("帖子不存在、已删除或需要登录 Cookie")

        val title = MetaApi.captionOf(media).ifEmpty { "Instagram $code" }.take(120)
        val (author, authorId) = MetaApi.userOf(media)
        val date = fmtDate(media.lng("taken_at", 0L)).ifEmpty { today() }
        val typeName = media.str("__typename").ifEmpty { media.str("media_type") }
        val mediaType = when {
            "XDTGraphSidecar" in typeName || typeName == "8" -> 8
            "XDTGraphVideo" in typeName || typeName == "2" -> 2
            else -> 1
        }

        var cover: String? = null
        val tiers = mutableListOf<Tier>()
        val images = mutableListOf<ImageItem>()

        when (mediaType) {
            2 -> {
                val videoUrl = MetaApi.firstUrl(media, "video_versions")
                val (thumb, dims) = MetaApi.firstCandidate(media)
                if (thumb.isNotEmpty()) cover = toHttps(thumb)
                if (videoUrl.isNotEmpty()) {
                    tiers += Tier(
                        idx = 0, gear = "原画", w = dims.first, h = dims.second,
                        bps = 0, size = 0, h265 = false, fps = 0.0, fpsEst = false,
                        isOrigin = true, urls = listOf(ensureMime(toHttps(videoUrl))),
                    )
                }
            }
            8 -> {
                val children = media.arr("carousel_media")
                    ?: media.obj("edge_sidecar_to_children")?.arr("edges")
                val nodes = children?.mapNotNull {
                    (it as? JsonObject)?.let { o -> o.obj("node") ?: o }
                } ?: emptyList()
                for ((i, node) in nodes.withIndex()) {
                    val v = MetaApi.firstUrl(node, "video_versions")
                    val (img, dims) = MetaApi.firstCandidate(node)
                    if (v.isNotEmpty()) {
                        tiers += Tier(
                            idx = tiers.size, gear = "片段${i + 1}", w = dims.first, h = dims.second,
                            bps = 0, size = 0, h265 = false, fps = 0.0, fpsEst = false,
                            isOrigin = false, urls = listOf(ensureMime(toHttps(v))),
                        )
                        if (img.isNotEmpty() && cover == null) cover = toHttps(img)
                    } else if (img.isNotEmpty()) {
                        images += ImageItem(i, toHttps(img), "jpg", null, dims.first, dims.second)
                        if (cover == null) cover = toHttps(img)
                    }
                }
            }
            else -> {
                val (img, dims) = MetaApi.firstCandidate(media)
                if (img.isNotEmpty()) {
                    images += ImageItem(0, toHttps(img), "jpg", null, dims.first, dims.second)
                    cover = toHttps(img)
                }
            }
        }

        val dur = media.dbl("video_duration", 0.0)
        val stat = listOfNotNull(
            media.lng("view_count", 0L).takeIf { it > 0 }?.let { "播 $it" },
            media.lng("play_count", 0L).takeIf { it > 0 }?.let { "播 $it" },
        ).joinToString(" / ")

        return ParseResult(
            platform = Platform.INSTAGRAM,
            id = code,
            title = title,
            author = author.ifEmpty { "未知作者" },
            date = date,
            durSec = dur,
            statLine = stat,
            width = 0, height = 0,
            cover = cover,
            referer = Platform.INSTAGRAM.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
            authorId = authorId,
        )
    }
}
