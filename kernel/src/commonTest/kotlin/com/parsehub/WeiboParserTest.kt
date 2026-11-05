package com.parsehub

import com.parsehub.core.*
import com.parsehub.parsers.WeiboParser
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class WeiboParserTest {
    private fun format(json: String) = WeiboParser.formatStatus(parseJson(json) as JsonObject, "requested")

    @Test fun routesOrdinaryStatusUrlsByHostAndPath() {
        listOf(
            "https://weibo.com/123456/AbCd12345?from=share#comments",
            "https://m.weibo.cn/status/AbCd12345",
            "https://m.weibo.cn/detail/AbCd12345/",
            "https://www.weibo.com/status/AbCd12345",
        ).forEach { url ->
            val route = assertNotNull(WeiboParser.route(url), url)
            assertEquals("AbCd12345", route.id)
            assertFalse(route.isTv)
        }
        assertEquals("1234567890123456", WeiboParser.route("https://m.weibo.cn/detail/1234567890123456")?.id)
        listOf(
            "https://example.test/123/AbCd12345",
            "https://weibo.com.example.test/status/AbCd12345?fid=1034:123",
            "https://example.test/?next=https://weibo.com/status/AbCd12345",
            "https://weibo.com/u/123456789",
            "https://weibo.com/ttarticle/p/show?id=123456789",
            "https://weibo.com/status/AbCd12345/extra",
        ).forEach { assertNull(WeiboParser.route(it), it) }
    }

    @Test fun preservesTvRoutesAndFidPriority() {
        listOf(
            "https://weibo.com/tv/show/1034:123456789",
            "https://weibo.com/tv/v/1034:123456789",
            "https://video.weibo.com/show?fid=1034%3A123456789",
            "https://weibo.com/123/AbCd12345?fid=1034:123456789",
        ).forEach {
            val route = assertNotNull(WeiboParser.route(it))
            assertTrue(route.isTv)
            assertEquals("1034:123456789", route.id)
        }
    }

    @Test fun suppliesOnlyCallerCookie() {
        assertNull(WeiboParser.statusHeaders(ParseOptions())["Cookie"])
        val headers = WeiboParser.statusHeaders(ParseOptions(cookieWeibo = "synthetic=fixture"))
        assertEquals("synthetic=fixture", headers["Cookie"])
        assertEquals("https://weibo.com/", headers["Referer"])
    }

    @Test fun formatsPlaybackMetadataAndSkipsMissingOrDuplicateUrls() {
        val result = format("""{
          "idstr":"987654321", "text_raw":"示例正文 https://t.cn/fixture", "text":"wrong",
          "user":{"id":123,"screen_name":"示例作者"},
          "created_at":"Thu Nov 05 12:34:56 +0800 2026",
          "attitudes_count":3,"comments_count":2,"reposts_count":0,
          "page_info":{"object_type":"video","page_pic":"//media.example.test/cover.jpg",
            "short_url":"https://t.cn/fixture","media_info":{
              "mp4_hd_url":"//media.example.test/fallback.mp4",
              "playback_list":[{}, {"play_info":{"url":""}},
                {"play_info":{"url":"//media.example.test/a.mp4?fixture=1",
                  "width":1920,"height":1080,"duration":12.5,"bitrate":800000,"size":12345}},
                {"play_info":{"url":"https://media.example.test/a.mp4?fixture=1"}},
                {"play_info":{"url":"http://media.example.test/b.mp4","width":1280,"height":720}}]
            }}
        }""")
        assertEquals("987654321", result.id)
        assertEquals("示例正文", result.title)
        assertEquals("示例作者", result.author)
        assertEquals("123", result.authorId)
        assertEquals("2026-11-05", result.date)
        assertEquals("赞 3 / 评 2 / 转 0", result.statLine)
        assertEquals(12.5, result.durSec)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertEquals("https://media.example.test/cover.jpg", result.cover)
        assertEquals(listOf(0, 1), result.tiers.map { it.idx })
        assertEquals(800000L, result.tiers[0].bps)
        assertEquals(12345L, result.tiers[0].size)
        assertEquals("https://media.example.test/a.mp4?fixture=1&mime_type=video_mp4", result.tiers[0].urls.single())
        assertEquals("https://media.example.test/b.mp4?mime_type=video_mp4", result.tiers[1].urls.single())
        assertTrue(result.images.isEmpty())
    }

    @Test fun fallsBackToHdThenSdWhenPlaybackHasNoUsableUrl() {
        val hd = format("""{"page_info":{"media_info":{"duration":7,
          "playback_list":[{"play_info":{}}],
          "mp4_hd_url":"//media.example.test/hd.mp4", "mp4_sd_url":"//media.example.test/sd.mp4"}}}""")
        assertEquals("https://media.example.test/hd.mp4?mime_type=video_mp4", hd.tiers.single().urls.single())
        assertEquals(7.0, hd.durSec)
        val sd = format("""{"page_info":{"media_info":{"mp4_hd_url":null,
          "mp4_sd_url":"http://media.example.test/sd.mp4?mime_type=video_mp4"}}}""")
        assertEquals("https://media.example.test/sd.mp4?mime_type=video_mp4", sd.tiers.single().urls.single())
        assertEquals("requested", sd.id)
        assertEquals("0000-00-00", sd.date)
    }

    @Test fun formatsOrderedLargestImagesAndLiveCompanions() {
        val result = format("""{"text":"<b>图集</b>&nbsp;&amp; 示例", "created_at":"2026-11-05",
          "pic_ids":["second","missing","first"],"pic_infos":{
            "first":{"largest":{"url":"//media.example.test/first.jpg","width":100,"height":200}},
            "second":{"largest":{"url":"http://media.example.test/second.PNG?fixture=1","width":300,"height":400},
              "thumbnail":{"url":"//media.example.test/thumb.jpg"},"video":"//media.example.test/live.mov"}
          }}""")
        assertTrue(result.tiers.isEmpty())
        assertEquals(listOf(0, 2), result.images.map { it.index })
        assertEquals(listOf("png", "jpg"), result.images.map { it.ext })
        assertEquals("https://media.example.test/second.PNG?fixture=1", result.images[0].url)
        assertEquals("https://media.example.test/live.mov", result.images[0].live)
        assertEquals(300, result.images[0].w)
        assertEquals(400, result.images[0].h)
        assertEquals(result.images[0].url, result.cover)
        assertEquals("图集  & 示例", result.title)
    }

    @Test fun prefersRetweetedImagesAndKeepsOuterPostMetadata() {
        val result = format("""{"text_raw":"转发正文", "pic_infos":{
          "outer":{"largest":{"url":"//media.example.test/outer.jpg"}}},
          "retweeted_status":{"pic_infos":{
            "inner":{"largest":{"url":"//media.example.test/inner.jpg"}}}}}""")
        assertEquals("转发正文", result.title)
        assertEquals("https://media.example.test/inner.jpg", result.images.single().url)
    }

    @Test fun rejectsApiErrorsAndPostsWithoutDownloadableMedia() {
        listOf("{}", """{"ok":0,"msg":"synthetic error"}""",
            """{"page_info":{"media_info":{"playback_list":[{"play_info":{}}]}}}""")
            .forEach { assertFailsWith<RuntimeException> { format(it) } }
    }
}
