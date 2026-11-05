package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http

/**
 * 微信公众号文章。
 *
 * 上游用 json5 解析页面里的 `window.cgiDataNew` JS 对象再转 markdown；这里改成按字段直接抽取，
 * 避免 JS 对象字面量（无引号键、单引号字符串、`'0' * 1` 隐式转换）带来的解析脆弱性：
 * - 标题/作者/封面取 og / rich_media_title 等稳定结构；
 * - 正文图片取 `data-src`（懒加载原件地址），图文消息退回 `cdn_url`。
 */
object WeixinParser {
    private const val REFERER = "https://mp.weixin.qq.com/"
    private val tagRe = Regex("<[^>]+>")
    private val titleRe = Regex(
        "<h1[^>]*class=\"[^\"]*rich_media_title[^\"]*\"[^>]*>(.*?)</h1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ogTitleRe = Regex("<meta[^>]*property=\"og:title\"[^>]*content=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val ogImageRe = Regex("<meta[^>]*property=\"og:image\"[^>]*content=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val ogAuthorRe = Regex("<meta[^>]*property=\"og:article:author\"[^>]*content=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val nicknameRe = Regex("var nickname = \"([^\"]*)\"")
    private val dataSrcRe = Regex("data-src=\"([^\"]+)\"")
    private val cdnUrlRe = Regex("\"cdn_url\"\\s*:\\s*\"([^\"]+)\"")
    private val contentStartRe = Regex("id=\"js_content\"")

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val url = extractUrl(text) ?: text
        val headers = mutableMapOf(
            "User-Agent" to Http.UA_MOBILE,
            "Referer" to REFERER,
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        opts.cookie("weixin").takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }

        val html = try {
            Http.getText(url, headers)
        } catch (e: Exception) {
            throw RuntimeException("获取公众号文章失败：" + (e.message?.take(60) ?: "未知错误"))
        }

        val title = listOf(
            titleRe.find(html)?.groupValues?.get(1),
            ogTitleRe.find(html)?.groupValues?.get(1),
        ).mapNotNull { it }.map { strip(it) }.firstOrNull { it.isNotEmpty() }

        val author = listOf(
            ogAuthorRe.find(html)?.groupValues?.get(1),
            nicknameRe.find(html)?.groupValues?.get(1),
        ).mapNotNull { it }.map { strip(it) }.firstOrNull { it.isNotEmpty() }

        val body = sliceContent(html)
        val images = (dataSrcRe.findAll(if (body.isNotEmpty()) body else html)
            .map { it.groupValues[1] }
            .toList() + cdnUrlRe.findAll(html).map { it.groupValues[1] }.toList())
            .map { unescapeHtml(it).trim() }
            .filter { it.startsWith("http") || it.startsWith("//") }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()

        if (title == null && images.isEmpty()) {
            throw RuntimeException("获取公众号文章失败：页面结构里没有找到正文，链接可能已失效或需要验证")
        }

        val plain = if (body.isNotEmpty()) strip(tagRe.replace(body, " ")) else ""
        val items = images.mapIndexed { i, u ->
            ImageItem(index = i, url = u, ext = "jpg", live = null, w = 0, h = 0)
        }

        return ParseResult(
            platform = Platform.WEIXIN,
            id = extractId(url),
            title = title ?: "公众号文章",
            author = author ?: "微信公众号",
            date = "",
            durSec = 0.0,
            statLine = plain.take(140),
            width = 0,
            height = 0,
            cover = (ogImageRe.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() })
                ?: items.firstOrNull()?.url,
            referer = REFERER,
            tiers = emptyList(),
            aux = emptyList(),
            images = items,
        )
    }

    /** 正文区间：从 id="js_content" 走到页面里其后第一个 </div> 结束（够用且不会牵进评论区） */
    private fun sliceContent(html: String): String {
        val m = contentStartRe.find(html) ?: return ""
        val from = m.range.first
        val to = html.indexOf("</div>", from).let { if (it > 0) it else html.length }
        return html.substring(from.coerceAtLeast(0), to.coerceAtMost(html.length))
    }

    private fun extractId(url: String): String {
        val cleaned = url.substringBefore('#')
        val mid = Regex("/([A-Za-z0-9_-]{10,})").findAll(cleaned).map { it.groupValues[1] }.lastOrNull()
        return mid ?: "weixin"
    }

    private fun strip(s: String): String =
        unescapeHtml(tagRe.replace(s, " ")).split(' ').filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun unescapeHtml(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}
