package com.parsehub.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.parsehub.ui.parsedImageRequest
import com.parsehub.ui.LocalPageBottomPadding
import com.parsehub.core.AppConfig
import com.parsehub.core.Fmt
import com.parsehub.core.dateTime
import com.parsehub.ui.EmptyHint
import com.parsehub.ui.HairLine
import com.parsehub.ui.InfoRow
import com.parsehub.ui.PageHero
import com.parsehub.ui.Reveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.foundation.lazy.grid.itemsIndexed
import coil3.request.ImageRequest
import androidx.compose.animation.scaleOut

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

private enum class MediaFilter(val key: String, val label: String) {
    ALL("all", "全部"),
    IMAGE("image", "图片"),
    VIDEO("video", "视频"),
}

private enum class SortMode(val key: String, val label: String) {
    NEWEST("newest", "最新"),
    OLDEST("oldest", "最旧"),
    NAME("name", "名称"),
    SIZE("size", "大小"),
}

/**
 * 本地图库（目录层级浏览）：
 * 先看文件夹，逐级进入，文件夹与本层文件分区展示；支持搜索（跨整棵树）、类型/排序/列数、
 * 长按多选批量分享与删除，以及全屏查看（左右滑、缩放、视频内嵌播放、看图时隐藏底栏）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryScreen(
    ctx: Context,
    config: AppConfig,
    onConfig: (AppConfig) -> Unit,
    onMessage: (String) -> Unit,
    onImmersive: (Boolean) -> Unit,
) {
    var all by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(!hasMediaReadPermission(ctx)) }
    var needAllFiles by remember { mutableStateOf(!hasAllFilesAccess()) }
    var reload by remember { mutableStateOf(0) }

    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember {
        mutableStateOf(MediaFilter.entries.firstOrNull { it.key == config.galleryFilter } ?: MediaFilter.ALL)
    }
    var sort by remember {
        mutableStateOf(SortMode.entries.firstOrNull { it.key == config.gallerySort } ?: SortMode.NEWEST)
    }
    var columns by remember { mutableStateOf(config.galleryColumns.coerceIn(2, 6)) }
    var visibleCount by remember { mutableStateOf(90) }
    var query by remember { mutableStateOf("") }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatch by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(config.galleryOpenPanel) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            denied = false
            reload++
        }
    }

    // 从「所有文件访问权限」设置页返回时（onResume）自动重扫
    var resumeTick by remember { mutableIntStateOf(0) }
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return@DisposableEffect onDispose {}
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(reload, resumeTick) {
        loading = true
        val granted = hasMediaReadPermission(ctx)
        denied = !granted
        needAllFiles = !hasAllFilesAccess()
        if (granted) {
            val res = withContext(Dispatchers.IO) { runCatching { queryMedia(ctx) } }
            all = res.getOrDefault(emptyList())
        } else {
            all = emptyList()
        }
        loading = false
    }

    // 视频首帧缩略图（MediaStore 提供），异步取，避免视频所在目录整片空白
    val videoThumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(all) {
        val missing = all.filter { it.isVideo && !videoThumbs.containsKey(it.uri.toString()) }.take(200)
        if (missing.isEmpty()) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            buildMap {
                missing.forEach { item ->
                    runCatching {
                        val bmp = MediaStore.Video.Thumbnails.getThumbnail(
                            ctx.contentResolver,
                            ContentUris.parseId(item.uri),
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null,
                        )
                        if (bmp != null) put(item.uri.toString(), bmp.asImageBitmap())
                    }
                }
            }
        }
        videoThumbs.putAll(decoded)
    }

    // 系统返回键：先退出查看器，再退出多选，然后逐级回上级目录
    BackHandler(enabled = viewerAt != null || selecting || stack.isNotEmpty()) {
        when {
            viewerAt != null -> viewerAt = null
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            else -> stack = stack.dropLast(1)
        }
    }

    val prefix = if (stack.isEmpty()) "" else stack.joinToString("/") + "/"
    val searching = query.isNotBlank()

    // 当前层级的子文件夹（名称 -> 项数）
    val childFolders = remember(all, prefix, searching) {
        if (searching) return@remember emptyMap<String, Int>()
        val map = LinkedHashMap<String, Int>()
        for (m in all) {
            if (!m.bucket.startsWith(prefix)) continue
            val rest = m.bucket.substring(prefix.length).trim('/')
            if (rest.isEmpty()) continue
            val seg = rest.substringBefore('/')
            map[seg] = (map[seg] ?: 0) + 1
        }
        map.toList().sortedBy { it.first }.toMap()
    }

    val levelFiles = remember(all, prefix) {
        all.filter { it.bucket.trim('/') + "/" == prefix }
    }

    val shown = remember(all, levelFiles, filter, sort, query, stack) {
        var list = if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            all.filter { it.name.lowercase().contains(q) }
        } else {
            levelFiles
        }
        list = when (filter) {
            MediaFilter.ALL -> list
            MediaFilter.IMAGE -> list.filter { !it.isVideo }
            MediaFilter.VIDEO -> list.filter { it.isVideo }
        }
        when (sort) {
            SortMode.NEWEST -> list.sortedByDescending { it.added }
            SortMode.OLDEST -> list.sortedBy { it.added }
            SortMode.NAME -> list.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            SortMode.SIZE -> list.sortedByDescending { it.size }
        }
    }

    // 换目录 / 改搜索 / 换排序时回到第一批
    LaunchedEffect(prefix, query, sort, filter) { visibleCount = 90 }

    // 向下滚动时整个头部（标题 / 面包屑 / 搜索筛选 / 多选条）一起隐藏，
    // 回到顶部再出现。头部不再固定占近半屏，滚起来网格占满全屏。
    var scrollY by remember { mutableFloatStateOf(0f) }
    val headerHidden by remember { derivedStateOf { scrollY > 24f } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 整个头部随滚动收起/展开（淡出 + 高度收拢），不再固定占近半屏
        AnimatedVisibility(
            visible = !headerHidden,
            enter = fadeIn(animationSpec = tween(180)) +
                expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(animationSpec = tween(140)) +
                shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PageHero(
                    "本地图库",
                    if (searching) {
                        "搜索「${query.trim()}」· ${shown.size} 项"
                    } else {
                        "${childFolders.size} 个文件夹 · ${levelFiles.size} 个文件 · " + crumbLabel(stack)
                    },
                )
            }
            TextButton(text = "刷新", onClick = { reload++ })
        }

        // 面包屑：点任意一级回跳
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stack.isNotEmpty()) {
                TextButton(text = "‹ 上一级", onClick = { stack = stack.dropLast(1) })
            }
            TextButton(text = "全部", onClick = { stack = emptyList() })
            stack.forEachIndexed { i, seg ->
                Text(
                    "/",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(text = seg, onClick = { stack = stack.take(i + 1) })
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallTitle(text = "搜索与筛选")
            Spacer(Modifier.weight(1f))
            TextButton(
                text = if (panelOpen) "收起" else "展开",
                onClick = {
                    val next = !panelOpen
                    panelOpen = next
                    onConfig(config.copy(galleryOpenPanel = next))
                },
            )
        }
        Reveal(panelOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索文件名（跨所有文件夹）",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaFilter.entries.forEach { f ->
                        if (f == filter) {
                            Button(onClick = { filter = f; onConfig(config.copy(galleryFilter = f.key)) }) {
                                Text(f.label, style = MiuixTheme.textStyles.button)
                            }
                        } else {
                            TextButton(text = f.label, onClick = { filter = f; onConfig(config.copy(galleryFilter = f.key)) })
                        }
                    }
                    TextButton(text = "排序：${sort.label}", onClick = {
                        val next = when (sort) {
                            SortMode.NEWEST -> SortMode.OLDEST
                            SortMode.OLDEST -> SortMode.NAME
                            SortMode.NAME -> SortMode.SIZE
                            SortMode.SIZE -> SortMode.NEWEST
                        }
                        sort = next
                        onConfig(config.copy(gallerySort = next.key))
                    })
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "列数",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    listOf(2, 3, 4, 5, 6).forEach { n ->
                        if (n == columns) {
                            Button(onClick = { columns = n; onConfig(config.copy(galleryColumns = n)) }) {
                                Text("$n", style = MiuixTheme.textStyles.button)
                            }
                        } else {
                            TextButton(text = "$n", onClick = { columns = n; onConfig(config.copy(galleryColumns = n)) })
                        }
                    }
                }
            }
        }

        Reveal(selecting) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selected.size}", style = MiuixTheme.textStyles.main)
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
            }   // Column (header content)
        }       // AnimatedVisibility (整个头部随滚动隐藏)

        // 权限分两级：媒体读取权限 → 读媒体库；所有文件访问 → 读其它应用写入的文件。
        // 缺「所有文件访问」时仍照常显示已能读到的文件，只是给一条可点的提示条。
        if (needAllFiles && !denied) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "开启「所有文件访问权限」可浏览全部文件夹里的媒体",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        Text(
                            "不开启也能看本应用自己保存的文件，返回会自动刷新",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(text = "去开启", onClick = { requestAllFilesAccess(ctx) })
                }
            }
        }

        if (denied) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "需要媒体读取权限才能扫描图库（Android 13+ 需要「照片和视频」权限）",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Button(onClick = { launcher.launch(mediaPermissions()) }) {
                        Text("授权", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        } else if (loading) {
            Text(
                "正在扫描…",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else if (childFolders.isEmpty() && shown.isEmpty()) {
            EmptyHint(if (searching) "没有匹配的文件" else "这个文件夹是空的")
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (available.y != 0f) scrollY = (scrollY - available.y).coerceAtLeast(0f)
                                return Offset.Zero
                            }
                        }
                    }),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp + LocalPageBottomPadding.current),
            ) {
                if (childFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SmallTitle(text = "文件夹")
                    }
                    for ((name, count) in childFolders) {
                        item(key = "dir:$name") {
                            FolderCard(name = name, count = count) {
                                stack = stack + name
                                selected = emptySet()
                                selecting = false
                            }
                        }
                    }
                }
                if (shown.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { HairLine() }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SmallTitle(text = if (searching) "搜索结果" else "文件")
                            Spacer(Modifier.weight(1f))
                            if (shown.size > visibleCount) {
                                TextButton(
                                    text = "加载更多（剩 ${shown.size - visibleCount}）",
                                    onClick = { visibleCount += 90 },
                                )
                            }
                        }
                    }
                    itemsIndexed(shown.take(visibleCount), key = { _, item -> item.uri.toString() }) { index, item ->
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
                                    val bmp = videoThumbs[item.uri.toString()]
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = item.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Box(
                                            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "视频",
                                                style = MiuixTheme.textStyles.footnote2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    }
                                } else {
                                    AsyncImage(
                                        model = ImageRequest.Builder(ctx)
                                            .data(item.uri)
                                            .size(360, 360)
                                            .build(),
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (item.isVideo) {
                                    Text(
                                        "▶ " + Fmt.dur(item.durationMs / 1000.0),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                    )
                                }
                                if (searching) {
                                    Text(
                                        folderLabel(item.bucket),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                    )
                                }
                                if (selected.contains(item.uri.toString())) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.30f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("✓", style = MiuixTheme.textStyles.title1, color = Color.White)
                                    }
                                }
                            }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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

    // 看图层的进入/退出用淡入淡出 + 轻微缩放，和 Miuix 页面过渡保持连续
    AnimatedVisibility(
        visible = viewerAt != null && shown.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260)),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)),
        label = "viewer",
    ) {
        val at = viewerAt
        if (at != null && shown.isNotEmpty()) {
            ViewerOverlay(
                ctx = ctx,
                items = shown,
                startIndex = at.coerceIn(0, shown.size - 1),
                onClose = { viewerAt = null },
                onMessage = onMessage,
                onDeleted = { reload++ },
                onImmersive = onImmersive,
            )
        }
    }
}

/** 文件夹卡片：名称 + 项数，点击进入下一级 */
@Composable
private fun FolderCard(name: String, count: Int, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().height(112.dp).clickable { onOpen() },
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(MiuixIcons.Regular.Folder, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$count 项",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
    }
}

