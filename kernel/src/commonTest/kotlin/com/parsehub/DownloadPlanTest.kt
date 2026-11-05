package com.parsehub

import com.parsehub.core.*
import kotlin.test.*

class DownloadPlanTest {
    private fun result() = ParseResult(
        platform = Platform.DOUYIN, id = "123", title = "标题", author = "账号", date = "2026-01-01",
        durSec = 0.0, statLine = "", width = 0, height = 0, cover = null, referer = "",
        tiers = listOf(Tier(0, "原画", 1920, 1080, 0, 0, false, 30.0, false, true, listOf("https://example.test/video"))),
        aux = emptyList(), images = listOf(ImageItem(0, "https://example.test/image", "jpg", "https://example.test/live", 10, 10)),
    )

    @Test fun plansImagesLiveAndSelectedVideo() {
        val jobs = DownloadPlan(AppConfig(), "归档作者", setOf(0), true, emptyList()).buildJobs(result())
        assertEquals(listOf("image", "live", "video"), jobs.map { it.kind })
        assertEquals(jobs.size, jobs.map { it.name }.distinct().size)
        assertEquals(1, jobs.map { it.relDir }.distinct().size)
    }

    @Test fun selectedImagesKeepOriginalIndexesAndLiveCompanions() {
        val album = result().copy(images = listOf(
            ImageItem(0, "https://example.test/first", "jpg", null, 10, 10),
            ImageItem(4, "https://example.test/fifth", "jpg", "https://example.test/fifth-live", 10, 10),
        ))
        val jobs = DownloadPlan(
            AppConfig(fileTemplate = "{type}_{index}"), "作者", emptySet(), true, emptyList(), setOf(4),
        ).buildJobs(album)
        assertEquals(listOf("image", "live"), jobs.map { it.kind })
        assertEquals(listOf("图片_5.jpg", "实况_5.mp4"), jobs.map { it.name })
        assertEquals(listOf(album.images[1].url, album.images[1].live), jobs.map { it.urls.single() })
        assertTrue(DownloadPlan(AppConfig(), "作者", emptySet(), true, emptyList(), emptySet()).buildJobs(album).isEmpty())
    }

    @Test fun aliasAndCreatorTemplateAreShared() {
        val config = AppConfig(folderTemplate = "{creator}", aliases = listOf(AuthorAlias("douyin", "id", "合并作者")))
        val creator = config.creatorOf("douyin", "id", "账号")
        assertEquals("合并作者", DownloadPlan(config, creator, emptySet(), true, emptyList()).folderName(result()))
    }

    @Test fun namesPreserveExtensionAndNeverOverwrite() {
        assertEquals("a_2.jpg", Naming.uniqueName("a.jpg") { it == "a.jpg" || it == "a_1.jpg" })
        assertFailsWith<IllegalStateException> { Naming.uniqueName("a.jpg") { true } }
        assertEquals("_CON", Naming.safeName("CON"))
    }
}
