package com.parsehub

import com.parsehub.core.ParseOptions
import com.parsehub.core.obj
import com.parsehub.core.str
import com.parsehub.net.Http
import com.parsehub.parsers.XhsParser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.*

class XhsParserTest {
    private val url = "https://www.xiaohongshu.com/explore/wanted?xsec_token=fixture%2Btoken&share_token=keep&from=share"
    private val note = """{"noteId":"wanted","type":"normal","title":"fixture","user":{"nickname":"tester"},"imageList":[{"url":"https://example.test/image.jpg"}]}"""

    private fun desktop(data: String = note) =
        """<script nonce="fixture" type="text/javascript">window.__INITIAL_STATE__ = {"note":{"firstNoteId":"wanted","noteDetailMap":{"wanted":{"note":$data}}}}; window.after = true;</script>"""

    // Fixture fetchers complete inline: no dispatcher, network, delay or platform test runtime.
    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "Fixture unexpectedly suspended" }.getOrThrow()
    }

    @Test fun preservesAllSignedQueryBytesAndOnlyRewritesAliasHost() {
        val signed = "$url&share_token=fixture%2Btoken&share_source=fixture&from=share"
        assertEquals(signed, XhsParser.shareUrl("分享 $signed。"))
        val alias = "https://xhs.com/a/abc?next=https://xhs.com&xsec_token=a%2Bb"
        assertEquals("https://xhslink.com/a/abc?next=https://xhs.com&xsec_token=a%2Bb", XhsParser.shareUrl(alias))
    }

    @Test fun mobileSafariAcceptAndCookieArePairedOnEveryRequest() {
        val mobile = XhsParser.pageHeaders(true, "fixture=cookie")
        assertTrue(mobile.getValue("User-Agent").contains("Version/18.5 Mobile/"))
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", mobile["Accept"])
        assertFalse(mobile.getValue("Accept").contains("signed-exchange"))
        assertEquals("fixture=cookie", mobile["Cookie"])
        val desktop = XhsParser.pageHeaders(false, "fixture=cookie")
        assertTrue(desktop.getValue("User-Agent").contains("Chrome/"))
        assertTrue(desktop.getValue("Accept").contains("signed-exchange"))
        assertEquals("fixture=cookie", desktop["Cookie"])
        assertFalse(XhsParser.pageHeaders(true).containsKey("Cookie"))
    }

    @Test fun handlesAttributesSemicolonNestedBracesEscapesAndJsValues() {
        val state = XhsParser.initialState("""
              <script data-comparison="a > b" nonce='fixture' type="text/javascript">
              window.__INITIAL_STATE__ = {
                "text":"undefined new Set([1]) }; { \"quote\" \\ tail",
                "missing":undefined,
                "nested":{"values":new Set([{"x":undefined},["bracket ]",new Set([1,2])]])},
                "empty":new Set()
              }; window.after = "ignored";
            </SCRIPT>
        """.trimIndent())
        assertEquals("undefined new Set([1]) }; { \"quote\" \\ tail", state.str("text"))
        assertEquals(JsonNull, state["missing"])
        assertEquals("{\"values\":[{\"x\":null},[\"bracket ]\",[1,2]]]}", state.obj("nested").toString())
        assertEquals("[]", state["empty"].toString())
    }

    @Test fun malformedScriptDoesNotHideLaterValidState() {
        val html = "<script>window.__INITIAL_STATE__={\"broken\":new Unknown()};</script>" + desktop()
        assertEquals("fixture", XhsParser.parsePage(200, url, html).title)
        assertFailsWith<XhsParser.PageFailure> {
            XhsParser.initialState("<script>window.__INITIAL_STATE__={\"x\":undefinedValue};</script>")
        }
    }

    @Test fun requestedMapEntryWinsOverFirstNoteAndH5() {
        val state = XhsParser.initialState("""<script>window.__INITIAL_STATE__={
            "note":{"firstNoteId":"other","noteDetailMap":{
                "other":{"note":{"noteId":"other","title":"wrong"}},
                "wanted":{"note":$note}}},
            "noteData":{"data":{"noteData":{"noteId":"wanted","title":"h5"}}}
        };</script>""")
        assertEquals("fixture", XhsParser.selectNote(state, "wanted").str("title"))
    }

    @Test fun acceptsBareScriptWithoutSemicolonAndMatchingFirstNote() {
        val html = """<script>window.__INITIAL_STATE__={"note":{"firstNoteId":"wanted","noteDetailMap":{"wanted":{"note":$note}}}}</script>"""
        assertEquals("fixture", XhsParser.parsePage(200, url, html).title)
    }

    @Test fun h5FallbackRequiresMatchingIdentity() {
        val h5 = """<script data-fixture="h5">window.__INITIAL_STATE__={"noteData":{"data":{"noteData":$note}}};</script>"""
        assertEquals("wanted", XhsParser.parsePage(200, url, h5).id)
        for (badNote in listOf(note.replace("wanted", "other"), """{"title":"missing identity"}""")) {
            val failure = assertFailsWith<XhsParser.PageFailure> {
                XhsParser.parsePage(200, url, h5.replace(note, badNote))
            }
            assertEquals(XhsParser.FailureKind.IDENTITY, failure.kind)
        }
    }

    @Test fun rejectsRecommendedNoteAndContradictoryIdentityAndRedirect() {
        val other = desktop().replace("wanted", "other")
        assertEquals(XhsParser.FailureKind.IDENTITY, assertFailsWith<XhsParser.PageFailure> {
            XhsParser.parsePage(200, url, other)
        }.kind)
        assertEquals(XhsParser.FailureKind.IDENTITY, assertFailsWith<XhsParser.PageFailure> {
            XhsParser.parsePage(200, url, desktop(note.replace("wanted", "other")))
        }.kind)
        assertEquals(XhsParser.FailureKind.IDENTITY, assertFailsWith<XhsParser.PageFailure> {
            XhsParser.parsePage(200, url.replace("wanted", "other"), desktop(), "wanted")
        }.kind)
    }

    @Test fun classifiesRedirectPathsAndStatusWithoutReadingQueryOrEchoingSecrets() {
        val cases = listOf(
            Triple(200, "/login", XhsParser.FailureKind.LOGIN),
            Triple(200, "/404/sec", XhsParser.FailureKind.RISK),
            Triple(200, "/captcha", XhsParser.FailureKind.RISK),
            Triple(200, "/404", XhsParser.FailureKind.NOT_FOUND),
            Triple(401, "/explore/wanted", XhsParser.FailureKind.LOGIN),
            Triple(403, "/explore/wanted", XhsParser.FailureKind.RISK),
            Triple(429, "/explore/wanted", XhsParser.FailureKind.RISK),
            Triple(404, "/explore/wanted", XhsParser.FailureKind.NOT_FOUND),
            Triple(500, "/explore/wanted", XhsParser.FailureKind.REQUEST),
        )
        for ((status, path, expected) in cases) {
            val failure = assertFailsWith<XhsParser.PageFailure> {
                XhsParser.parsePage(status, "https://www.xiaohongshu.com$path?xsec_token=private", desktop())
            }
            assertEquals(expected, failure.kind)
            assertFalse(failure.message.orEmpty().contains("private"))
            assertFalse(failure.message.orEmpty().contains("https://"))
            assertNull(failure.cause)
        }
        assertNull(XhsParser.pageFailure(200, "$url&next=/login&hint=/404/sec"))
    }

    @Test fun fallsBackOnceUsingOriginalSignedUrlAndMatchingHeaders() {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val result = immediate {
            XhsParser.parseWithFetcher(url, ParseOptions(cookieXhs = "fixture=cookie")) { requested, headers ->
                calls += requested to headers
                if (calls.size == 1) Http.Page(200, "https://www.xiaohongshu.com/404/sec", "captcha")
                else Http.Page(200, url, desktop())
            }
        }
        assertEquals("fixture", result.title)
        assertEquals(listOf(url, url), calls.map { it.first })
        assertEquals(XhsParser.pageHeaders(true, "fixture=cookie"), calls[0].second)
        assertEquals(XhsParser.pageHeaders(false, "fixture=cookie"), calls[1].second)
    }

    @Test fun shortShareUsesFinalIdAndStopsAfterSuccess() {
        var calls = 0
        val result = immediate {
            XhsParser.parseWithFetcher("https://xhslink.com/a/fixture?share_token=keep", ParseOptions()) { _, _ ->
                calls++
                Http.Page(200, url, desktop())
            }
        }
        assertEquals("wanted", result.id)
        assertEquals(1, calls)
    }

    @Test fun fallbackIsBoundedAndTransportErrorsAreSanitized() {
        var calls = 0
        val failure = assertFailsWith<XhsParser.PageFailure> {
            immediate {
                XhsParser.parseWithFetcher(url, ParseOptions()) { _, _ ->
                    calls++
                    error("https://private.test/?xsec_token=secret Cookie=private")
                }
            }
        }
        assertEquals(2, calls)
        assertEquals(XhsParser.FailureKind.REQUEST, failure.kind)
        assertFalse(failure.message.orEmpty().contains("private"))
        assertNull(failure.cause)
    }

    @Test fun specificRiskFailureSurvivesGenericFallbackFailure() {
        var calls = 0
        val failure = assertFailsWith<XhsParser.PageFailure> {
            immediate {
                XhsParser.parseWithFetcher(url, ParseOptions()) { _, _ ->
                    calls++
                    if (calls == 1) Http.Page(403, url, "") else Http.Page(200, url, "no state")
                }
            }
        }
        assertEquals(2, calls)
        assertEquals(XhsParser.FailureKind.RISK, failure.kind)
    }

    @Test fun cancellationPropagatesWithoutRetry() {
        var calls = 0
        val cancellation = CancellationException("fixture cancellation")
        val thrown = assertFailsWith<CancellationException> {
            immediate {
                XhsParser.parseWithFetcher(url, ParseOptions()) { _, _ ->
                    calls++
                    throw cancellation
                }
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(1, calls)
    }
}
