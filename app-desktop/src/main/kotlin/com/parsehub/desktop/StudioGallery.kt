package com.parsehub.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.parsehub.core.Fmt
import com.parsehub.core.dateTime
import com.parsehub.ui.EmptyHint
import com.parsehub.ui.HairLine
import com.parsehub.ui.Reveal
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
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.awt.Desktop
import java.io.File
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter(val key: String, val label: String) { ALL("all", "全部"), IMAGE("image", "图片"), VIDEO("video", "视频") }
private enum class Sort(val key: String, val label: String) {
    NEWEST("newest", "最新"), OLDEST("oldest", "最早"), NAME("name", "名称"), SIZE("size", "大小")
}

/**
 * 桌面图库，布局与交互按 x-spider 的 Gallery / MediaLightbox 复刻：
 * 左侧文件夹树、右侧面包屑 + 筛选工具栏、子文件夹卡片、网格/列表切换、
 * 全屏看图窗口（←/→ 切换、Esc 关闭、滚轮缩放、双击复位）。
 */
@Composable
internal fun StudioGallery(
    root: File,
    store: DesktopStore,
    thumbCache: MutableMap<String, ImageBitmap>,
    onMessage: (String) -> Unit,
) {
    var prefs by remember { mutableStateOf(store.loadGallery()) }
    fun persist(next: DesktopStore.GalleryPrefs) {
        prefs = next
        store.saveGallery(next)
    }

    var files by remember { mutableStateOf<List<LocalFile>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    var sidebar by remember { mutableStateOf(prefs.sidebar) }

    LaunchedEffect(root, reload) {
        scanning = true
        files = withContext(Dispatchers.IO) { scanLocal(root) }
        scanning = false
    }

    // 缩略图在后台解码，跨 tab 复用缓存
    LaunchedEffect(files) {
        val missing = files.filter { !it.isVideo && !thumbCache.containsKey(it.file.path) }.take(300)
        if (missing.isEmpty()) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            buildMap { missing.forEach { f -> loadThumb(f.file)?.let { put(f.file.path, it) } } }
        }
        thumbCache.putAll(decoded)
    }

    val prefix = stack.fold(root.absolutePath) { acc, s -> File(acc, s).absolutePath }
    val childFolders = remember(files, prefix) {
        val map = LinkedHashMap<String, Int>()
        files.forEach { f ->
            val parent = f.file.parentFile?.absolutePath ?: return@forEach
            if (!parent.startsWith(prefix)) return@forEach
            val rest = parent.removePrefix(prefix).trim('/', '\\')
            if (rest.isEmpty()) return@forEach
            val seg = rest.substringBefore('/').substringBefore('\\')
            map[seg] = (map[seg] ?: 0) + 1
        }
        map.toList().sortedBy { it.first }.toMap()
    }
    val levelFiles = remember(files, prefix) {
        files.filter { (it.file.parentFile?.absolutePath ?: "") == prefix }
    }
    val searching = query.isNotBlank()
    val shown = remember(levelFiles, files, prefs.filter, prefs.sort, query, searching) {
        var list = if (searching) {
            val q = query.trim().lowercase()
            files.map { it.file }.filter { it.name.lowercase().contains(q) }
        } else levelFiles.map { it.file }
        list = when (prefs.filter) {
            Filter.ALL.key -> list
            Filter.IMAGE.key -> list.filter { it.extension.lowercase() in IMAGE_EXT }
            else -> list.filter { it.extension.lowercase() in VIDEO_EXT }
        }
        when (prefs.sort) {
            Sort.OLDEST.key -> list.sortedBy { it.lastModified() }
            Sort.NAME.key -> list.sortedBy { it.name.lowercase() }
            Sort.SIZE.key -> list.sortedByDescending { it.length() }
            else -> list.sortedByDescending { it.lastModified() }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ---- 左侧文件夹树（x-spider FolderSidebar）----
        AnimatedVisibility(visible = sidebar) {
            Column(
                Modifier
                    .width(230.dp)
                    .fillMaxHeight()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文件夹", style = MiuixTheme.textStyles.title3, modifier = Modifier.weight(1f))
                    IconButton(onClick = { reload++ }) {
                        Icon(MiuixIcons.Regular.Refresh, contentDescription = "刷新")
                    }
                }
                Spacer(Modifier.height(8.dp))
                FolderSidebarItem("全部", childFolders.isEmpty() && stack.isEmpty(), stack.isEmpty()) { stack = emptyList() }
                childFolders.forEach { (name, count) ->
                    FolderSidebarItem(name, false, stack.firstOrNull() == name, count) {
                        stack = listOf(name)
                    }
                }
                if (stack.size > 1) {
                    HairLine()
                    stack.drop(1).forEachIndexed { i, seg ->
                        FolderSidebarItem(seg, false, i == stack.size - 2, null) {
                            stack = stack.take(i + 2)
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
            // ---- 面包屑 + 路径 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = if (sidebar) "‹ 收起目录" else "› 展开目录", onClick = {
                    sidebar = !sidebar
                    persist(prefs.copy(sidebar = sidebar))
                })
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(root.name.ifBlank { root.absolutePath })
                        stack.forEach { append(" / ").append(it) }
                    },
                    style = MiuixTheme.textStyles.headline1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${childFolders.size} 个子文件夹 · ${levelFiles.count { !it.isVideo }} 张图片 · ${levelFiles.count { it.isVideo }} 个视频" +
                        if (scanning) " · 扫描中…" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (stack.isNotEmpty()) TextButton(text = "上一级", onClick = { stack = stack.dropLast(1) })
                TextButton(text = "刷新", onClick = { reload++ })
                TextButton(text = "打开目录", onClick = {
                    val dir = File(prefix)
                    runCatching { Desktop.getDesktop().open(dir.takeIf { it.isDirectory } ?: root) }
                })
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(text = "全部", onClick = { stack = emptyList() })
                stack.forEachIndexed { i, seg ->
                    Text("/", style = MiuixTheme.textStyles.footnote2)
                    TextButton(text = seg, onClick = { stack = stack.take(i + 1) })
                }
            }

            // ---- 工具栏：类型 / 搜索 / 排序 / 网格列表 / 显示设置 ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Filter.entries.forEach { f ->
                    val n = when (f) {
                        Filter.ALL -> levelFiles.size
                        Filter.IMAGE -> levelFiles.count { !it.isVideo }
                        Filter.VIDEO -> levelFiles.count { it.isVideo }
                    }
                    val on = prefs.filter == f.key
                    if (on) Button(onClick = { persist(prefs.copy(filter = f.key)) }) {
                        Text("${f.label} $n", style = MiuixTheme.textStyles.button)
                    } else TextButton(text = "${f.label} $n", onClick = { persist(prefs.copy(filter = f.key)) })
                }
                Box(Modifier.width(210.dp)) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "筛选文件名",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Sort.entries.forEach { s ->
                    if (prefs.sort == s.key) Button(onClick = { persist(prefs.copy(sort = s.key)) }) {
                        Text(s.label, style = MiuixTheme.textStyles.button)
                    } else TextButton(text = s.label, onClick = { persist(prefs.copy(sort = s.key)) })
                }
                TextButton(text = if (prefs.view == "list") "网格" else "列表", onClick = {
                    persist(prefs.copy(view = if (prefs.view == "list") "thumbnail" else "list"))
                })
                TextButton(text = "显示设置", onClick = { persist(prefs.copy(panel = !prefs.panel)) })
            }
            Reveal(prefs.panel) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("列数", style = MiuixTheme.textStyles.footnote2)
                    listOf(2, 3, 4, 5, 6, 8).forEach { n ->
                        if (n == prefs.columns) Button(onClick = { persist(prefs.copy(columns = n)) }) {
                            Text("$n", style = MiuixTheme.textStyles.button)
                        } else TextButton(text = "$n", onClick = { persist(prefs.copy(columns = n)) })
                    }
                    Text("缩略图", style = MiuixTheme.textStyles.footnote2)
                    TextButton(text = if (prefs.fit == "cover") "裁剪填充" else "完整显示", onClick = {
                        persist(prefs.copy(fit = if (prefs.fit == "cover") "contain" else "cover"))
                    })
                    TextButton(text = if (prefs.panel) "收起" else "展开", onClick = { persist(prefs.copy(panel = !prefs.panel)) })
                }
            }

            if (shown.isEmpty() && childFolders.isEmpty()) {
                EmptyHint(if (searching) "没有匹配的文件" else "该目录内没有媒体文件")
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (prefs.view == "list") 1 else prefs.columns),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (childFolders.isNotEmpty() && !searching) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        SmallTitle(text = "子文件夹 (${childFolders.size})")
                    }
                    childFolders.forEach { (name, count) ->
                        item(key = "dir:$name") {
                            SubfolderCard(name, count, prefs.view == "list") { stack = stack + name }
                        }
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { HairLine() }
                items(
                    count = shown.size,
                    key = { shown[it].absolutePath },
                ) { i ->
                    val file = shown[i]
                    val isVideo = file.extension.lowercase() in VIDEO_EXT
                    val bitmap = thumbCache[file.path]
                    MediaTile(file, bitmap, isVideo, prefs.view == "list", prefs.fit == "cover") {
                        viewerAt = shown.indexOf(file)
                    }
                }
            }
        }
    }

    // ---- 全屏看图窗口（x-spider MediaLightbox 等价物）----
    val at = viewerAt
    if (at != null && shown.isNotEmpty()) {
        LightboxWindow(
            files = shown,
            index = at,
            thumbCache = thumbCache,
            onIndex = { viewerAt = it },
            onClose = { viewerAt = null },
            onOpenExternal = { f -> runCatching { Desktop.getDesktop().open(f) } },
            onShowInFolder = { f -> reveal(f) },
            onMessage = onMessage,
        )
    }
}

