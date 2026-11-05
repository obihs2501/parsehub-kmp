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
import com.parsehub.core.str
import com.parsehub.core.today
import com.parsehub.core.toHttps
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * Threads 单帖解析（移植自 ParseHub-Studio 的 provider_api/threads.py 的纯 HTTP 路径）。
 *
 * 公开帖子匿名可解析；登录墙内容需要用户在设置里按 threads 平台填 Cookie（sessionid 等）。
 * doc_id 与 Barcelona feature flags 是 Meta 前端常量，可能随前端改版失效。
 */
object ThreadsParser {
    private const val ENDPOINT = "https://www.threads.com/graphql/query"
    private const val HOME = "https://www.threads.com/"
    private const val DOC_ID = "27419285281047858"
    private const val IG_APP_ID = "238260118697367"

    private val BARCELONA_FLAGS = listOf(
        "HasPermalinkIndentation", "IsLoggedIn", "HasPostAuthorNotifControls",
        "ShouldShowFediverseM1Features", "HasPermalinkPodcastCard", "HasDearAlgoConsumption",
        "HasEventBadge", "GenAIRepliesEnabled", "IsSearchDiscoveryEnabled", "HasCommunities",
        "HasGameScoreShare", "HasPublicViewCountCard", "HasCommunityEntityCard",
        "HasScorecardCommunity", "HasSportTeamAllegianceCard", "HasMusic", "HasNewspaperLinkStyle",
        "HasMessaging", "HasPodcastTextFragments", "ShouldFulfillLightboxQuery", "HasViewerReplied",
        "HasPrivateRepliesDeprecation", "HasGhostPostEmojiActivation", "OptionalCookiesEnabled",
        "HasDearAlgoWebProduction", "HasWebFavicons", "IsCrawler", "HasCommunityTopContributors",
        "CanSeeSponsoredContent", "ShouldShowFediverseM075Features", "IsInternalUser",
    ).map { "__relay_internal__pv__Barcelona${it}relayprovider" }

    private fun extractCode(url: String): String? =
        Regex("""/post/([\w-]+)""").find(url)?.groupValues?.get(1)

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val cookie = opts.cookie("threads")
        if ("/share/" in url) {
            url = Http.followChain(url, mapOf("User-Agent" to Http.UA, "Cookie" to cookie))
        }
        val code = extractCode(url) ?: throw RuntimeException("无法从链接提取帖子 code")
        val pk = shortcodeToPk(code)

        val variables = metaVariables(mapOf("postID" to pk), BARCELONA_FLAGS)
        val root = MetaApi.graphql(
            endpoint = ENDPOINT,
            home = HOME,
            docId = DOC_ID,
            variables = variables,
            referer = HOME,
            igAppId = IG_APP_ID,
            userCookie = cookie,
            anonymous = MetaApi.ANON_THREADS,
        )

        val posts = mutableListOf<JsonObject>()
        root.obj("data")?.obj("data")?.arr("edges")?.forEach { edge ->
            val node = (edge as? JsonObject)?.obj("node") ?: return@forEach
            node.arr("thread_items")?.forEach { item ->
                val post = (item as? JsonObject)?.obj("post") ?: return@forEach
                posts += post
            }
        }
        val post = posts.firstOrNull { it.str("code") == code } ?: posts.firstOrNull()
            ?: throw RuntimeException("帖子不存在或需要登录 Cookie")

        val caption = post.obj("caption")?.str("text")
            ?: post.str("caption").ifEmpty { "Threads $code" }
        val title = caption.trim().ifEmpty { "Threads $code" }.take(120)
        val (author, authorId) = MetaApi.userOf(post)
        val date = fmtDate(post.lng("taken_at", 0L)).ifEmpty { today() }
        val mediaType = post.int("media_type", 1)

        var cover: String? = null
        val tiers = mutableListOf<Tier>()
        val images = mutableListOf<ImageItem>()

        fun addMedia(node: JsonObject, idx: Int) {
            val videoUrl = MetaApi.firstUrl(node, "video_versions")
            val (img, dims) = MetaApi.firstCandidate(node)
            if (videoUrl.isNotEmpty()) {
                tiers += Tier(
                    idx = tiers.size, gear = "片段${idx + 1}", w = dims.first, h = dims.second,
                    bps = 0, size = 0, h265 = false, fps = 0.0, fpsEst = false,
                    isOrigin = false, urls = listOf(ensureMime(toHttps(videoUrl))),
                )
                if (img.isNotEmpty() && cover == null) cover = toHttps(img)
            } else if (img.isNotEmpty()) {
                images += ImageItem(idx, toHttps(img), "jpg", null, dims.first, dims.second)
                if (cover == null) cover = toHttps(img)
            }
        }

        when (mediaType) {
            8 -> {
                val nodes = post.arr("carousel_media")?.mapNotNull { it as? JsonObject } ?: emptyList()
                nodes.forEachIndexed { i, node -> addMedia(node, i) }
            }
            19 -> {
                val linked = post.obj("text_post_app_info")?.arr("linked_inline_media")
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
                linked.forEachIndexed { i, node -> addMedia(node, i) }
            }
            else -> addMedia(post, 0)
        }

        return ParseResult(
            platform = Platform.THREADS,
            id = code,
            title = title,
            author = author.ifEmpty { "未知作者" },
            date = date,
            durSec = 0.0,
            statLine = "",
            width = 0, height = 0,
            cover = cover,
            referer = Platform.THREADS.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
            authorId = authorId,
        )
    }
}
