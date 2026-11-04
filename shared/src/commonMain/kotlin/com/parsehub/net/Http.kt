package com.parsehub.net

import com.parsehub.core.J
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement

/** 平台桥接：建 HttpClient（android/desktop 都用 OkHttp 引擎） */
internal expect fun newHttpClient(followRedirects: Boolean): HttpClient

object Http {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    val client: HttpClient by lazy { newHttpClient(true) }
    val noRedirect: HttpClient by lazy { newHttpClient(false) }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cli: HttpClient = client,
    ): String {
        val resp = cli.get(url) { applyHeaders(headers) }
        if (resp.status.value >= 400) throw RuntimeException("HTTP ${resp.status.value}")
        return resp.bodyAsText()
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cli: HttpClient = client,
    ): JsonElement = J.parseToJsonElement(getText(url, headers, cli))

    suspend fun postRaw(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val resp = client.post(url) {
            applyHeaders(headers)
            contentType(ContentType.parse(contentType))
            setBody(body)
        }
        if (resp.status.value >= 400) throw RuntimeException("HTTP ${resp.status.value}")
        return resp.bodyAsText()
    }

    /** 取一次 302 的 Location（不跟随重定向） */
    suspend fun location(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cli: HttpClient = noRedirect,
    ): String? {
        val resp = cli.get(url) { applyHeaders(headers) }
        return resp.headers[HttpHeaders.Location]
    }

    suspend fun getHeaders(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cli: HttpClient = client,
    ): Headers {
        val resp = cli.get(url) { applyHeaders(headers) }
        return resp.headers
    }

    /** 手动逐跳跟随重定向，返回最终 URL */
    suspend fun followChain(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxHop: Int = 6,
    ): String {
        var cur = url
        var hop = 0
        while (hop++ < maxHop) {
            val loc = location(cur, headers) ?: return cur
            cur = resolveUrl(cur, loc)
        }
        return cur
    }

    fun resolveUrl(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val se = base.indexOf("://")
        if (se <= 0) return location
        val hs = base.indexOf('/', se + 3)
        val origin = if (hs > 0) base.substring(0, hs) else base
        if (location.startsWith("//")) return base.substring(0, se + 1) + location
        if (location.startsWith("/")) return origin + location
        val dirEnd = if (hs > 0) base.lastIndexOf('/') else -1
        return if (dirEnd > hs) base.substring(0, dirEnd + 1) + location else origin + "/" + location
    }

    private fun HttpRequestBuilder.applyHeaders(h: Map<String, String>) {
        h.forEach { (k, v) -> header(k, v) }
    }
}

internal fun formEncode(s: String): String = s.encodeURLParameter(spaceToPlus = true)
