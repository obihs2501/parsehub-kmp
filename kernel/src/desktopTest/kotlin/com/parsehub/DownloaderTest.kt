package com.parsehub

import com.parsehub.download.CancelledException
import com.parsehub.download.Downloader
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DownloaderTest {
    @Test fun retriesBothRoundsAndPreservesPartialOnHttpFailure() = runBlocking {
        val directory = Files.createTempDirectory("kernel-download-")
        val calls = AtomicInteger()
        val payload = "deterministic payload".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            if (exchange.requestHeaders.getFirst("Range") != "bytes=4-") {
                exchange.sendResponseHeaders(400, -1)
            } else if (calls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                exchange.responseHeaders.add("Content-Range", "bytes 4-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, (payload.size - 4).toLong())
                exchange.responseBody.write(payload, 4, payload.size - 4)
            }
            exchange.close()
        }
        server.start()
        try {
            val target = directory.resolve("file.bin")
            Files.write(directory.resolve("file.bin.part"), payload.copyOf(4))
            assertEquals(payload.size.toLong(), Downloader.download(listOf("http://127.0.0.1:${server.address.port}/file"), target.toString().toPath(), ""))
            assertContentEquals(payload, Files.readAllBytes(target))
            assertEquals(2, calls.get())
            assertFalse(Files.exists(directory.resolve("file.bin.part")))
        } finally {
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun cancellationBeforeDownloadDoesNotConnectOrCreateFiles() = runBlocking {
        val directory = Files.createTempDirectory("kernel-cancel-")
        try {
            assertFailsWith<CancelledException> {
                Downloader.download(listOf("http://127.0.0.1:1/never"), directory.resolve("cancel.bin").toString().toPath(), "", shouldCancel = { true })
            }
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun acceptsExtendedSizeMp4AtomsAndRejectsInvalidHeaderSize() {
        val directory = Files.createTempDirectory("kernel-mp4-")
        try {
            val path = directory.resolve("extended.mp4")
            Files.write(path, ByteBuffer.allocate(24).putInt(1).put("moov".toByteArray()).putLong(16).putInt(8).put("free".toByteArray()).array())
            assertTrue(Downloader.verifyMp4(path.toString().toPath()))
            Files.write(path, ByteBuffer.allocate(16).putInt(1).put("moov".toByteArray()).putLong(8).array())
            assertFalse(Downloader.verifyMp4(path.toString().toPath()))
        } finally { directory.toFile().deleteRecursively() }
    }
}