/** 记录页等外部页面复用同一个看图覆盖层 */
@Composable
internal fun MediaViewer(
    ctx: Context,
    items: List<MediaItem>,
    startIndex: Int = 0,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    onImmersive: (Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    ViewerOverlay(
        ctx = ctx,
        items = items,
        startIndex = startIndex.coerceIn(0, items.size - 1),
        onClose = onClose,
        onMessage = onMessage,
        onDeleted = {},
        onImmersive = onImmersive,
    )
}

@Composable
private fun ViewerOverlay(
    ctx: Context,
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    onDeleted: () -> Unit,
    onImmersive: (Boolean) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(items.getOrNull(startIndex) ?: items.first()) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }

    // 返回键 / 手势返回先关闭看图（视频不再吞事件，这里兜底保证一定能退）
    BackHandler(enabled = true) { onClose() }

    DisposableEffect(Unit) {
        onImmersive(true)
        onDispose { onImmersive(false) }
    }
    LaunchedEffect(pagerState.currentPage) {
        items.getOrNull(pagerState.currentPage)?.let { current = it }
        zoomed = false
    }
    // 进入看图时先显示，3 秒后自动隐藏；之后由单击画面决定显示/隐藏
    LaunchedEffect(Unit) {
        delay(3000)
        chrome = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoBody(item, onTap = { chrome = !chrome })
            } else {
                ZoomableImage(item, onTap = { chrome = !chrome }, onZoomChange = { zoomed = it })
            }
        }

        // 顶部渐变遮罩：保证状态栏时间和图标在任何图片上都看得清
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent),
                    ),
                ),
        )

        // 顶栏与文件名合并成一条
        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
            enter = fadeIn(animationSpec = tween(160)) + slideInVertically(animationSpec = tween(180)) { -it / 3 },
            exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(animationSpec = tween(180)) { -it / 3 },
        ) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                insideMargin = PaddingValues(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(MiuixIcons.Regular.Close, contentDescription = "关闭")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            current.name,
                            style = MiuixTheme.textStyles.body2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pagerState.currentPage + 1}/${items.size} · " + Fmt.size(current.size) +
                                if (current.width > 0) " · ${current.width}×${current.height}" else "",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
            }
        }
    }

    if (showInfo) {
        OverlayDialog(title = "媒体详情", show = showInfo, onDismissRequest = { showInfo = false }) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                InfoRow("名称", current.name)
                InfoRow("大小", Fmt.size(current.size))
                InfoRow("时间", dateTime(current.added))
                InfoRow("类型", if (current.isVideo) "视频" else "图片")
                if (current.width > 0 && current.height > 0) InfoRow("尺寸", "${current.width} × ${current.height}")
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

/**
 * 单张图片：使用 Telephoto 的 zoomable（惯性甩动、橡皮筋回弹、以手指为锚点的平滑缩放）。
 * 未放大时禁用单指拖拽，把横滑让给外层 Pager 翻页；放大后自动接管拖拽。
 */
@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    ZoomableImageBody(item.uri, item.name, onTap, onZoomChange)
}

