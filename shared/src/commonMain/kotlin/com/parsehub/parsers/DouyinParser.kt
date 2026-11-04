package com.parsehub.parsers

import com.parsehub.core.*
import com.parsehub.net.Http
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 抖音解析（移植自桌面版 dy_dl_v3 / Android dy-dl）：
 * 纯 HTTP、免 Cookie；amemv 五主机兜底、精选页播放量、fplay(AES) 原画真值、
 * ratio=default 302 原画、三代图集格式、mime_type 补齐。
 */
object DouyinParser {

    private val UA = Http.UA
    private val UA_MOBILE = Http.UA_MOBILE

    private val AMEMV_HOSTS = listOf(
        "api.amemv.com",
        "api3-core-c.amemv.com",
        "aweme.snssdk.com",
        "api5-normal-lf.amemv.com",
        "api3-normal-c.amemv.com",
    )
    private const val WEB_DETAIL = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
    private const val PLAY_API = "https://www.douyin.com/aweme/v1/play/"
    private const val PLAY_API_ALT = "https://aweme.snssdk.com/aweme/v1/play/"
    private const val V26_ALT_HOST = "v26-luna.douyinvod.com"

    private fun urlWithParams(base: String, params: Map<String, String>): String =
        URLBuilder(base).apply { params.forEach { (k, v) -> parameters.append(k, v) } }.buildString()

    // ------------------------------------------------------------------ 链接解析
    suspend fun resolveAwemeId(text: String): String {
        val t = text.trim()
        Regex("^\\d{15,25}$").find(t)?.let { return t }
        Regex("/(?:share/)?video/(\\d{15,25})").find(t)?.let { return it.groupValues[1] }
        Regex("modal_id=(\\d{15,25})").find(t)?.let { return it.groupValues[1] }
        Regex("/note/(\\d{15,25})").find(t)?.let { return it.groupValues[1] }
        val url = extractUrl(t) ?: throw RuntimeException("找不到链接或视频ID")
        // 短链：先只看 302 的 Location
        Http.location(url, mapOf("User-Agent" to UA))?.let { loc ->
            extractId(loc)?.let { return it }
        }
        // 兜底：跟随重定向后从最终地址/页面里找
        val finalUrl = Http.followChain(url, mapOf("User-Agent" to UA))
        extractId(finalUrl)?.let { return it }
        val body = try {
            Http.getText(finalUrl, mapOf("User-Agent" to UA))
        } catch (_: Exception) {
            ""
        }
        Regex("/(?:video|note)/(\\d{15,25})").find(finalUrl)?.let { return it.groupValues[1] }
        Regex("aweme_id=(\\d{15,25})").find(body)?.let { return it.groupValues[1] }
        throw RuntimeException("无法解析 aweme_id")
    }

    private fun extractId(s: String): String? =
        Regex("/(?:share/)?(?:video|note)/(\\d{15,25})").find(s)?.groupValues?.get(1)
            ?: Regex("(\\d{15,25})").find(s)?.groupValues?.get(1)

    // ------------------------------------------------------------------ detail（amemv 多主机 -> web）
    private fun filterMsg(j: JsonObject): String? {
        val fd = j.obj("filter_detail") ?: return null
        val reason = fd.str("filter_reason")
        if (reason.isEmpty()) return null
        val msg = fd.str("detail_msg").ifEmpty { fd.str("notice") }
        return "作品不可用($reason)${if (msg.isNotEmpty()) " $msg" else ""}"
    }

    private suspend fun detailAmemv(aid: String): JsonObject {
        val errs = mutableListOf<String>()
        for (host in AMEMV_HOSTS) {
            try {
                val url = urlWithParams(
                    "https://$host/aweme/v1/aweme/detail/",
                    mapOf(
                        "aid" to "6383", "device_platform" to "web",
                        "version_code" to "190500", "version_name" to "19.5.0",
                        "aweme_id" to aid,
                    ),
                )
                val j = Http.getJson(url, mapOf("User-Agent" to UA_MOBILE, "Accept" to "application/json, text/plain, */*"))
                    as? JsonObject ?: throw RuntimeException("返回不是 JSON 对象")
                if (j.str("status_code", "-1") !in listOf("0")) throw RuntimeException("status_code=${j.str("status_code")}")
                val ad = j.obj("aweme_detail") ?: throw RuntimeException(filterMsg(j) ?: "未返回 aweme_detail")
                if (ad.str("aweme_id") != aid) throw RuntimeException("返回的作品 ID 不匹配")
                return ad
            } catch (e: Exception) {
                errs += "${host.substringBefore('.')}(${e.message?.take(40)})"
            }
        }
        throw RuntimeException(errs.joinToString(" / "))
    }

