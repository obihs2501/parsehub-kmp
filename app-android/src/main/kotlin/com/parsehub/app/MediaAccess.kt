package com.parsehub.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

/** 是否拥有「所有文件访问权限」；Android 10 及以下用旧存储权限即可 */
internal fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    true
}

/**
 * 是否已授予媒体读取权限。MediaStore 在无权限时返回的是「空游标」而不是抛异常，
 * 所以不能靠 query 的异常判断，必须主动检查，否则清数据后图库会一直空白且不弹授权。
 */
internal fun hasMediaReadPermission(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 打开系统「所有文件访问权限」设置页，让用户授权后返回即可刷新图库 */
internal fun requestAllFilesAccess(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * 把「下载记录」里的目录名解析成媒体库里的媒体项，供记录页直接点开预览。
 * relDir 是命名模板生成的子目录（如 抖音_作者__xxx），下载落点在
 * Download/<downloadRootDir>/<relDir>/，用 DISPLAY_NAME + RELATIVE_PATH 模糊匹配定位。
 */
internal fun resolveDownloadedMedia(
    ctx: Context,
    relDir: String,
    fileNames: List<String>,
): List<MediaItem> {
    val leaf = relDir.trim('/').substringAfterLast('/')
    if (leaf.isEmpty()) return emptyList()
    val wanted = fileNames.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    val out = mutableListOf<MediaItem>()

    fun collect(uri: Uri, video: Boolean) {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        ) + if (video) arrayOf(MediaStore.MediaColumns.DURATION) else emptyArray()
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%/$leaf/%")
        ctx.contentResolver.query(uri, cols, sel, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                if (wanted.isNotEmpty() && name !in wanted) continue
                out += MediaItem(
                    uri = ContentUris.withAppendedId(uri, c.getLong(0)),
                    name = name,
                    bucket = c.getString(2) ?: "",
                    isVideo = video,
                    size = c.getLong(3),
                    added = c.getLong(4) * 1000L,
                    width = c.getInt(5),
                    height = c.getInt(6),
                    durationMs = if (video) c.getLong(7) else 0L,
                )
            }
        }
    }
    runCatching { collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) }
    runCatching { collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) }
    return out.sortedByDescending { it.added }
}

/** 用系统播放器/看图应用打开一个媒体 URI（记录页「打开」按钮） */
internal fun openMedia(ctx: Context, item: MediaItem) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
