package io.github.obihs2501.parsehub.download
import okio.Buffer
import io.ktor.utils.io.readAvailable

import io.github.obihs2501.parsehub.core.nowMillis
import io.github.obihs2501.parsehub.net.Http
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.math.max
import kotlin.math.min

class CancelledException : Exception("已取消")

/**
 * 共享下载器（Android / 桌面同一套）：
 * - Range 断点续传（.part）
 * - ≥16MB 且支持 Range 时自动 4 路分片并行，任何异常回退单连接
 * - MP4 容器级校验（atom 结构 + moov）
 * - 多线路 × 2 轮重试
 */
object Downloader {
    private const val SEG_CONN = 4
    private const val SEG_MIN = 16L shl 20

    suspend fun download(
        urls: List<String>,
        outPath: Path,
        referer: String,
        onProgress: (done: Long, total: Long, speed: Long) -> Unit = { _, _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): Long {
        if (urls.isEmpty()) throw RuntimeException("没有可用直链")
        val fs = FileSystem.SYSTEM
        outPath.parent?.let { fs.createDirectories(it) }
        val tmp = (outPath.parent ?: outPath) / (outPath.name + ".part")
        val headers = mapOf("User-Agent" to Http.UA, "Referer" to referer, "Accept" to "*/*")

        // 分片快速路径：仅全新下载、探测到 ≥16MB 时启用
        if (!fs.exists(tmp)) {
            val (segUrl, total0) = probeSegments(urls, referer)
            if (segUrl != null && total0 >= SEG_MIN) {
                try {
                    segmentDownload(segUrl, total0, tmp, headers, onProgress, shouldCancel)
                    if (outPath.name.endsWith(".mp4") && !verifyMp4(tmp, total0)) {
                        throw RuntimeException("容器校验失败")
                    }
                    fs.delete(outPath, mustExist = false)
                    fs.atomicMove(tmp, outPath)
                    return fs.metadataOrNull(outPath)?.size ?: total0
                } catch (e: CancelledException) {
                    throw e
                } catch (_: Exception) {
                    fs.delete(tmp, mustExist = false)
                }
            }
        }

        var lastErr: Exception? = null
        for (round in 0 until 2) {
            for (url in urls) {
                try {
                    return singleRun(url, tmp, outPath, headers, onProgress, shouldCancel)
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
        }
        throw RuntimeException("所有线路均失败: ${lastErr?.message?.take(120)}")
    }

    private suspend fun probeSegments(urls: List<String>, referer: String): Pair<String?, Long> {
        for (u in urls) {
            try {
                val h = Http.getHeaders(
                    u, mapOf("User-Agent" to Http.UA, "Referer" to referer, "Range" to "bytes=0-0"),
                )
                val cr = h["Content-Range"] ?: ""
                if (cr.isNotEmpty()) {
                    val total = cr.substringAfterLast("/").trim().toLongOrNull()
                    if (total != null && total > 0) return u to total
                }
            } catch (_: Exception) {
            }
        }
        return null to 0L
    }

    private suspend fun singleRun(
        url: String,
        tmp: Path,
        outPath: Path,
        headers: Map<String, String>,
        onProgress: (Long, Long, Long) -> Unit,
        shouldCancel: () -> Boolean,
    ): Long {
        val fs = FileSystem.SYSTEM
        var done0 = fs.metadataOrNull(tmp)?.size ?: 0L
        val reqHeaders = headers.toMutableMap()
        if (done0 > 0) reqHeaders["Range"] = "bytes=$done0-"

        val resp = Http.client.get(url) { reqHeaders.forEach { (k, v) -> header(k, v) } }
        if (done0 > 0 && resp.status.value != 206) {
            fs.delete(tmp, mustExist = false)
            done0 = 0
        }
        if (resp.status.value >= 400) throw RuntimeException("HTTP ${resp.status.value}")

        val cl = resp.headers["Content-Length"]?.toLongOrNull() ?: 0L
        val total = if (done0 > 0) (if (cl > 0) cl + done0 else 0L) else cl
        var done = done0
        val t0 = nowMillis()
        var lastPush = t0

        val ch = resp.bodyAsChannel()
        (if (done0 > 0) fs.appendingSink(tmp) else fs.sink(tmp)).buffer().use { bw ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (shouldCancel()) throw CancelledException()
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n == 0) continue
                bw.write(buf, 0, n)
                bw.flush()
                done += n
                val now = nowMillis()
                if (now - lastPush >= 200) {
                    lastPush = now
                    val el = max(now - t0, 1) / 1000.0
                    onProgress(done, total, ((done - done0) / el).toLong())
                }
            }
        }
        if (total > 0 && done < total) {
            throw RuntimeException("下载不完整 ${done}/${total}")
        }
        if (outPath.name.endsWith(".mp4") && !verifyMp4(tmp, total.takeIf { it > 0 })) {
            fs.delete(tmp, mustExist = false)
            throw RuntimeException("容器校验失败")
        }
        fs.delete(outPath, mustExist = false)
        fs.atomicMove(tmp, outPath)
        onProgress(outPath.let { fs.metadataOrNull(it)?.size } ?: done, total, 0L)
        return fs.metadataOrNull(outPath)?.size ?: done
    }

    private suspend fun segmentDownload(
        url: String,
        total: Long,
        tmp: Path,
        headers: Map<String, String>,
        onProgress: (Long, Long, Long) -> Unit,
        shouldCancel: () -> Boolean,
    ) = coroutineScope {
        val fs = FileSystem.SYSTEM
        val n = SEG_CONN
        val part = (total + n - 1) / n
        var doneAgg = 0L          // 仅用于进度展示，允许近似
        val t0 = nowMillis()
        var lastPush = t0
        val segPaths = (0 until n).map { (tmp.parent ?: tmp) / (tmp.name + ".seg$it") }

        (0 until n).map { i ->
            async(Dispatchers.IO) {
                val start = i * part
                val end = min(start + part - 1, total - 1)
                val expected = end - start + 1
                val p = segPaths[i]
                val h = headers.toMutableMap()
                h["Range"] = "bytes=$start-$end"
                var lastErr: Exception? = null
                for (attempt in 0 until 2) {
                    var got = 0L
                    try {
                        val resp = Http.client.get(url) { h.forEach { (k, v) -> header(k, v) } }
                        if (resp.status.value != 206) {
                            throw RuntimeException("分片要求 206，实得 ${resp.status.value}")
                        }
                        val cr = resp.headers["Content-Range"] ?: ""
                        val m = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").find(cr)
                            ?: throw RuntimeException("分片响应缺少 Content-Range")
                        if (m.groupValues[1].toLong() != start || m.groupValues[2].toLong() != end) {
                            throw RuntimeException("分片范围不符")
                        }
                        if (m.groupValues[3] != "*" && m.groupValues[3].toLong() != total) {
                            throw RuntimeException("远端大小变化")
                        }
                        val ch = resp.bodyAsChannel()
                        fs.sink(p).buffer().use { bw ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (shouldCancel()) throw CancelledException()
                                val rn = ch.readAvailable(buf, 0, buf.size)
                                if (rn == -1) break
                                if (rn == 0) continue
                                bw.write(buf, 0, rn)
                                bw.flush()
                                got += rn
                                doneAgg += rn
                                val now = nowMillis()
                                if (now - lastPush >= 200) {
                                    lastPush = now
                                    val el = max(now - t0, 1) / 1000.0
                                    onProgress(doneAgg, total, (doneAgg / el).toLong())
                                }
                            }
                        }
                        if (got != expected) {
                            throw RuntimeException("分片不完整 $got/$expected")
                        }
                        return@async
                    } catch (e: CancelledException) {
                        throw e
                    } catch (e: Exception) {
                        lastErr = e
                        fs.delete(p, mustExist = false)
                        doneAgg -= got
                        if (attempt == 0) delay(1000)
                    }
                }
                throw RuntimeException("分片 $i 失败: ${lastErr?.message?.take(80)}")
            }
        }.awaitAll()

        fs.sink(tmp).buffer().use { target ->
            for (p in segPaths) {
                val sz = fs.metadataOrNull(p)?.size ?: 0L
                if (sz <= 0) throw RuntimeException("分片文件缺失: ${p.name}")
                fs.source(p).buffer().use { src -> src.readAll(target) }
                fs.delete(p, mustExist = false)
            }
        }
        val merged = fs.metadataOrNull(tmp)?.size ?: 0L
        if (merged != total) {
            fs.delete(tmp, mustExist = false)
            throw RuntimeException("合并后大小不符 $merged != $total")
        }
        onProgress(total, total, 0L)
    }

