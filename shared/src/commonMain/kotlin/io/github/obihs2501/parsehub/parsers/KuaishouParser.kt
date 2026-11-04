package io.github.obihs2501.parsehub.parsers

import io.github.obihs2501.parsehub.core.*
import io.github.obihs2501.parsehub.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 快手解析（移植自 short_videos KuaishouSpider.php）：
 * 短链跟随重定向 -> 页面 window.INIT_STATE -> 视频 / 图集 / 单图（免 Cookie）。
 */
object KuaishouParser {

    private val UA = Http.UA

    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val raw = extractUrl(text) ?: throw RuntimeException("找不到链接")
        val finalUrl = Http.followChain(raw, mapOf("User-Agent" to UA))
        val page = Http.getText(
            finalUrl,
            mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "zh-CN,zh;q=0.9",
            ),
        )
        val (type, id) = extractTypeId(finalUrl)
        val data = extractFromInitState(page)
            ?: throw RuntimeException("未找到有效媒体信息（长视频/新格式暂不支持，已用 $type/$id）")
        return data
    }

    private fun extractTypeId(url: String): Pair<String, String> {
        for ((type, pat) in listOf(
            "short-video" to "short-video/([^?]+)",
            "long-video" to "long-video/([^?]+)",
            "photo" to "photo/([^?]+)",
        )) {
            Regex(pat).find(url)?.let { return type to it.groupValues[1] }
        }
        return "" to ""
    }

    private suspend fun extractFromInitState(page: String): ParseResult? {
        val m = Regex("window\\.INIT_STATE\\s*=\\s*(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page)
            ?: return null
        val jsonStr = m.groupValues[1].trim().trimEnd(';')
        val root = try {
            parseJson(jsonStr) as? JsonObject
        } catch (_: Exception) {
            // 容错：去掉已知坏片段再试一次
            val cleaned = jsonStr
                .replace("{\"err_msg\":\"launchApplication:fail\"}", "\"err_msg\",\"launchApplication:fail\"")
                .replace("{\"err_msg\":\"system:access_denied\"}", "\"err_msg\",\"system:access_denied\"")
            try {
                parseJson(cleaned) as? JsonObject
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val item = root.entries
            .firstOrNull { (k, v) -> k.startsWith("tusjoh") && v is JsonObject && ((v as JsonObject).any("fid") != null || v.obj("photo") != null) }
            ?.value as? JsonObject ?: return null
        val photo = item.obj("photo") ?: return null

        val title = photo.str("caption")
        val author = photo.str("userName")
        val date = fmtDate(photo.lng("timestamp")).ifEmpty { today() }
        val music = photo.obj("music") ?: photo.obj("soundTrack")
        val aux = mutableListOf<AuxItem>()
        if (music != null) {
            val murl = music.arr("audioUrls")?.objs()?.firstOrNull()?.str("url").orEmpty()
            if (murl.isNotEmpty()) {
                aux += AuxItem("audio", "配乐", music.str("name"), "m4a", listOf(toHttps(murl)))
            }
        }

        // 1) 图集
        val atlas = photo.obj("ext_params")?.obj("atlas")?.arr("list")
        if (atlas != null && atlas.size > 0) {
            val images = atlas.strs().mapIndexed { i, p ->
                ImageItem(i, toHttps("http://tx2.a.yximgs.com/" + p), "jpg", null, 0, 0)
            }
            val am = photo.obj("ext_params")?.obj("atlas")?.str("music")
            if (!am.isNullOrEmpty()) {
                aux += AuxItem("audio", "配乐", title, "m4a",
                    listOf(toHttps("http://txmov2.a.kwimgs.com" + am)))
            }
            return ParseResult(
                Platform.KUAISHOU, "", title.take(120), author, date, 0.0, "",
                0, 0, images.firstOrNull()?.url, Platform.KUAISHOU.referer,
                emptyList(), aux, images,
            )
        }

        // 2) 单图
        if (photo.str("photoType") == "SINGLE_PICTURE" || photo.bool("singlePicture")) {
            val url = photo.arr("coverUrls")?.objs()?.firstOrNull()?.str("url").orEmpty()
            if (url.isNotEmpty()) {
                val u = toHttps(url)
                return ParseResult(
                    Platform.KUAISHOU, "", title.take(120), author, date, 0.0, "",
                    0, 0, u, Platform.KUAISHOU.referer,
                    listOf(Tier(0, "原图", 0, 0, 0, 0, false, 0.0, false, false, listOf(u))),
                    aux, listOf(ImageItem(0, u, "jpg", null, 0, 0)),
                )
            }
        }

        // 3) 视频
        var videoUrl = photo.arr("mainMvUrls")?.objs()?.firstOrNull()?.str("url").orEmpty()
        if (videoUrl.isEmpty()) {
            videoUrl = photo.obj("manifest")?.arr("adaptationSet")?.objs()?.firstOrNull()
                ?.arr("representation")?.objs()?.firstOrNull()?.str("url").orEmpty()
        }
        if (videoUrl.isNotEmpty()) {
            val u = ensureMime(toHttps(videoUrl))
            val cover = photo.arr("coverUrls")?.objs()?.firstOrNull()?.str("url")
            return ParseResult(
                Platform.KUAISHOU, "", title.take(120), author, date, 0.0, "",
                0, 0, toHttps(cover), Platform.KUAISHOU.referer,
                listOf(Tier(0, "默认", 0, 0, 0, 0, false, 0.0, false, false, listOf(u))),
                aux, emptyList(),
            )
        }
        return null
    }
}
