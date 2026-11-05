package com.parsehub

import com.parsehub.net.Http
import com.parsehub.net.newHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpTest {
    @Test fun resolvesRelativeReferencesWithoutLosingSignedQuery() {
        val base = "https://www.xiaohongshu.com/a/b?old=1"
        assertEquals("https://www.xiaohongshu.com/a/b?xsec_token=test%2Btoken&xsec_source=share", Http.resolveUrl(base, "?xsec_token=test%2Btoken&xsec_source=share"))
        assertEquals("https://www.xiaohongshu.com/note/123", Http.resolveUrl(base, "../note/123"))
        assertEquals("https://www.xiaohongshu.com/explore/123", Http.resolveUrl(base, "/explore/123"))
        assertEquals("https://other.example/item", Http.resolveUrl(base, "//other.example/item"))
        assertEquals("https://other.example/absolute", Http.resolveUrl(base, "https://other.example/absolute"))
        assertEquals("https://www.xiaohongshu.com/a/b?old=1#section", Http.resolveUrl(base, "#section"))
        assertEquals("https://www.xiaohongshu.com/root/", Http.resolveUrl(base, "../../root/"))
        assertEquals("https://www.xiaohongshu.com/a/", Http.resolveUrl(base, "./"))
        assertEquals("https://www.xiaohongshu.com/a/b?", Http.resolveUrl(base, "?"))
    }

    @Test fun exposesFirstRedirectAndIgnoresLocationOnSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/final?xsec_token=test%2Btoken")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/final") { exchange ->
            exchange.responseHeaders.add("Location", "/should-not-follow")
            val bytes = "page".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/missing") { exchange ->
            val bytes = "not found".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val noRedirect = newHttpClient(false)
        val redirect = newHttpClient(true)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("/final?xsec_token=test%2Btoken", Http.location("$base/start", cli = noRedirect))
            assertNull(Http.location("$base/final", cli = noRedirect))
            val page = Http.getPage("$base/start", cli = redirect)
            assertEquals(200, page.status)
            assertEquals("$base/final?xsec_token=test%2Btoken", page.url)
            assertEquals("page", page.body)
            val missing = Http.getPage("$base/missing", cli = noRedirect)
            assertEquals(404, missing.status)
            assertEquals("not found", missing.body)
        } finally {
            noRedirect.close()
            redirect.close()
            server.stop(0)
        }
    }
}