    private suspend fun detailWeb(aid: String): JsonObject {
        val params = mapOf(
            "aid" to "6383", "device_platform" to "webapp", "channel" to "channel_pc_web",
            "version_code" to "190500", "version_name" to "19.5.0", "pc_client_type" to "1",
            "support_h265" to "1", "support_dash" to "1", "cookie_enabled" to "true",
            "screen_width" to "1920", "screen_height" to "1080", "browser_language" to "zh-CN",
            "browser_platform" to "Win32", "browser_name" to "Chrome", "browser_version" to "126.0.0.0",
            "engine_name" to "Blink", "engine_version" to "126.0.0.0", "os_name" to "Windows",
            "os_version" to "10", "platform" to "PC", "downlink" to "10", "effective_type" to "4g",
            "round_trip_time" to "50", "aweme_id" to aid,
        )
        val j = Http.getJson(
            urlWithParams(WEB_DETAIL, params),
            mapOf("User-Agent" to UA, "Origin" to "https://open.douyin.com", "Referer" to "https://open.douyin.com/"),
        ) as? JsonObject ?: throw RuntimeException("web 详情返回非 JSON")
        val ad = j.obj("aweme_detail") ?: throw RuntimeException(
            filterMsg(j) ?: "未返回 aweme_detail (status=${j.str("status_code")})"
        )
        if (ad.str("aweme_id") != aid) throw RuntimeException("返回的作品 ID 不匹配")
        return ad
    }

    suspend fun fetchDetail(aid: String): JsonObject {
        val errs = mutableListOf<String>()
        try {
            return detailAmemv(aid)
        } catch (e: Exception) {
            errs += "amemv(${e.message?.take(60)})"
        }
        try {
            return detailWeb(aid)
        } catch (e: Exception) {
            errs += "web(${e.message?.take(60)})"
        }
        throw RuntimeException("详情接口失败: " + errs.joinToString(" / "))
    }

    // ------------------------------------------------------------------ 精选页
    data class JxInfo(val playCount: Long = 0, val fallbackApi: String? = null)

    suspend fun fetchJingxuan(aid: String): JxInfo = try {
        val body = Http.getText("https://jingxuan.douyin.com/m/video/$aid?app=yumme", mapOf("User-Agent" to UA_MOBILE))
        val i = body.indexOf("window._SSR_DATA")
        if (i < 0) JxInfo() else {
            val j = body.indexOf("</script>", i)
            val seg = body.substring(i, if (j > i) j else body.length)
            val json = seg.substringAfter("=").trim().trimEnd(';')
            val res = (parseJson(json) as? JsonObject)
                ?.obj("data")?.obj("storeState")?.obj("detail")?.obj("videoData")?.obj("result")
            if (res == null) JxInfo() else {
                var fb: String? = null
                val vmRaw = res.str("video_model")
                if (vmRaw.isNotEmpty()) {
                    val vm = parseJson(vmRaw) as? JsonObject
                    fb = when (val f = vm?.any("fallback_api")) {
                        is JsonObject -> f.str("fallback_api").ifEmpty { null }
                        is JsonElement -> f.toString().trim('"').ifEmpty { null }
                        else -> null
                    }
                }
                JxInfo(res.lng("play_count"), fb)
            }
        }
    } catch (_: Exception) {
        JxInfo()
    }