/** Shared zoom/pan/double-tap primitive for local media and parsed remote images. */
@Composable
private fun ZoomableImageBody(
    model: Any?,
    description: String,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    val state = rememberZoomableState(ZoomSpec(maxZoomFactor = 6f))
    val zoomed = (state.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoomChange(zoomed) }

    AsyncImage(
        model = model,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .zoomable(
                state = state,
                gestures = EnabledZoomGestures(zoom = true, pan = zoomed),
                onClick = { onTap() },
            ),
    )
}

/** Parsed remote images do not offer local-media delete/share actions. */
@Composable
internal fun ParsedImageViewer(
    urls: List<String>,
    startIndex: Int,
    referer: String,
    onClose: () -> Unit,
    onImmersive: (Boolean) -> Unit,
) {
    if (urls.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(urls.indices)) { urls.size }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler { onClose() }
    DisposableEffect(Unit) {
        onImmersive(true)
        onDispose { onImmersive(false) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = zoomedPages[pager.currentPage] != true,
        ) { index ->
            ZoomableImageBody(
                model = parsedImageRequest(urls[index], referer),
                description = "第 ${index + 1} 张，共 ${urls.size} 张",
                onTap = {},
                onZoomChange = { zoomedPages[index] = it },
            )
            DisposableEffect(index) {
                onDispose { zoomedPages.remove(index) }
            }
        }
        Card(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(MiuixIcons.Regular.Close, contentDescription = "关闭大图")
                }
                Text("${pager.currentPage + 1} / ${urls.size}", style = MiuixTheme.textStyles.body2)
            }
        }
        if (urls.size > 1) {
            Card(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(text = "上一张", enabled = pager.currentPage > 0, onClick = {
                        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                    })
                    TextButton(text = "下一张", enabled = pager.currentPage < urls.lastIndex, onClick = {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    })
                }
            }
        }
    }
}

