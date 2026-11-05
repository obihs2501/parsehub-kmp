package com.parsehub

import com.parsehub.core.*
import com.parsehub.parsers.DouyinParser
import kotlinx.serialization.json.*
import kotlin.test.*

class DouyinParserTest {
    private fun obj(text: String) = parseJson(text) as JsonObject
    private val detail = obj("""{
        "video": {
            "duration": 10000,
            "play_addr": {
                "uri": "https://preview.example.test/invalid",
                "width": 320, "height": 180, "data_size": 100,
                "url_list": ["https://media.example.test/preview"]
            },
            "bit_rate": [
                {"gear_name":"bad", "play_addr": {
                    "uri":"short", "url_list":["https://media.example.test/bad"]}},
                {"gear_name":"hd", "bit_rate":900000, "FPS":30, "play_addr": {
                    "uri":"valid_video_123", "width":1920, "height":1080, "data_size":900000,
                    "url_list":["https://media.example.test/hd"]}}
            ]
        }
    }""")

    @Test fun normalizesArrayDictionaryAndEncodedListsAndMetadata() {
        val meta = buildJsonObject {
            put("quality", "original")
            put("main_url", "https://media.example.test/video?a=1&text=\"quoted\"")
        }
        for (metadata in listOf(meta, JsonPrimitive(meta.toString()))) {
            val wrapped = buildJsonObject {
                put("quality", "preview")
                put("size", 42)
                put("video_meta", metadata)
            }
            val array = JsonArray(listOf(JsonNull, JsonPrimitive("ignored"), wrapped))
            val dictionary = buildJsonObject { put("video_1", wrapped) }
            for (list in listOf(array, dictionary, JsonPrimitive(array.toString()), JsonPrimitive(dictionary.toString()))) {
                val normalized = DouyinParser.normVideoList(list).single()
                assertEquals("original", normalized.str("quality"))
                assertEquals(42L, normalized.lng("size"))
                assertEquals(meta.str("main_url"), normalized.str("main_url"))
            }
        }
        assertTrue(DouyinParser.normVideoList(JsonPrimitive("{broken")).isEmpty())
        val broken = obj("""{"quality":"preview","video_meta":"{broken"}""")
        assertEquals(broken, DouyinParser.normVideoList(JsonArray(listOf(broken))).single())
    }

    @Test fun jingxuanAcceptsObjectAndStringModelsAndUnescapesFallbackUrls() {
        val expected = "https://media.example.test/fplay?a=1&text=\"quoted\""
        val escaped = JsonPrimitive(expected).toString().replace("/", "\\/")
        val primitive = parseJson(escaped)
        for (fallback in listOf(primitive, buildJsonObject { put("fallback_api", primitive) })) {
            val model = buildJsonObject { put("fallback_api", fallback) }
            for (value in listOf(model, JsonPrimitive(model.toString()))) {
                val result = buildJsonObject { put("play_count", 123); put("video_model", value) }
                assertEquals(DouyinParser.JxInfo(123, expected), DouyinParser.jingxuanInfo(result))
            }
        }
        assertEquals(DouyinParser.JxInfo(9), DouyinParser.jingxuanInfo(obj("""{"play_count":9,"video_model":"{broken"}""")))
        assertNull(DouyinParser.jingxuanInfo(obj("""{"video_model":{"fallback_api":null}}""")).fallbackApi)
    }

    @Test fun choosesLaterUsableUriEvenWhenPreviewAndFirstTierAreInvalid() {
        val tiers = DouyinParser.detailTiers(detail)
        assertEquals("valid_video_123", DouyinParser.originUri(detail, tiers))
        assertFalse(tiers.first().isOrigin)
        assertEquals("default", DouyinParser.originParams("valid_video_123")["ratio"])
        assertEquals("valid_video_123", DouyinParser.originParams("valid_video_123")["video_id"])
        assertEquals("", DouyinParser.originUri(obj("{}"), emptyList()))
        assertFalse(DouyinParser.isUsableOriginUri("https://media.example.test/preview"))
    }

    @Test fun fplayNormalizesMetadataAndKeepsExactTruthValuesAndPreferredUrls() {
        val metadata = obj("""{"quality":"original","size":12000,"vwidth":1280,"vheight":720,
            "float_fps":59.72,"fps":30,"bitrate":9600,"codec_type":"hevc",
            "main_url":"https://media.example.test/original",
            "backup_url_1":"https://media.example.test/original"}""")
        val list = buildJsonObject {
            put("video_1", buildJsonObject { put("video_meta", metadata.toString()) })
        }
        val response = buildJsonObject {
            put("video_info", buildJsonObject {
                put("data", buildJsonObject { put("video_list", list.toString()) })
            })
        }
        val real = assertNotNull(DouyinParser.fplayInfo(response))
        assertEquals(59.72, real.fps)
        assertEquals(12000L, real.size)
        assertEquals(1, real.urls.size)
        val tiers = DouyinParser.assembleTiers(detail, DouyinParser.detailTiers(detail), real,
            listOf("https://media.example.test/redirect") to 999L)
        val origin = tiers.first()
        assertTrue(origin.isOrigin)
        assertEquals(1280, origin.w)
        assertEquals(720, origin.h)
        assertEquals(12000L, origin.size)
        assertEquals(9600L, origin.bps)
        assertEquals(59.72, origin.fps)
        assertFalse(origin.fpsEst)
        assertTrue(origin.h265)
        assertTrue(origin.urls.single().startsWith(real.urls.single()))
        assertEquals(tiers.indices.toList(), tiers.map { it.idx })
    }

    @Test fun redirectOriginDoesNotBorrowPreviewFileSizeOrBitrate() {
        val raw = DouyinParser.detailTiers(detail)
        val origin = DouyinParser.assembleTiers(detail, raw, null,
            listOf("https://media.example.test/original") to 0L).first()
        assertTrue(origin.isOrigin)
        assertEquals(0L, origin.size)
        assertEquals(0L, origin.bps)
        assertEquals(30.0, origin.fps)
        assertTrue(origin.fpsEst)
        val measured = DouyinParser.assembleTiers(detail, raw, null,
            listOf("https://media.example.test/original") to 10000L).first()
        assertEquals(10000L, measured.size)
        assertEquals(8000L, measured.bps)
        assertTrue(DouyinParser.assembleTiers(detail, raw, null, emptyList<String>() to 0L).none { it.isOrigin })
    }

    @Test fun metadataOnlyFplayKeepsTruthValuesWithRedirectUrls() {
        val real = DouyinParser.OriginInfo(12000, 1280, 720, 59.72, 9600, "hevc", emptyList())
        val origin = DouyinParser.assembleTiers(detail, DouyinParser.detailTiers(detail), real,
            listOf("https://media.example.test/redirect") to 10000L).first()
        assertEquals(12000L, origin.size)
        assertEquals(9600L, origin.bps)
        assertEquals(59.72, origin.fps)
        assertFalse(origin.fpsEst)
        assertTrue(origin.h265)
        assertTrue(origin.urls.single().startsWith("https://media.example.test/redirect"))
    }

    @Test fun originCanExistWithoutAnyPreviewTierOrVideoId() {
        val real = DouyinParser.OriginInfo(100, 1280, 720, 25.0, 800, "h264",
            listOf("https://media.example.test/original"))
        val origin = DouyinParser.assembleTiers(obj("{}"), emptyList(), real, emptyList<String>() to 0L).single()
        assertTrue(origin.isOrigin)
        assertNull(origin.uri)
        assertFalse(origin.h265)
        assertFalse(origin.fpsEst)
    }
}
