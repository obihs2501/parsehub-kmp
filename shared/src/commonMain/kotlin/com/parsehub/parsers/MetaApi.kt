package com.parsehub.parsers

import com.parsehub.core.J
import com.parsehub.core.arr
import com.parsehub.core.int
import com.parsehub.core.obj
import com.parsehub.core.str
import com.parsehub.net.Http
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Meta（Instagram / Threads）GraphQL 公共助手。
 *
 * - 两个平台都要求先 GET 首页拿到 csrftoken Cookie，再用 x-csrftoken 头 POST GraphQL；
 * - POST 不能被重定向（follow_redirects=False），否则会被 302 到登录页拿到 HTML；
 * - 用户可在设置里按平台填 Cookie，用于年龄限制 / 仅登录可见的内容（不落任何明文到仓库）。
 */
internal object MetaApi {
    private const val UA_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val UA_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    /** 从 Set-Cookie 里抽某个 cookie 的值（容错大小写 / 分号 / 多值头） */
    private fun cookieValue(headers: io.ktor.http.Headers, name: String): String {
        val lines = headers.getAll(HttpHeaders.SetCookie).orEmpty()
        for (line in lines) {
            for (part in line.split(';')) {
                val kv = part.trim()
                val eq = kv.indexOf('=')
                if (eq > 0 && kv.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                    return kv.substring(eq + 1).trim().trim('"')
                }
            }
        }
        return ""
    }

    /** GET 首页并抓 csrftoken；失败返回空串（匿名请求多数公开帖子仍可用） */
    private suspend fun fetchCsrf(home: String, userCookie: String): String {
        val h = mutableMapOf("User-Agent" to UA_CHROME, "Accept" to "*/*")
        if (userCookie.isNotEmpty()) h["Cookie"] = userCookie
        return runCatching { cookieValue(Http.getHeaders(home, h), "csrftoken") }.getOrDefault("")
    }

    /** 合并匿名默认 Cookie 与用户 Cookie（同名时用户值优先） */
    private fun mergeCookie(anonymous: Map<String, String>, userCookie: String): String {
        val map = anonymous.toMutableMap()
        for (part in userCookie.split(';')) {
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq > 0) map[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * POST GraphQL 表单，不跟随重定向。
     * @return 解析后的 JsonObject
     */
    suspend fun graphql(
        endpoint: String,
        home: String,
        docId: String,
        variables: JsonObject,
        referer: String,
        igAppId: String? = null,
        userCookie: String = "",
        anonymous: Map<String, String> = emptyMap(),
    ): JsonObject {
        val csrf = fetchCsrf(home, userCookie).ifEmpty {
            // 用户 Cookie 里可能直接带了 csrftoken
            cookieValueFromRaw(userCookie, "csrftoken")
        }
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to referer,
            "User-Agent" to UA_CHROME,
            "x-csrftoken" to csrf,
        )
        if (igAppId != null) headers["X-IG-App-ID"] = igAppId
        val cookie = mergeCookie(anonymous + ("csrftoken" to csrf), userCookie)
        headers["Cookie"] = cookie

        val body = "variables=" + com.parsehub.net.formEncode(variables.toString()) +
            "&doc_id=" + docId + "&server_timestamps=true"
        val resp = Http.noRedirect.post(endpoint) {
            headers.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        val text = resp.bodyAsText()
        if (resp.status.value >= 400) throw RuntimeException("HTTP ${resp.status.value}")
        val j = runCatching { J.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw RuntimeException("接口返回的不是 JSON（可能需要登录 Cookie）")
        if (j.arr("errors") != null) {
            throw RuntimeException("接口报错：" + j.arr("errors")!!.firstOrNull()?.toString()?.take(80))
        }
        return j
    }

    private fun cookieValueFromRaw(raw: String, name: String): String {
        for (part in raw.split(';')) {
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq > 0 && kv.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                return kv.substring(eq + 1).trim()
            }
        }
        return ""
    }

    val ANON_IG = mapOf(
        "sessionid" to "", "mid" to "", "ig_pr" to "1", "ig_vw" to "1920",
        "csrftoken" to "", "s_network" to "", "ds_user_id" to "",
    )
    val ANON_THREADS = mapOf(
        "sessionid" to "", "ds_user_id" to "", "csrftoken" to "", "mid" to "", "ig_did" to "",
    )

    /** 从 image_versions2.candidates / video_versions 中取第一个可用直链 */
    fun firstUrl(node: JsonObject, key: String): String {
        val arr = node.arr(key) ?: return ""
        for (v in arr) {
            val o = v as? JsonObject ?: continue
            val u = o.str("url")
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    fun firstCandidate(node: JsonObject, key: String = "image_versions2"): Pair<String, Pair<Int, Int>> {
        val candidates = node.obj(key)?.arr("candidates") ?: node.arr(key)
        val c = candidates?.firstOrNull() as? JsonObject
        val url = c?.str("url").orEmpty()
        val w = c?.int("width") ?: 0
        val h = c?.int("height") ?: 0
        return url to (w to h)
    }

    fun userOf(node: JsonObject): Pair<String, String> {
        val u = node.obj("user")
        return (u?.str("username").orEmpty() to u?.str("pk").orEmpty())
    }

    fun captionOf(node: JsonObject): String {
        node.obj("caption")?.str("text")?.takeIf { it.isNotEmpty() }?.let { return it }
        val edges = node.obj("edge_media_to_caption")?.arr("edges")
        val node2 = (edges?.firstOrNull() as? JsonObject)?.obj("node")
        node2?.str("text")?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }
}

/** Threads 短码 -> 十进制 postID（与 Instagram shortcode 同一套字母表） */
internal fun shortcodeToPk(code: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    var pk = 0L
    for (ch in code) {
        val idx = alphabet.indexOf(ch)
        if (idx < 0) continue
        pk = pk * 64 + idx
    }
    return pk.toString()
}

/** 构造带若干 false 标志的 GraphQL variables（Meta 前端要求显式传这些 feature flags） */
internal fun metaVariables(core: Map<String, String>, flags: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        core.forEach { (k, v) -> put(k, v) }
        flags.forEach { put(it, false) }
    }
