package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * 最右解析（移植自 short_videos zuiyou.php）：
 * 分享页 pid -> planck 详情接口 -> 视频直链 / 图集兜底。
 */
object ZuiyouParser {
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    private fun queryParam(url: String, key: String): String? =
        Regex("[?&]$key=([^&\\s]+)").find(url)?.groupValues?.get(1)

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        var url = extractUrl(text) ?: throw RuntimeException("找不到链接")
        var pid = queryParam(url, "pid")
        if (pid.isNullOrEmpty()) {
            url = Http.followChain(url, mapOf("User-Agent" to UA))
            pid = queryParam(url, "pid")
        }
        if (pid.isNullOrEmpty()) throw RuntimeException("找不到有效的 pid 参数")

        val body = "{\"pid\":${pid.toLongOrNull() ?: throw RuntimeException("pid 非法")},\"h_av\":\"5.2.13.011\"}"
        val resp = Http.postRaw(
            "https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
            body, "application/json",
            mapOf("User-Agent" to UA, "Content-Type" to "application/json", "Accept" to "application/json"),
        )
        val root = parseJson(resp) as? JsonObject ?: throw RuntimeException("接口响应解析失败")
        val post = root.obj("data")?.obj("post") ?: throw RuntimeException("未找到作品数据")
        val member = post.obj("member")
        val imgs = post.arr("imgs")?.objs() ?: emptyList()
        val videos = post.obj("videos")

        // vid：优先 query 里的，其次图集首项的 id
        val vid = queryParam(url, "vid")
            ?: imgs.firstOrNull()?.any("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val videoUrl = (vid?.let { videos?.obj(it)?.str("url") })
            ?: videos?.values?.firstOrNull()?.let { (it as? JsonObject)?.str("url") }

        val images = mutableListOf<ImageItem>()
        imgs.forEachIndexed { i, im ->
            val urls = im.obj("urls") ?: return@forEachIndexed
            val u = listOf("origin", "1080_webp", "1080", "720_webp", "720", "540_webp", "540")
                .firstNotNullOfOrNull { k -> urls.obj(k)?.arr("urls")?.strs()?.firstOrNull() }
                ?: return@forEachIndexed
            images += ImageItem(i, toHttps(u), if (".webp" in u) "webp" else "jpg", null, 0, 0)
        }

        val tiers = mutableListOf<Tier>()
        if (!videoUrl.isNullOrEmpty()) {
            tiers += Tier(0, "默认", 0, 0, 0, 0, false, 0.0, false, true, listOf(ensureMime(toHttps(videoUrl))))
        }
        if (tiers.isEmpty() && images.isEmpty()) throw RuntimeException("未提取到视频或图片")

        val avatar = member?.obj("avatar_urls")?.obj("origin")?.arr("urls")?.strs()?.firstOrNull()
        return ParseResult(
            platform = Platform.ZUIYOU,
            id = pid,
            title = post.str("content").ifEmpty { "无标题" }.take(120),
            author = member?.str("name").orEmpty().ifEmpty { "未知作者" },
            date = today(),
            durSec = 0.0,
            statLine = "",
            width = 0, height = 0,
            cover = images.firstOrNull()?.url,
            referer = Platform.ZUIYOU.referer,
            tiers = tiers,
            aux = emptyList(),
            images = images,
        )
    }
}
