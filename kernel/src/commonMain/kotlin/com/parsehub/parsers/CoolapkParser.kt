package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http

/**
 * 酷安：网页版是服务端渲染的 HTML，直接从页面结构里取标题、文案与图片。
 *
 * 注意两点（对齐上游 provider_api/coolapk.py）：
 * - 分享时要保留 `shareKey` 或 `s` 参数，否则拿不到内容（清理跟踪参数时不能动这两个键）；
 * - 网页版不加载实况照片，动图按图片处理。
 */
object CoolapkParser {
    private const val REFERER = "https://www.coolapk.com/"
    private val tagRe = Regex("<[^>]+>")
    private val scriptRe = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val styleRe = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val titleRe = Regex(
        "<[^>]*class=\"[^\"]*message-title[^\"]*\"[^>]*>(.*?)</",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val imageSrcRe = Regex(
        "<img[^>]*class=\"[^\"]*message-image[^\"]*\"[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val srcRe = Regex("src=\"([^\"]+)\"")

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val url = extractUrl(text) ?: text
        val headers = mutableMapOf(
            "User-Agent" to Http.UA,
            "Referer" to REFERER,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        opts.cookie("coolapk").takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }

        val html = try {
            Http.getText(url, headers)
        } catch (e: Exception) {
            throw RuntimeException("获取酷安内容失败：" + (e.message?.take(60) ?: "未知错误"))
        }

        val title = titleRe.find(html)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty()
        val images = imageSrcRe.findAll(html)
            .mapNotNull { tag -> srcRe.find(tag.value)?.groupValues?.get(1) }
            .map { normalizeImageUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        val body = stripTags(
            scriptRe.replace(styleRe.replace(html, " "), " "),
        )
        val hasContent = body.isNotEmpty()

        if (title.isEmpty() && images.isEmpty()) {
            throw RuntimeException("获取酷安内容失败，分享时请保留 shareKey 或 s 参数")
        }

        val items = images.mapIndexed { i, u ->
            ImageItem(index = i, url = u, ext = "jpg", live = null, w = 0, h = 0)
        }
        val id = extractId(url)

        return ParseResult(
            platform = Platform.COOLAPK,
            id = id,
            title = title.ifBlank { "酷安动态 $id" },
            author = "酷安用户",
            date = "",
            durSec = 0.0,
            statLine = if (hasContent) body.take(140) else "",
            width = 0,
            height = 0,
            cover = items.firstOrNull()?.url,
            referer = REFERER,
            tiers = emptyList(),
            aux = emptyList(),
            images = items,
        )
    }

    private fun extractId(url: String): String {
        val cleaned = url.substringBefore('?').trimEnd('/')
        return cleaned.substringAfterLast('/').ifEmpty { "coolapk" }
    }

    private fun normalizeImageUrl(src: String): String {
        val s = unescapeHtml(src).trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> REFERER.trimEnd('/') + s
            else -> s
        }
    }

    private fun stripTags(html: String): String =
        unescapeHtml(tagRe.replace(html, " "))
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

    private fun unescapeHtml(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}