@Composable
private fun VideoBody(item: MediaItem, onTap: () -> Unit) {
    var videoView by remember(item.uri) { mutableStateOf<VideoView?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoURI(item.uri)
                    // 不挂 MediaController：它会先吞掉返回键用来隐藏原生控制条，
                    // 导致 Compose 的 BackHandler 收不到返回事件、看图界面退不出去
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                    videoView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.uri) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            val v = videoView
                            if (v != null) {
                                if (v.isPlaying) v.pause() else v.start()
                            }
                        },
                    )
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

private fun folderLabel(rel: String): String {
    val parts = rel.trim('/').split('/').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: rel
}

private fun crumbLabel(stack: List<String>): String =
    if (stack.isEmpty()) "全部位置" else stack.joinToString(" / ")

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
    ctx.contentResolver.delete(item.uri, null, null) > 0
} catch (_: Exception) {
    false
}

/** 数字感知排序：img2 排在 img10 前面 */
private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var x = 0L
            while (i < a.length && a[i].isDigit()) {
                x = x * 10 + (a[i] - '0')
                i++
            }
            var y = 0L
            while (j < b.length && b[j].isDigit()) {
                y = y * 10 + (b[j] - '0')
                j++
            }
            if (x != y) return if (x < y) -1 else 1
        } else {
            val la = ca.lowercaseChar()
            val lb = cb.lowercaseChar()
            if (la != lb) return if (la < lb) -1 else 1
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

/** 相对时间：今天显示 HH:mm，今年显示 MM-DD，更早显示 yyyy-MM-dd */
private fun relativeDate(ts: Long): String {
    val full = dateTime(ts)
    if (full.isEmpty()) return ""
    val day = full.substring(0, 10)
    val today = com.parsehub.core.today()
    return when {
        day == today -> "今天 " + full.substringAfter(' ', "")
        day.startsWith(today.substring(0, 4)) -> full.substring(5)
        else -> day
    }
}
