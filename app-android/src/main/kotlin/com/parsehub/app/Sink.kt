package com.parsehub.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.parsehub.FileSink
import com.parsehub.core.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import java.io.File

/**
 * Android 落点，两种模式：
 * 1. 默认：媒体库 Download/<子目录>/<模板目录>/（Android 10+ 无需存储权限）；
 * 2. 用户通过系统目录选择器指定的目录树（SAF），可直接写到任意位置（含 SD 卡）。
 * 两种模式都保证同名不覆盖。
 */
class AndroidSink(
    private val ctx: Context,
    private val configProvider: () -> AppConfig,
) : FileSink {

    private fun tree(): DocumentFile? {
        val raw = configProvider().treeUri
        if (raw.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(raw)) }.getOrNull()
    }

    private fun relPath(relDir: String): String {
        val root = configProvider().downloadRootDir.trim().trim('/')
        val mid = if (root.isEmpty()) "" else "$root/"
        return "Download/$mid$relDir/"
    }

    override suspend fun exists(relDir: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = tree()
        if (dir != null) {
            return@withContext runCatching { dir.findFile(relDir)?.findFile(fileName)?.isFile == true }
                .getOrDefault(false)
        }
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(relPath(relDir), fileName)
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                sel,
                args,
                null,
            )?.use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getInt(1) == 0) found = true
                }
                found
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun accept(tempPath: Path, relDir: String, fileName: String, mime: String) {
        withContext(Dispatchers.IO) {
            val dir = tree()
            if (dir != null) {
                writeToTree(dir, tempPath, relDir, fileName, mime)
            } else {
                writeToMediaStore(tempPath, relDir, fileName, mime)
            }
            FileSystem.SYSTEM.delete(tempPath, mustExist = false)
        }
    }

    private fun writeToTree(root: DocumentFile, tempPath: Path, relDir: String, fileName: String, mime: String) {
        val folder = root.findFile(relDir) ?: root.createDirectory(relDir)
            ?: throw RuntimeException("无法创建目录 $relDir")
        val target = folder.findFile(fileName) ?: folder.createFile(mime, fileName)
            ?: throw RuntimeException("无法创建文件 $fileName")
        ctx.contentResolver.openOutputStream(target.uri)?.use { os ->
            File(tempPath.toString()).inputStream().use { ins -> ins.copyTo(os, 256 * 1024) }
        } ?: throw RuntimeException("写入失败：$fileName")
    }

    private fun writeToMediaStore(tempPath: Path, relDir: String, fileName: String, mime: String) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(relDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("MediaStore 插入失败")
        try {
            File(tempPath.toString()).inputStream().use { ins ->
                resolver.openOutputStream(uri)?.use { os -> ins.copyTo(os, 256 * 1024) }
                    ?: throw RuntimeException("MediaStore 写入失败")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 供设置页展示当前保存位置 */
    fun describe(): String {
        val raw = configProvider().treeUri
        if (raw.isNotBlank()) {
            val name = Uri.parse(raw).lastPathSegment ?: raw
            return "已选目录：$name"
        }
        val root = configProvider().downloadRootDir.trim().trim('/')
        return "Download/" + if (root.isEmpty()) "" else "$root/"
    }
}

/** 打开系统目录选择器后可持久化的读/写权限掩码 */
const val SAF_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
