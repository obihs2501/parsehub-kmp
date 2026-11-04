package io.github.obihs2501.parsehub.parsers

import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.net.Http
import kotlinx.serialization.json.JsonObject

/**
 * 今日头条解析（移植自 short_videos toutiao.php）：
 * 页面 HTML 里的 <script id="RENDER_DATA"> JSON -> video_list 多档直链。
 */
object ToutiaoParser {
    private val UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/95.0.4638.69 Safari/537.36"

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val raw = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val final = Http.followChain(raw, mapOf("User-Agent" to UA))
        val id = Regex("video/(\\d{15,25})").find(final)?.groupValues?.get(1)
            ?: Regex("(\\d{15,25})").find(final)?.groupValues?.get(1)
            ?: throw RuntimeException("无法解析视频 ID")

        val headers = mutableMapOf("User-Agent" to UA)
        if (opts.cookieToutiao.isNotEmpty()) headers["Cookie"] = opts.cookieToutiao
        val page = Http.getText("https://www.toutiao.com/video/$id", headers)

        val startTag = "<script id=\"RENDER_DATA\" type=\"application/json\">"
        val s = page.indexOf(startTag)
        if (s < 0) throw RuntimeException("页面未找到 RENDER_DATA（可能需要 Cookie）")
        val e = page.indexOf("</script>", s)
        if (e < 0) throw RuntimeException("RENDER_DATA 未闭合")
        val jsonStr = urlDecode(page.substring(s + startTag.length, e))
        val root = parseJson(jsonStr) as? JsonObject ?: throw RuntimeException("RENDER_DATA 解析失败")
        val data = root.obj("data") ?: throw RuntimeException("当前分享链接已失效")
        val iv = data.obj("initialVideo") ?: throw RuntimeException("未找到视频数据")

        val itemCell = iv.obj("itemCell")
        val userInfo = itemCell?.obj("userInfo")
        val title = iv.str("title").ifEmpty { userInfo?.str("description").orEmpty() }
        val videoList = iv.obj("videoPlayInfo")?.arr("video_list")?.objs() ?: emptyList()

        // 直链优先级与原实现一致：第 3 档 -> 第 2 档 -> 第 1 档
        fun mainUrlOf(o: JsonObject): String = o.str("main_url").takeIf { it.startsWith("http") }
            ?: o.str("backup_url").takeIf { it.startsWith("http") } ?: ""

        val primary = (videoList.getOrNull(2)?.let(::mainUrlOf)).orEmpty()
            .ifEmpty { videoList.getOrNull(1)?.let(::mainUrlOf).orEmpty() }
            .ifEmpty { videoList.firstOrNull()?.let(::mainUrlOf).orEmpty() }
        if (primary.isEmpty() && videoList.isEmpty()) throw RuntimeException("未提取到视频直链")

        val tiers = mutableListOf<Tier>()
        tiers += Tier(0, "默认", 0, 0, 0, 0, false, 0.0, false, true, listOf(ensureMime(primary)))
        videoList.forEachIndexed { i, o ->
            val u = mainUrlOf(o)
            if (u.isNotEmpty() && u != primary) {
                val gear = o.str("definition").ifEmpty { "线路${i + 1}" }
                tiers += Tier(tiers.size, gear, 0, 0, 0, 0, false, 0.0, false, false, listOf(ensureMime(u)))
            }
        }

        val aux = mutableListOf<AuxItem>()
        val music = itemCell?.obj("videoAbility")?.any("music")
        if (music is JsonObject) {
            val mu = music.str("url").ifEmpty { music.str("play_url") }.ifEmpty { "" }
            if (mu.startsWith("http")) {
                aux += AuxItem("audio", "配乐", music.str("title").ifEmpty { "背景音乐" }, "mp3", listOf(mu))
            }
        }

        return ParseResult(
            platform = Platform.TOUTIAO,
            id = id,
            title = title.ifEmpty { "无标题" }.take(120),
            author = userInfo?.str("name").orEmpty().ifEmpty { "未知作者" },
            date = today(),
            durSec = 0.0,
            statLine = "",
            width = iv.obj("videoPlayInfo")?.int("width") ?: 0,
            height = iv.obj("videoPlayInfo")?.int("height") ?: 0,
            cover = toHttps(iv.str("coverUrl").let { if (it.startsWith("//")) "https:$it" else it })
                .ifEmpty { null },
            referer = Platform.TOUTIAO.referer,
            tiers = tiers,
            aux = aux,
            images = emptyList(),
        )
    }
}