    // ------------------------------------------------------------------ fplay（原画真值 + 解密直链）
    private val QAAB_SALT = hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba4" + "1cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f" + "1f05a51892aef2949732b62a38aadd58"
    )

    private fun hexToBytes(h: String): ByteArray =
        ByteArray(h.length / 2) {
            ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte()
        }

    private fun b64Loose(value: String?): ByteArray? {
        val text = (value ?: "").trim()
        if (text.isEmpty()) return null
        val variants = listOf(
            text,
            text.replace('$', '_').replace('@', '/').replace('#', '.'),
            text.replace('$', '+').replace('@', '/').replace('#', '='),
        )
        for (cand in variants) {
            if (cand.isEmpty()) continue
            val norm = cand.replace('-', '+').replace('_', '/') + "=".repeat((4 - cand.length % 4) % 4)
            if (!Regex("^[A-Za-z0-9+/]+={0,2}$").matches(norm)) continue
            b64Decode(norm)?.let { return it }
        }
        return null
    }

    private fun urlFromBytes(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        var end = raw.size
        val pad = raw.last().toInt()
        if (pad in 1..16 && raw.size >= pad) {
            var ok = true
            for (i in raw.size - pad until raw.size) if (raw[i] != raw.last()) ok = false
            if (ok) end = raw.size - pad
        }
        val s = raw.decodeToString(0, end).trim()
        return if (s.startsWith("http")) s else null
    }

    fun decodeMainUrl(token: String?, keySeed: String?): String {
        val t = (token ?: "").trim()
        if (t.startsWith("http")) return t
        val raw = b64Loose(t) ?: return ""
        urlFromBytes(raw)?.let { return it }
        if (!t.startsWith("qAAB") || keySeed.isNullOrEmpty()) return ""
        val seed = b64Loose(keySeed) ?: return ""
        if (seed.size < 32) return ""
        val material = sha512(sha512(seed.copyOf(32)) + QAAB_SALT)
        val key = material.copyOfRange(0, 16)
        val iv = material.copyOfRange(16, 32)
        val attempts = mutableListOf<Pair<ByteArray, Pair<ByteArray, ByteArray>>>()
        val isQaab = raw.size > 4 && raw[0] == 0xA8.toByte() && raw[1] == 0.toByte() &&
            raw[2] == 1.toByte() && raw[3] == 0.toByte()
        if (isQaab) {
            val p4 = raw.copyOfRange(4, raw.size)
            attempts += p4 to (key to iv)
            attempts += p4 to (iv to key)
            if (raw.size > 36) {
                val p36 = raw.copyOfRange(36, raw.size)
                attempts += p36 to (key to raw.copyOfRange(20, 36))
                attempts += p36 to (key to iv)
            }
        } else {
            attempts += raw to (key to iv)
        }
        for ((payload, kv) in attempts) {
            val dec = aesCbcDecrypt(payload, kv.first, kv.second) ?: continue
            urlFromBytes(dec)?.let { return it }
        }
        return ""
    }

    private fun normVideoList(v: JsonElement?): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        when (v) {
            is JsonArray -> v.forEach { (it as? JsonObject)?.let { o -> out += unwrapMeta(o) } }
            is JsonObject -> v.values.forEach { (it as? JsonObject)?.let { o -> out += unwrapMeta(o) } }
            else -> {}
        }
        return out
    }

    private fun unwrapMeta(x: JsonObject): JsonObject {
        val mo = when (val m = x.any("video_meta")) {
            is JsonObject -> m
            is JsonElement -> try {
                parseJson(m.toString().trim('"')) as? JsonObject
            } catch (_: Exception) {
                null
            }
            else -> null
        } ?: return x
        return buildJsonObject {
            for ((k, v) in x) if (k != "video_meta") put(k, v)
            for ((k, v) in mo) put(k, v)
        }
    }

    data class OriginInfo(
        val size: Long, val w: Int, val h: Int, val fps: Double,
        val bps: Long, val codec: String, val urls: List<String>,
    )

    suspend fun fplayOriginal(fallbackApi: String?): OriginInfo? {
        if (fallbackApi.isNullOrBlank()) return null
        var url = Regex("codec_type=\\d+").replace(fallbackApi, "codec_type=8")
        if (!url.contains("codec_type=")) url += "&codec_type=8"
        val body = Http.getText(url, mapOf("User-Agent" to UA_MOBILE))
        val d = (parseJson(body) as? JsonObject)?.obj("video_info")?.obj("data") ?: return null
        val g = normVideoList(d.any("video_list"))
            .firstOrNull { it.str("quality").lowercase() == "original" } ?: return null
        val seed = d.str("key_seed")
        val urls = mutableListOf<String>()
        for (k in listOf("main_url", "backup_url_1")) {
            val u = decodeMainUrl(g.str(k), seed)
            if (u.isNotEmpty() && u !in urls) urls += u
        }
        return OriginInfo(
            size = g.lng("size"),
            w = g.int("vwidth"), h = g.int("vheight"),
            fps = if (g.dbl("float_fps") > 0) g.dbl("float_fps") else g.dbl("fps"),
            bps = if (g.lng("bitrate") > 0) g.lng("bitrate") else g.lng("real_bitrate"),
            codec = g.str("codec_type").lowercase(),
            urls = urls,
        )
    }

    // ------------------------------------------------------------------ 原画 302
    private fun swapHost(url: String): String? {
        if ("v26-web" !in url) return null
        return Regex("://[^/]+").replaceFirst(url, "://$V26_ALT_HOST")
    }

    private suspend fun playHop(api: String, params: Map<String, String>, maxHop: Int = 3): String {
        var loc = ""
        var cur: String = urlWithParams(api, params)
        var hop = 0
        while (hop++ < maxHop) {
            val next = try {
                Http.location(cur, mapOf("User-Agent" to UA))
            } catch (_: Exception) {
                null
            } ?: return loc
            loc = next
            if (!Regex("://[^/]*(douyin|snssdk|amemv)").containsMatchIn(next)) return loc
            cur = next
        }
        return loc
    }

    private suspend fun probeSize(url: String): Long = try {
        val h = Http.getHeaders(
            url,
            mapOf(
                "User-Agent" to UA,
                "Referer" to "https://www.douyin.com/",
                "Range" to "bytes=0-0",
            ),
        )
        val ct = (h["Content-Type"] ?: "").lowercase()
        if (ct.isNotEmpty() && !ct.startsWith("video/") && "octet-stream" !in ct) 0L else {
            val cr = h["Content-Range"] ?: ""
            if ("/" in cr) cr.substringAfterLast("/").trim().toLongOrNull() ?: 0L
            else h["Content-Length"]?.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    suspend fun resolveOrigin(uri: String): Pair<List<String>, Long> {
        if (!Regex("^[A-Za-z0-9_\\-]{10,64}$").matches(uri)) return emptyList<String>() to 0L
        val params = mapOf(
            "video_id" to uri, "ratio" to "default", "watermark" to "0",
            "is_play_url" to "1", "source" to "PackSourceEnum_PUBLISH",
        )
        for (line in listOf(0, 2, 5, 3, 1, 4)) {
            val p = params + ("line" to line.toString())
            for (api in listOf(PLAY_API, PLAY_API_ALT)) {
                val loc = playHop(api, p)
                if (loc.isNotEmpty()) return listOf(loc) to probeSize(loc)
            }
        }
        return emptyList<String>() to 0L
    }

    // ------------------------------------------------------------------ 候选档位
    private fun strList(a: JsonArray?): List<String> = a?.strs() ?: emptyList()

    private fun urlListOf(node: JsonObject?): List<String> {
        if (node == null) return emptyList()
        val a = node.arr("url_list") ?: node.arr("urlList") ?: return emptyList()
        return a.strs()
    }

    private fun preferUrls(urls: List<String>): List<String> {
        val head = urls.filter { it.contains("v3-web") }
        return if (head.isEmpty() || head.size == urls.size) urls else head + urls.filter { it !in head }
    }

    suspend fun buildTiers(detail: JsonObject, jx: JxInfo, wantOrigin: Boolean): List<Tier> {
        val v = detail.obj("video") ?: JsonObject(emptyMap())
        val raw = mutableListOf<Tier>()
        val br = v.arr("bit_rate")
        if (br != null) {
            for (b in br.objs()) {
                val pa = b.obj("play_addr") ?: continue
                val urls = preferUrls(urlListOf(pa)).map { ensureMime(toHttps(it)) }
                if (urls.isEmpty()) continue
                raw += Tier(
                    idx = -1, gear = b.str("gear_name", "gear"),
                    w = pa.int("width"), h = pa.int("height"),
                    bps = b.lng("bit_rate"), size = pa.lng("data_size"),
                    h265 = b.bool("is_h265"),
                    fps = b.dbl("FPS"), fpsEst = true,
                    isOrigin = false, urls = urls,
                    uri = pa.str("uri").ifEmpty { null },
                )
            }
        }
        if (raw.isEmpty()) {
            val pa = v.obj("play_addr")
            val urls = preferUrls(urlListOf(pa)).map { ensureMime(toHttps(it)) }
            if (urls.isNotEmpty()) {
                raw += Tier(
                    -1, "default", pa?.int("width") ?: 0, pa?.int("height") ?: 0,
                    0L, pa?.lng("data_size") ?: 0L, v.bool("is_h265"), 0.0, true,
                    false, urls, pa?.str("uri")?.ifEmpty { null },
                )
            }
        }
        val uniq = raw.distinctBy { "${it.gear}/${it.w}/${it.h}/${it.bps}/${it.size}" }
            .sortedWith(compareByDescending<Tier> { it.w.toLong() * it.h }.thenByDescending { it.bps })
            .toMutableList()

        if (wantOrigin && uniq.isNotEmpty()) {
            val top = uniq.first()
            val uri = v.obj("play_addr")?.str("uri").orEmpty().ifEmpty { top.uri ?: "" }
            val real = try {
                fplayOriginal(jx.fallbackApi)
            } catch (_: Exception) {
                null
            }
            val ourls: List<String>
            val osize: Long
            if (real != null && real.urls.isNotEmpty()) {
                ourls = real.urls
                osize = real.size
            } else {
                val p = resolveOrigin(uri)
                ourls = p.first
                osize = p.second
            }
            if (ourls.isNotEmpty()) {
                val dur = v.lng("duration")
                var w = v.int("width", top.w)
                var h = v.int("height", top.h)
                val size = if (osize > 0) osize else top.size
                var bps = if (size > 0 && dur > 0) size * 8 * 1000 / dur else top.bps
                var fps = top.fps
                var fpsEst = true
                var codec: String? = null
                if (real != null) {
                    if (real.w > 0) w = real.w
                    if (real.h > 0) h = real.h
                    if (real.bps > 0) bps = real.bps
                    if (real.fps > 0) {
                        fps = real.fps
                        fpsEst = false
                    }
                    codec = real.codec.ifEmpty { null }
                }
                val h265 = if (codec != null) codec == "hevc" else (v.bool("is_h265") || top.h265)
                uniq += Tier(
                    -1, "原画", w, h, bps, size, h265, fps, fpsEst,
                    true, ourls.map { ensureMime(toHttps(it)) }, uri.ifEmpty { null },
                )
                uniq.sortWith(compareByDescending<Tier> { it.w.toLong() * it.h }.thenByDescending { it.bps })
            }
        }
        return uniq.mapIndexed { i, t -> t.copy(idx = i) }
    }

    // ------------------------------------------------------------------ 图集 / 附属
    private fun pickLiveUrl(video: JsonObject?): String? {
        if (video == null) return null
        val cands = mutableListOf<String>()
        cands += urlListOf(video.obj("play_addr"))
        video.arr("playAddr")?.objs()?.forEach { it.str("src").takeIf { s -> s.isNotEmpty() }?.let(cands::add) }
        if (cands.isEmpty()) return null
        val v3 = cands.filter { "v3-web" in it }
        val v26 = cands.filter { "v26-web" in it }
        var pick = (v3.ifEmpty { v26 }.ifEmpty { cands })[0]
        if (pick in v26) swapHost(pick)?.let { pick = it }
        pick = pick.replace("playwm", "play")
        return ensureMime(toHttps(pick))
    }

    fun extractImages(detail: JsonObject): List<ImageItem> {
        var raw: JsonArray? = detail.arr("images") ?: detail.arr("image_list")
        if (raw == null) raw = detail.obj("image_post_info")?.arr("images")
        if (raw == null) return emptyList()
        val out = mutableListOf<ImageItem>()
        for (item in raw) {
            val im = item as? JsonObject ?: continue
            var node = im
            val disp = im.obj("display_image")
            if (disp != null) node = disp
            var urls = urlListOf(node).ifEmpty { urlListOf(im) }
            val dl = im.arr("download_url_list")?.strs() ?: emptyList()
            var live = pickLiveUrl(im.obj("video"))
            if (live == null && disp != null) live = pickLiveUrl(disp.obj("video"))
            val jpegs = urls.filter { ".jpeg" in it.substringBefore('?') || ".jpg" in it.substringBefore('?') }
            val webps = urls.filter { ".webp" in it.substringBefore('?') }
            val url = toHttps((jpegs.ifEmpty { webps }.ifEmpty { urls }).firstOrNull()) ?: continue
            val ext = when {
                ".webp" in url.substringBefore('?') -> "webp"
                ".png" in url.substringBefore('?') -> "png"
                ".heic" in url.substringBefore('?') -> "heic"
                else -> "jpg"
            }
            out += ImageItem(
                index = out.size,
                url = url,
                ext = ext,
                live = live,
                w = node.int("width").takeIf { it > 0 } ?: im.int("width"),
                h = node.int("height").takeIf { it > 0 } ?: im.int("height"),
            )
        }
        return out
    }

    fun collectAux(detail: JsonObject): List<AuxItem> {
        val out = mutableListOf<AuxItem>()
        val v = detail.obj("video") ?: JsonObject(emptyMap())
        val bra = v.arr("bit_rate_audio")
        if (bra != null && bra.size > 0) {
            val am = (bra[0] as? JsonObject)?.obj("audio_meta")
            val ul = am?.any("url_list")
            if (am != null && ul is JsonObject) {
                val urls = listOf("main_url", "backup_url", "fallback_url")
                    .mapNotNull { ul.str(it).takeIf { s -> s.isNotEmpty() } }
                if (urls.isNotEmpty()) {
                    out += AuxItem(
                        "audio", "原声",
                        "${am.str("codec_type", "?")} · " +
                            (am.lng("size").takeIf { it > 0 }?.let { Fmt.size(it) } ?: "-"),
                        "m4a", urls,
                    )
                }
            }
        }
        if (out.none { it.kind == "audio" }) {
            val music = detail.obj("music")
            val play = music?.obj("play_url")
            val ul = play?.any("url_list")
            val urls = when (ul) {
                is JsonArray -> ul.strs()
                is JsonObject -> listOf("main_url", "backup_url", "fallback_url")
                    .mapNotNull { ul.str(it).takeIf { s -> s.isNotEmpty() } }
                else -> emptyList()
            }
            if (urls.isNotEmpty()) {
                val name = music?.str("music_name").orEmpty().ifEmpty { music?.str("title").orEmpty() }.ifEmpty { "配乐" }
                out += AuxItem("audio", "配乐", name, "mp3", urls)
            }
        }
        val seen = mutableSetOf<String>()
        for ((key, label) in listOf("download_addr" to "带水印", "download_suffix_logo_addr" to "带尾标")) {
            val da = v.obj(key) ?: continue
            val urls = urlListOf(da)
            if (urls.isEmpty()) continue
            val fh = da.str("file_cs").ifEmpty { da.str("uri") }
            if (fh.isNotEmpty() && !seen.add(fh)) continue
            out += AuxItem(
                "download", label,
                "${da.int("width")}x${da.int("height")} · " +
                    (da.lng("data_size").takeIf { it > 0 }?.let { Fmt.size(it) } ?: "-"),
                "mp4", urls.map { ensureMime(toHttps(it)) },
            )
        }
        return out
    }

    private fun pickCover(detail: JsonObject): String? {
        val v = detail.obj("video") ?: JsonObject(emptyMap())
        for (node in listOf(v.obj("origin_cover"), v.obj("cover"))) {
            urlListOf(node).firstOrNull()?.let { return toHttps(it) }
        }
        urlListOf(detail.obj("cover")).firstOrNull()?.let { return toHttps(it) }
        return extractImages(detail).firstOrNull()?.url
    }

    // ------------------------------------------------------------------ 主入口
    suspend fun parse(text: String, opts: ParseOptions): ParseResult {
        val aid = resolveAwemeId(text)
        val detail = fetchDetail(aid)
        val jx = fetchJingxuan(aid)
        val wantOrigin = opts.defaultTier != "none"
        val tiers = buildTiers(detail, jx, wantOrigin)
        val images = extractImages(detail)
        val aux = collectAux(detail)

        val author = detail.obj("author")?.str("nickname").orEmpty().ifEmpty { "未知作者" }
        val ts = detail.lng("create_time")
        val date = fmtDate(ts).ifEmpty { today() }
        val title = Regex("\\s+").replace(detail.str("desc").trim(), " ").ifEmpty { "无标题" }
        val v = detail.obj("video") ?: JsonObject(emptyMap())
        val durMs = v.lng("duration")
        val st = detail.obj("statistics") ?: JsonObject(emptyMap())
        val playTxt = if (jx.playCount > 0) "播 ${jx.playCount} / " else ""
        val statLine = playTxt + "赞 ${st.lng("digg_count")} / 评 ${st.lng("comment_count")}" +
            " / 藏 ${st.lng("collect_count")} / 转 ${st.lng("share_count")}"
        return ParseResult(
            platform = Platform.DOUYIN,
            id = aid,
            title = title.take(120),
            author = author,
            date = date,
            durSec = if (durMs > 1000) durMs / 1000.0 else durMs.toDouble(),
            statLine = statLine,
            width = v.int("width"),
            height = v.int("height"),
            cover = pickCover(detail),
            referer = Platform.DOUYIN.referer,
            tiers = tiers,
            aux = aux,
            images = images,
        )
    }
}
