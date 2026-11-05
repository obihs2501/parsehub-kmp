package com.parsehub.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.parsehub.core.Fmt
import com.parsehub.core.dateTime
import com.parsehub.ui.EmptyHint
import com.parsehub.ui.InfoRow
import com.parsehub.ui.PageHero
import com.parsehub.ui.Reveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class MediaItem(
    val uri: Uri,
    val name: String,
    val bucket: String,
    val isVideo: Boolean,
    val size: Long,
    val added: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

private enum class MediaFilter(val label: String) { ALL("全部"), IMAGE("图片"), VIDEO("视频") }

private enum class SortMode(val label: String) {
    NEWEST("最新"),
    OLDEST("最旧"),
    NAME("名称"),
    SIZE("大小"),
}

/**
 * 本地图库：文件夹筛选 + 类型/排序/列数 + 全屏查看（左右滑、双指缩放、双击放大、视频内嵌播放）
 * + 分享 / 删除 / 详情。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryScreen(ctx: Context, onMessage: (String) -> Unit) {
    var all by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    var folder by remember { mutableStateOf("") }        // "" = 全部文件夹
    var filter by remember { mutableStateOf(MediaFilter.ALL) }
    var sort by remember { mutableStateOf(SortMode.NEWEST) }
    var columns by remember { mutableStateOf(3) }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    var showFolders by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatch by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            denied = false
            reload++
        }
    }

    LaunchedEffect(reload) {
        loading = true
        val res = withContext(Dispatchers.IO) { runCatching { queryMedia(ctx) } }
        all = res.getOrDefault(emptyList())
        denied = res.isFailure
        if (folder.isNotEmpty() && all.none { it.bucket == folder }) folder = ""
        loading = false
    }

    val folders = remember(all) { all.map { it.bucket }.distinct().sorted() }

    val shown = remember(all, folder, filter, sort, query) {
        var list = all
        if (folder.isNotEmpty()) list = list.filter { it.bucket == folder }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(q) }
        }
        list = when (filter) {
            MediaFilter.ALL -> list
            MediaFilter.IMAGE -> list.filter { !it.isVideo }
            MediaFilter.VIDEO -> list.filter { it.isVideo }
        }
        when (sort) {
            SortMode.NEWEST -> list.sortedByDescending { it.added }
            SortMode.OLDEST -> list.sortedBy { it.added }
            SortMode.NAME -> list.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> list.sortedByDescending { it.size }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PageHero("本地图库", "${shown.size} 项 · ${if (folder.isEmpty()) "全部文件夹" else folderLabel(folder)}")
            }
            TextButton(text = "刷新", onClick = { reload++ })
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索文件名",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${selected.size}",
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                TextButton(text = "全选", onClick = { selected = shown.map { it.uri.toString() }.toSet() })
                TextButton(text = "清空", onClick = { selected = emptySet() })
                TextButton(text = "分享", onClick = {
                    if (selected.isNotEmpty()) shareMediaList(ctx, shown.filter { selected.contains(it.uri.toString()) })
                })
                TextButton(text = "删除", onClick = { if (selected.isNotEmpty()) confirmBatch = true })
                TextButton(text = "取消", onClick = {
                    selecting = false
                    selected = emptySet()
                })
            }
        }

        // 文件夹 / 类型 / 排序 / 列数
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(text = if (folder.isEmpty()) "文件夹：全部" else "文件夹：${folderLabel(folder)}", onClick = { showFolders = true })
            MediaFilter.entries.forEach { f ->
                if (f == filter) Button(onClick = { filter = f }) { Text(f.label, style = MiuixTheme.textStyles.button) }
                else TextButton(text = f.label, onClick = { filter = f })
            }
            TextButton(text = "排序：${sort.label}", onClick = {
                sort = when (sort) {
                    SortMode.NEWEST -> SortMode.OLDEST
                    SortMode.OLDEST -> SortMode.NAME
                    SortMode.NAME -> SortMode.SIZE
                    SortMode.SIZE -> SortMode.NEWEST
                }
            })
            TextButton(text = "列数：$columns", onClick = { columns = if (columns >= 5) 2 else columns + 1 })
        }

        if (denied) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "需要媒体读取权限才能扫描图库（Android 13+ 需要「照片和视频」权限）",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Button(onClick = {
                        launcher.launch(
                            arrayOf(
                                "android.permission.READ_MEDIA_IMAGES",
                                "android.permission.READ_MEDIA_VIDEO",
                                "android.permission.READ_EXTERNAL_STORAGE",
                            ),
                        )
                    }) { Text("授权", style = MiuixTheme.textStyles.button) }
                }
            }
        } else if (loading) {
            Text(
                "正在扫描…",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else if (shown.isEmpty()) {
            EmptyHint("这里还没有媒体文件")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(shown) { item ->
                    val index = shown.indexOf(item)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(MiuixTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = {
                                    val key = item.uri.toString()
                                    if (selecting) {
                                        selected = if (selected.contains(key)) selected - key else selected + key
                                    } else {
                                        viewerAt = index
                                    }
                                },
                                onLongClick = {
                                    selecting = true
                                    selected = selected + item.uri.toString()
                                },
                            ),
                    ) {
                        if (item.isVideo) {
                            Text(
                                "▶ " + Fmt.dur(item.durationMs / 1000.0),
                                style = MiuixTheme.textStyles.footnote2,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (selected.contains(item.uri.toString())) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.28f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✓", style = MiuixTheme.textStyles.title1, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showFolders) {
        OverlayDialog(
            title = "选择文件夹",
            show = showFolders,
            onDismissRequest = { showFolders = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("全部文件夹", style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
                    TextButton(text = if (folder.isEmpty()) "已选" else "选择", onClick = {
                        folder = ""
                        showFolders = false
                    })
                }
                folders.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(folderLabel(f), style = MiuixTheme.textStyles.main)
                            Text(
                                "${all.count { it.bucket == f }} 项",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(text = if (folder == f) "已选" else "选择", onClick = {
                            folder = f
                            showFolders = false
                        })
                    }
                }
            }
        }
    }

    if (confirmBatch) {
        OverlayDialog(title = "删除选中的 ${selected.size} 个文件？", show = confirmBatch, onDismissRequest = { confirmBatch = false }) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    "自己下载的文件可以直接删除；其他应用管理的文件会被系统拒绝。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        confirmBatch = false
                        var ok = 0
                        shown.filter { selected.contains(it.uri.toString()) }.forEach {
                            if (deleteMedia(ctx, it)) ok++
                        }
                        onMessage("已删除 $ok / ${selected.size} 个文件")
                        selecting = false
                        selected = emptySet()
                        reload++
                    }) { Text("删除", style = MiuixTheme.textStyles.button) }
                    TextButton(text = "取消", onClick = { confirmBatch = false })
                }
            }
        }
    }

    val startIndex = viewerAt
    if (startIndex != null && shown.isNotEmpty()) {
        ViewerOverlay(
            ctx = ctx,
            items = shown,
            startIndex = startIndex.coerceIn(0, shown.size - 1),
            onClose = { viewerAt = null },
            onMessage = onMessage,
            onDeleted = { reload++ },
        )
    }
}

@Composable
private fun ViewerOverlay(
    ctx: Context,
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(items.getOrNull(startIndex) ?: items.first()) }

    LaunchedEffect(pagerState.currentPage) {
        items.getOrNull(pagerState.currentPage)?.let { current = it }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoBody(ctx, item)
            } else {
                ZoomableImage(item)
            }
        }

        // 顶栏
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(MiuixIcons.Regular.Close, contentDescription = "关闭")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showInfo = true }) {
                Icon(MiuixIcons.Regular.Info, contentDescription = "详情")
            }
            IconButton(onClick = { shareMedia(ctx, current) }) {
                Icon(MiuixIcons.Regular.Share, contentDescription = "分享")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(MiuixIcons.Regular.Delete, contentDescription = "删除")
            }
        }

        // 底栏计数 + 文件名
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) {
            Text(
                current.name,
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${pagerState.currentPage + 1} / ${items.size} · ${Fmt.size(current.size)} · ${dateTime(current.added)}",
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }

    if (showInfo) {
        OverlayDialog(title = "媒体详情", show = showInfo, onDismissRequest = { showInfo = false }) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                InfoRow("名称", current.name)
                InfoRow("大小", Fmt.size(current.size))
                InfoRow("时间", dateTime(current.added))
                InfoRow("类型", if (current.isVideo) "视频" else "图片")
                if (current.width > 0 && current.height > 0) {
                    InfoRow("尺寸", "${current.width} × ${current.height}")
                }
                if (current.isVideo) InfoRow("时长", Fmt.dur(current.durationMs / 1000.0))
                InfoRow("目录", current.bucket)
            }
        }
    }

    if (confirmDelete) {
        OverlayDialog(title = "删除这个文件？", show = confirmDelete, onDismissRequest = { confirmDelete = false }) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    current.name,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        confirmDelete = false
                        val ok = deleteMedia(ctx, current)
                        onMessage(if (ok) "已删除 ${current.name}" else "删除失败：该文件可能由其他应用管理")
                        if (ok) {
                            onDeleted()
                            if (items.size <= 1) onClose()
                        }
                    }) { Text("删除", style = MiuixTheme.textStyles.button) }
                    TextButton(text = "取消", onClick = { confirmDelete = false })
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
    var scale by remember(item.uri) { mutableStateOf(1f) }
    var panX by remember(item.uri) { mutableStateOf(0f) }
    var panY by remember(item.uri) { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            panX = 0f
                            panY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(item.uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1f) {
                        panX += pan.x
                        panY += pan.y
                    } else {
                        panX = 0f
                        panY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = panX,
                    translationY = panY,
                ),
        )
    }
}

@Composable
private fun VideoBody(ctx: Context, item: MediaItem) {
    var videoView by remember(item.uri) { mutableStateOf<VideoView?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoURI(item.uri)
                    setMediaController(MediaController(c).also { it.setAnchorView(this) })
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                    videoView = this
                }
            },
            modifier = Modifier.fillMaxSize().clickable {
                val v = videoView ?: return@clickable
                if (v.isPlaying) v.pause() else v.start()
            },
        )
    }
}

// ---------------------------------------------------------------- 数据与工具
private fun queryMedia(ctx: Context): List<MediaItem> {
    val out = mutableListOf<MediaItem>()

    val imageCols = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
    )
    val videoCols = imageCols + MediaStore.MediaColumns.DURATION

    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        imageCols,
        null,
        null,
        MediaStore.MediaColumns.DATE_MODIFIED + " DESC",
    )?.use { c ->
        while (c.moveToNext()) {
            out += MediaItem(
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                name = c.getString(1) ?: "",
                bucket = c.getString(2) ?: "",
                isVideo = false,
                size = c.getLong(3),
                added = c.getLong(4) * 1000L,
                width = c.getInt(5),
                height = c.getInt(6),
                durationMs = 0L,
            )
        }
    }
    ctx.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        videoCols,
        null,
        null,
        MediaStore.MediaColumns.DATE_MODIFIED + " DESC",
    )?.use { c ->
        while (c.moveToNext()) {
            out += MediaItem(
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                name = c.getString(1) ?: "",
                bucket = c.getString(2) ?: "",
                isVideo = true,
                size = c.getLong(3),
                added = c.getLong(4) * 1000L,
                width = c.getInt(5),
                height = c.getInt(6),
                durationMs = c.getLong(7),
            )
        }
    }
    return out
}

/** 目录名：取相对路径的最后一段（Download/抖音_某某/ -> 抖音_某某） */
private fun folderLabel(rel: String): String {
    val parts = rel.trim('/').split('/').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: rel
}

private fun shareMedia(ctx: Context, item: MediaItem) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享"))
    } catch (_: Exception) {
    }
}

private fun shareMediaList(ctx: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    try {
        val uris = ArrayList(items.map { it.uri })
        val anyVideo = items.any { it.isVideo }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (anyVideo) "video/*" else "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享 ${items.size} 个文件"))
    } catch (_: Exception) {
    }
}

private fun deleteMedia(ctx: Context, item: MediaItem): Boolean = try {
    // Download 目录里自己下载的文件可以直接删；其他应用管理的文件在 Android 11+ 需要用户授权，
    // 这里失败就明确告知，不做静默重试。
    ctx.contentResolver.delete(item.uri, null, null) > 0
} catch (_: Exception) {
    false
}
