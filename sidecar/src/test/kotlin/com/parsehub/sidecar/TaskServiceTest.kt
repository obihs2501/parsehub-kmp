package com.parsehub.sidecar

import com.parsehub.core.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TaskServiceTest {
    @Test fun downloadsUniqueNamesPersistsHistoryAndCancelsWithoutSaving() = runBlocking {
        val root = Files.createTempDirectory("sidecar-tasks-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = "fixture image".toByteArray()
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/blocked") { exchange ->
            if (exchange.requestHeaders.getFirst("Range") != "bytes=0-0") {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val store = Store(root.resolve("store"), root.resolve("no-legacy"))
        store.update(store.settings.copy(outDir = root.resolve("output").toString(),
            config = store.settings.config.copy(folderTemplate = "{id}", fileTemplate = "{index}", mergeAuthorFolders = true)))
        var blocked = false
        val service = TaskService(store) { _, _ ->
            ParseResult(platform = Platform.DOUYIN, id = "123", title = "fixture", author = "author", date = "2026-01-01",
                durSec = 0.0, statLine = "", width = 0, height = 0, cover = null, referer = "",
                tiers = emptyList(), aux = emptyList(), images = listOf(ImageItem(0,
                    "http://127.0.0.1:${server.address.port}/${if (blocked) "blocked" else "file"}", "jpg", null, 1, 1)))
        }
        suspend fun start(): TaskState {
            val parsed = service.parse("https://www.douyin.com/video/123")
            return service.start(TaskRequest(parsed.getValue("resultId").jsonPrimitive.content))
        }
        suspend fun await(id: String): TaskState = withTimeout(15_000) {
            while (service.get(id).status in setOf("running", "cancelling")) delay(10)
            service.get(id)
        }
        try {
            val first = await(start().id)
            val second = await(start().id)
            assertEquals("done", first.status)
            assertEquals("done", second.status)
            assertNotEquals(first.files.single(), second.files.single())
            (first.files + second.files).forEach { assertContentEquals(bytes, Files.readAllBytes(java.nio.file.Path.of(it))) }
            blocked = true
            val third = start()
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals("cancelling", service.cancel(third.id).status)
            release.countDown()
            val cancelled = await(third.id)
            assertEquals("cancelled", cancelled.status)
            assertTrue(cancelled.files.isEmpty())
            val history = Store(store.root, root.resolve("no-legacy")).history.load().filter { it.kind == "download" }
            assertEquals(listOf("fail", "ok", "ok"), history.map { it.status })
            assertEquals("已取消", history.first().error)
            assertEquals(0, history.first().files)
            assertEquals(java.nio.file.Path.of(second.files.single()).fileName.toString(), decodeFileNames(history[1].fileNames).single())
            Files.list(store.root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("download-") }) }
        } finally {
            release.countDown()
            service.close()
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun migratesPlainTextLegacySettingsAndNeverReimportsAfterSettingsExist() {
        val root = Files.createTempDirectory("sidecar-migration-")
        try {
            val legacy = Files.createDirectory(root.resolve("legacy"))
            val output = root.resolve("输出 folder")
            Files.writeString(legacy.resolve("studio-settings.json"), output.toString())
            Files.writeString(legacy.resolve("config.json"), """{"theme":"dark"}""")
            Files.writeString(legacy.resolve("history.json"), """{"entries":[{"id":"legacy","title":"old"}]}""")
            val data = root.resolve("data")
            val store = Store(data, legacy)
            assertEquals(output.toString(), store.settings.outDir)
            assertEquals("dark", store.settings.config.theme)
            assertEquals("legacy", store.history.load().single().id)
            store.history.clear()
            assertTrue(Store(data, legacy).history.load().isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
}
