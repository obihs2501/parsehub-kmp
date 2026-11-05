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
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.http.encodedPath
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

    data class Page(val status: Int, val url: String, val body: String)

    suspend fun getPage(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cli: HttpClient = client,
    ): Page {
        val resp = cli.get(url) { applyHeaders(headers) }
        return Page(resp.status.value, resp.call.request.url.toString(), resp.bodyAsText())
    }

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
        return if (resp.status.value in setOf(301, 302, 303, 307, 308)) {
            resp.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
        } else null
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
        if (location.isEmpty()) return base.substringBefore('#')
        // Keep the encoded query verbatim: parsing and rebuilding can change signed bytes.
        val reference = location.substringBefore('#').substringBefore('?')
        val suffix = when {
            '?' in location.substringBefore('#') -> "?" + location.substringAfter('?')
            reference.isEmpty() -> base.substringBefore('#').substringAfter('?', "")
                .let { if ('?' in base.substringBefore('#')) "?$it" else "" } +
                (if ('#' in location) "#" + location.substringAfter('#') else "")
            '#' in location -> "#" + location.substringAfter('#')
            else -> ""
        }
        val builder = URLBuilder(base.substringBefore('#').substringBefore('?'))
        if (reference.isNotEmpty()) builder.takeFrom(reference)
        val segments = mutableListOf<String>()
        val path = builder.encodedPath
        for (segment in path.split('/')) {
            when (segment) {
                "." -> Unit
                ".." -> if (segments.isNotEmpty() && segments.last().isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        if (path.endsWith("/.") || path.endsWith("/..")) segments += ""
        builder.encodedPath = segments.joinToString("/").ifEmpty { "/" }
        return builder.buildString() + suffix
    }

    private fun HttpRequestBuilder.applyHeaders(h: Map<String, String>) {
        h.forEach { (k, v) -> header(k, v) }
    }
}

internal fun formEncode(s: String): String = s.encodeURLParameter(spaceToPlus = true)
