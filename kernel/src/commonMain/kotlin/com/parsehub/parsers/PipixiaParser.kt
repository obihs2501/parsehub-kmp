package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 皮皮虾解析（移植自 short_videos ppxia.php）：
 * 跟随重定向拿 cell_id -> h5 评论接口 -> 高画质视频 / 图集。
 */
object PipixiaParser {

    private val UA = Http.UA

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val raw = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val final = Http.followChain(raw, mapOf("User-Agent" to UA))
        val cellId = Regex("/item/([^?/]+)").find(final)?.groupValues?.get(1)
            ?: throw RuntimeException("无法从链接提取 cell_id")

        val api = "https://h5.pipix.com/bds/cell/cell_h5_comment/?count=5&aid=1319&app_name=super&cell_id=$cellId"
        val root = Http.getJson(api, mapOf("User-Agent" to UA, "Accept" to "application/json")) as? JsonObject
            ?: throw RuntimeException("接口返回异常")
        val comments = root.obj("data")?.arr("cell_comments")?.objs()
            ?: throw RuntimeException("未找到所需数据")

        // 原实现固定取下标 1，且某些作品会挪位；这里先取 1，再全量兜底扫描
        val item = comments.getOrNull(1)?.obj("comment_info")?.obj("item")
            ?: comments.firstNotNullOfOrNull { it.obj("comment_info")?.obj("item") }
            ?: throw RuntimeException("解析失败，未找到作品数据")

        val author = item.obj("author")?.str("name").orEmpty()
        val title = item.str("content")
        val cover = item.obj("cover")?.arr("url_list")?.objs()?.firstOrNull()?.str("url")
            ?: item.str("cover_url").ifEmpty { null }

        val videoUrl = item.obj("video")?.let { v ->
            (v.obj("video_high") ?: v).arr("url_list")?.objs()?.firstOrNull()?.str("url")
        }?.takeIf { it.isNotEmpty() }

        val images = mutableListOf<ImageItem>()
        val multi = item.obj("note")?.arr("multi_image")
        if (multi != null) {
            for ((i, img) in multi.withIndex()) {
                val u = (img as? JsonObject)?.arr("url_list")?.objs()?.firstOrNull()?.str("url") ?: continue
                images += ImageItem(i, toHttps(u), "jpg", null, 0, 0)
            }
        }

        val tiers = mutableListOf<Tier>()
        if (videoUrl != null) {
            tiers += Tier(0, "默认", 0, 0, 0, 0, false, 0.0, false, true, listOf(ensureMime(toHttps(videoUrl))))
        }
        if (tiers.isEmpty() && images.isEmpty()) throw RuntimeException("未提取到视频或图片")

        return ParseResult(
            platform = Platform.PIPIXIA,
            id = cellId,
            title = title.ifEmpty { "无标题" }.take(120),
            author = author.ifEmpty { "未知作者" },
            date = today(),
            durSec = 0.0,
            statLine = "",
            width = 0, height = 0,
            cover = toHttps(cover).ifEmpty { images.firstOrNull()?.url ?: "" }.ifEmpty { null },
            referer = Platform.PIPIXIA.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
        )
    }
}