    /** MP4 容器粗校验：顶层 atom 结构完整且包含 moov */
    fun verifyMp4(path: Path, size: Long? = null): Boolean {
        return try {
            val fs = FileSystem.SYSTEM
            val total = fs.metadataOrNull(path)?.size ?: return false
            if (total < 16) return false
            if (size != null && size > 0 && total != size) return false
            var moov = false
            val discard = Buffer()
            fs.source(path).buffer().use { src ->
                var pos = 0L
                while (pos < total) {
                    if (total - pos < 8) return false
                    val hdr = ByteArray(8)
                    if (src.read(hdr) < 8) return false
                    var sz = ((hdr[0].toLong() and 0xFF) shl 24) or
                        ((hdr[1].toLong() and 0xFF) shl 16) or
                        ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
                    val type = hdr.decodeToString(4, 8)
                    if (sz == 1L) {
                        val ext = ByteArray(8)
                        if (src.read(ext) < 8) return false
                        sz = 0L
                        for (b in ext) sz = (sz shl 8) or (b.toLong() and 0xFF)
                    } else if (sz == 0L) {
                        sz = total - pos
                    }
                    if (sz < 8 || pos + sz > total) return false
                    if (type == "moov") moov = true
                    var skip = sz - 8
                    while (skip > 0) {
                        val chunk = src.read(discard, minOf(skip, 65536L))
                        if (chunk == -1L) return false
                        discard.clear()
                        skip -= chunk
                    }
                    pos += sz
                }
            }
            moov
        } catch (_: Exception) {
            false
        }
    }
}
