package com.parsehub.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.parsehub.HubController
import com.parsehub.core.AppConfig
import com.parsehub.core.nowCompact
import com.parsehub.ui.AliasSettings
import com.parsehub.ui.AppearanceSettings
import com.parsehub.ui.AboutSettings
import com.parsehub.ui.CookieSettings
import com.parsehub.ui.DownloadSettings
import com.parsehub.ui.HistoryScreen
import com.parsehub.ui.NamingSettings
import com.parsehub.ui.PageHero
import com.parsehub.ui.ParseScreen
import com.parsehub.ui.Reveal
import com.parsehub.ui.SettingsHome
import com.parsehub.ui.SettingsPage
import com.parsehub.ui.TasksScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private enum class Tab(val title: String, val icon: ImageVector) {
    PARSE("解析", MiuixIcons.Regular.Link),
    TASKS("下载", MiuixIcons.Regular.Download),
    GALLERY("图库", MiuixIcons.Regular.GridView),
    HISTORY("记录", MiuixIcons.Regular.Folder),
    SETTINGS("设置", MiuixIcons.Regular.Settings),
}

private data class GalleryItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val rel: String,
    val added: Long,
)

@Composable
fun App(sharedText: MutableState<String>) {
    val ctx = LocalContext.current
    val store = remember { AndroidStore(ctx) }
    val controller = remember {
        HubController(
            tempDir = (ctx.cacheDir.absolutePath + "/parsehub_dl").toPath(),
            sink = MediaStoreSink(ctx),
            initialConfig = store.loadConfig(),
            history = store.history(),
            onConfigChanged = { store.saveConfig(it) },
        )
    }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(controller.config) }
    var tab by remember { mutableStateOf(Tab.PARSE) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var viewing by remember { mutableStateOf<GalleryItem?>(null) }
    var toast by remember { mutableStateOf("") }

    fun applyConfig(next: AppConfig) {
        config = next
        controller.updateConfig(next)
    }

    LaunchedEffect(sharedText.value) {
        val t = sharedText.value
        if (t.isNotBlank() && controller.link.isBlank()) {
            controller.link = t
            tab = Tab.PARSE
        }
    }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            kotlinx.coroutines.delay(2600)
            toast = ""
        }
    }

    val mode = when (config.theme) {
        "light" -> ColorSchemeMode.Light
        "dark" -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val themeController = remember(mode) { ThemeController(mode) }

    MiuixTheme(controller = themeController) {
        BackHandler(enabled = page != null || viewing != null) {
            if (viewing != null) viewing = null else page = null
        }

        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Scaffold(
                topBar = {
                    SmallTopAppBar(
                        title = page?.title ?: tab.title,
                        navigationIcon = {
                            if (page != null) {
                                IconButton(onClick = { page = null }) {
                                    Icon(MiuixIcons.Regular.Back, contentDescription = "返回")
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = page == null && tab == t,
                                onClick = {
                                    page = null
                                    tab = t
                                },
                                icon = t.icon,
                                label = t.title,
                            )
                        }
                    }
                },
                content = { pad ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
                    ) {
                        AnimatedContent(
                            targetState = page?.name ?: tab.name,
                            transitionSpec = {
                                (slideInHorizontally(animationSpec = com.parsehub.ui.PageSpring) { it / 8 } + fadeIn())
                                    .togetherWith(slideOutHorizontally(animationSpec = com.parsehub.ui.PageSpring) { -it / 8 } + fadeOut())
                            },
                            label = "page",
                        ) { key ->
                            val currentPage = page
                            when {
                                currentPage != null -> when (currentPage) {
                                    SettingsPage.NAMING -> NamingSettings(config) { applyConfig(it) }
                                    SettingsPage.ALIASES -> AliasSettings(config) { applyConfig(it) }
                                    SettingsPage.COOKIES -> CookieSettings(config) { applyConfig(it) }
                                    SettingsPage.APPEARANCE -> AppearanceSettings(config) { applyConfig(it) }
                                    SettingsPage.DOWNLOAD -> DownloadSettings(config) { applyConfig(it) }
                                    SettingsPage.ABOUT -> AboutSettings { url -> openUrl(ctx, url) }
                                }
                                key == Tab.PARSE.name -> {
                                    val clip = LocalClipboardManager.current
                                    ParseScreen(controller, readClipboard = { clip.getText()?.text })
                                }
                                key == Tab.TASKS.name -> TasksScreen(controller)
                                key == Tab.GALLERY.name -> GalleryScreen(
                                    ctx = ctx,
                                    onOpen = { viewing = it },
                                    onMessage = { toast = it },
                                )
                                key == Tab.HISTORY.name -> HistoryScreen(controller) { csv ->
                                    scope.launch {
                                        val name = exportCsv(ctx, csv)
                                        toast = if (name == null) "导出失败" else "已导出到 Download/ParseHub/$name"
                                    }
                                }
                                else -> SettingsHome(config) { page = it }
                            }
                        }
                    }
                },
            )

            Reveal(viewing != null) {
                viewing?.let { item ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.94f)),
                    ) {
                        ViewerBody(item)
                        IconButton(
                            onClick = { viewing = null },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        ) {
                            Icon(MiuixIcons.Regular.Close, contentDescription = "关闭")
                        }
                        Text(
                            item.name,
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        )
                    }
                }
            }

            Reveal(toast.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Card(Modifier.padding(24.dp), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(toast, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerBody(item: GalleryItem) {
    var scale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var panX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var panY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
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

// ---------------------------------------------------------------- 图库
@Composable
private fun GalleryScreen(
    ctx: Context,
    onOpen: (GalleryItem) -> Unit,
    onMessage: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            denied = false
            reload++
        }
    }

    LaunchedEffect(reload) {
        loading = true
        val res = withContext(Dispatchers.IO) { runCatching { queryGallery(ctx) } }
        items = res.getOrDefault(emptyList())
        denied = res.isFailure
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("本地图库", "浏览 Download 目录里已下载的图片与视频 · ${items.size} 项")
        if (denied) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "需要媒体读取权限才能扫描下载目录",
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
        }
        if (loading) {
            Text(
                "正在扫描…",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (!loading && items.isEmpty() && !denied) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                Text(
                    "Download 目录里还没有内容，先去解析下载吧",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items) { item ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpen(item) },
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) {
                        Text(
                            "视频",
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun queryGallery(ctx: Context): List<GalleryItem> {
    val out = mutableListOf<GalleryItem>()
    val cols = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATE_ADDED,
    )
    val sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
    val args = arrayOf("Download/%")
    val order = MediaStore.MediaColumns.DATE_ADDED + " DESC"

    ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel, args, order)?.use { c ->
        while (c.moveToNext()) {
            out += GalleryItem(
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                name = c.getString(1) ?: "",
                isVideo = false,
                rel = c.getString(2) ?: "",
                added = c.getLong(3),
            )
        }
    }
    ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, sel, args, order)?.use { c ->
        while (c.moveToNext()) {
            out += GalleryItem(
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                name = c.getString(1) ?: "",
                isVideo = true,
                rel = c.getString(2) ?: "",
                added = c.getLong(3),
            )
        }
    }
    return out.sortedByDescending { it.added }
}

// ---------------------------------------------------------------- 工具
private suspend fun exportCsv(ctx: Context, text: String): String? = withContext(Dispatchers.IO) {
    try {
        val name = "parsehub-history-" + nowCompact() + ".csv"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ParseHub/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { os -> os.write(text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        name
    } catch (_: Exception) {
        null
    }
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
