package io.github.obihs2501.parsehub.parsers

import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * 哔哩哔哩解析（移植自 short_videos bilibili.php）。
 * view 接口拿标题/分P -> playurl(qn=112, fnval=3, platform=html5) 拿 durl 直链。
 * 无 cookie 一般 480P/720P；在设置里填 `SESSDATA=...` 可到 1080P+。
 */
object BilibiliParser {
    private val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_6) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36"

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val raw = extractUrl(text) ?: throw RuntimeException("找不到链接")
        var bvid = Regex("/video/(BV[a-zA-Z0-9]{10})").find(raw)?.groupValues?.get(1).orEmpty()
        if (bvid.isEmpty()) {
            val final = Http.followChain(raw, mapOf("User-Agent" to UA))
            bvid = Regex("BV[a-zA-Z0-9]{10}").find(final)?.value.orEmpty()
            if (bvid.isEmpty()) {
                val page = try {
                    Http.getText(final, mapOf("User-Agent" to UA))
                } catch (_: Exception) {
                    ""
                }
                bvid = Regex("(BV[a-zA-Z0-9]{10})").find(page)?.groupValues?.get(1).orEmpty()
            }
        }
        if (bvid.isEmpty()) throw RuntimeException("解析失败：无法提取 BV 号")

        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "https://www.bilibili.com/",
            "Accept" to "application/json, text/plain, */*",
        )
        if (opts.cookieBili.isNotEmpty()) headers["Cookie"] = opts.cookieBili

        val view = Http.getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers) as? JsonObject
            ?: throw RuntimeException("view 接口返回异常")
        if (view.str("code") != "0") throw RuntimeException("解析失败：${view.str("message", "接口错误")}")
        val data = view.obj("data") ?: throw RuntimeException("无视频数据")
        val title = data.str("title")
        val cover = toHttps(data.str("pic").let { if (it.startsWith("//")) "https:$it" else it })
        val owner = data.obj("owner")?.str("name").orEmpty()
        val pages = data.arr("pages")?.objs() ?: emptyList()

        val tiers = mutableListOf<Tier>()
        for ((i, page) in pages.withIndex()) {
            val cid = page.lng("cid")
            val part = page.str("part")
            val u = "https://api.bilibili.com/x/player/playurl?otype=json&fnver=0&fnval=3" +
                "&player=3&qn=112&bvid=$bvid&cid=$cid&platform=html5&high_quality=1"
            val play = try {
                Http.getJson(u, headers) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val rawUrl = play?.obj("data")?.arr("durl")?.objs()?.firstOrNull()?.str("url").orEmpty()
            if (rawUrl.isEmpty()) continue
            val mirror = Regex("^https?://[^/]+/").replace(rawUrl, "https://upos-sz-mirrorhw.bilivideo.com/")
            val urls = listOf(ensureMime(rawUrl), ensureMime(mirror)).distinct()
            val gear = if (pages.size > 1) "P${i + 1} $part".take(28) else "默认"
            tiers += Tier(tiers.size, gear, 0, 0, 0, 0, false, 0.0, false, false, urls)
        }
        if (tiers.isEmpty()) throw RuntimeException("未获取到视频直链（该视频可能需要登录 Cookie）")

        val dur = data.lng("duration")
        return ParseResult(
            platform = Platform.BILIBILI,
            id = bvid,
            title = title.ifEmpty { "无标题" }.take(120),
            author = owner.ifEmpty { "未知UP主" },
            date = today(),
            durSec = if (dur > 1000) dur / 1000.0 else dur.toDouble(),
            statLine = "",
            width = 0, height = 0,
            cover = cover.ifEmpty { null },
            referer = Platform.BILIBILI.referer,
            tiers = tiers,
            aux = emptyList(),
            images = emptyList(),
        )
    }
}
