package com.parsehub.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.parsehub.FileSink
import okio.FileSystem
import okio.Path
import java.io.File

/** 落点：Download/<relDir>/<name>，Android 10+ 无需存储权限；同名不覆盖 */
class MediaStoreSink(private val ctx: Context) : FileSink {

    private val resolver get() = ctx.contentResolver

    override suspend fun exists(relDir: String, fileName: String): Boolean {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf("Download/$relDir/", fileName)
        return try {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, sel, args, null)?.use { c ->
                var found = false
                while (c.moveToNext()) {
                    // 只有已落盘（非 pending）的才算占用名字
                    if (c.getInt(1) == 0) found = true
                }
                found
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun accept(tempPath: Path, relDir: String, fileName: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$relDir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
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