@Composable
private fun FolderSidebarItem(
    label: String,
    isAll: Boolean,
    selected: Boolean,
    count: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiuixIcons.Regular.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) Text(
            "$count",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SubfolderCard(name: String, count: Int, list: Boolean, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().height(if (list) 56.dp else 84.dp).clickable { onOpen() },
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Regular.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$count 项",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun MediaTile(
    file: File,
    bitmap: ImageBitmap?,
    isVideo: Boolean,
    list: Boolean,
    crop: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().height(if (list) 62.dp else 210.dp).clickable { onOpen() },
        insideMargin = PaddingValues(6.dp),
    ) {
        if (list) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(if (isVideo) "视频" else "图片", style = MiuixTheme.textStyles.footnote2)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Fmt.size(file.length()) + " · " + dateTime(file.lastModified()),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = file.name,
                            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else Text(if (isVideo) "视频" else "图片", style = MiuixTheme.textStyles.footnote2)
                }
                Spacer(Modifier.height(4.dp))
                Text(file.name, style = MiuixTheme.textStyles.footnote2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LightboxWindow(
    files: List<File>,
    index: Int,
    thumbCache: MutableMap<String, ImageBitmap>,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onOpenExternal: (File) -> Unit,
    onShowInFolder: (File) -> Unit,
    onMessage: (String) -> Unit,
) {
    val file = files.getOrNull(index) ?: return
    val isVideo = file.extension.lowercase() in VIDEO_EXT
    var scale by remember(file.path) { mutableStateOf(1f) }
    var offsetX by remember(file.path) { mutableStateOf(0f) }
    var offsetY by remember(file.path) { mutableStateOf(0f) }
    var full by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.path) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        full = if (!isVideo) withContext(Dispatchers.IO) { loadThumb(file, 2400) } else null
    }

    DialogWindow(
        onCloseRequest = onClose,
        title = "ParseHub · 查看媒体",
        state = rememberDialogState(size = androidx.compose.ui.unit.DpSize(1400.dp, 920.dp)),
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@DialogWindow false
            when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.DirectionLeft -> { if (index > 0) onIndex(index - 1); true }
                Key.DirectionRight -> { if (index < files.lastIndex) onIndex(index + 1); true }
                Key.Equals, Key.NumPadAdd -> { scale = (scale * 1.25f).coerceIn(0.2f, 8f); true }
                Key.Minus, Key.NumPadSubtract -> { scale = (scale / 1.25f).coerceIn(0.2f, 8f); true }
                Key.Zero -> { scale = 1f; offsetX = 0f; offsetY = 0f; true }
                else -> false
            }
        },
    ) {
        MiuixTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
                Column(Modifier.fillMaxSize()) {
                    // 顶部：文件名 + 序号 + 缩放 / 外部打开 / 关闭
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = Color.White, style = MiuixTheme.textStyles.body2, maxLines = 1)
                            Text(
                                "${index + 1} / ${files.size} · " + Fmt.size(file.length()) + " · " + dateTime(file.lastModified()),
                                color = Color.White.copy(alpha = 0.6f),
                                style = MiuixTheme.textStyles.footnote2,
                            )
                        }
                        if (!isVideo) {
                            TextButton(text = "−", onClick = { scale = (scale / 1.25f).coerceIn(0.2f, 8f) })
                            Text("${(scale * 100).toInt()}%", color = Color.White, style = MiuixTheme.textStyles.footnote2)
                            TextButton(text = "+", onClick = { scale = (scale * 1.25f).coerceIn(0.2f, 8f) })
                            TextButton(text = "复位", onClick = { scale = 1f; offsetX = 0f; offsetY = 0f })
                        }
                        TextButton(text = "外部打开", onClick = { onOpenExternal(file) })
                        TextButton(text = "所在目录", onClick = { onShowInFolder(file) })
                        IconButton(onClick = onClose) { Icon(MiuixIcons.Regular.Close, contentDescription = "关闭") }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(file.path) {
                                detectTapGestures(onDoubleTap = {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isVideo) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("视频文件", color = Color.White, style = MiuixTheme.textStyles.title3)
                                Spacer(Modifier.height(8.dp))
                                Text(file.name, color = Color.White.copy(alpha = 0.7f), style = MiuixTheme.textStyles.body2)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { onOpenExternal(file) }) { Text("用系统播放器打开", style = MiuixTheme.textStyles.button) }
                            }
                        } else {
                            val bmp = full ?: thumbCache[file.path]
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = file.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY,
                                        ),
                                )
                            } else {
                                Text("正在载入…", color = Color.White, style = MiuixTheme.textStyles.body2)
                            }
                        }
                    }
                    // 左右切换
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(text = "‹ 上一个 (←)", onClick = { if (index > 0) onIndex(index - 1) })
                        Spacer(Modifier.weight(1f))
                        Text("← / → 切换 · Esc 关闭 · + / − 缩放 · 0 复位", color = Color.White.copy(alpha = 0.45f), style = MiuixTheme.textStyles.footnote2)
                        Spacer(Modifier.weight(1f))
                        TextButton(text = "下一个 (→) ›", onClick = { if (index < files.lastIndex) onIndex(index + 1) })
                    }
                }
            }
        }
    }
}

private fun reveal(file: File) {
    try {
        val dir = file.parentFile ?: return
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir)
    } catch (_: Exception) {
    }
}
