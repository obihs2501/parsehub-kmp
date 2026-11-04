package io.github.obihs2501.parsehub.android

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import io.github.obihs2501.parsehub.FileSink
import okio.FileSystem
import okio.Path
import java.io.File

/** MediaStore 落点：写入 Download/<relDir>/<name>，Android 10+ 无需存储权限 */
class MediaStoreSink(private val ctx: Context, private val relDir: String) : FileSink {

    override suspend fun accept(tempPath: Path, fileName: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$relDir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("MediaStore 插入失败")
        try {
            File(tempPath.toString()).inputStream().use { ins ->
                resolver.openOutputStream(uri)?.use { os -> ins.copyTo(os, 256 * 1024) }
                    ?: throw RuntimeException("MediaStore 写入失败")
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            FileSystem.SYSTEM.delete(tempPath, mustExist = false)
        }
    }
}
