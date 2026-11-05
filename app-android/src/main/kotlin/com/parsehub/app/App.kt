package com.parsehub.app

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.parsehub.HubController
import com.parsehub.core.AppConfig
import com.parsehub.core.nowCompact
import com.parsehub.ui.AboutSettings
import com.parsehub.ui.AliasSettings
import com.parsehub.ui.AppearanceSettings
import com.parsehub.ui.CookieSettings
import com.parsehub.ui.DownloadSettings
import com.parsehub.ui.HistoryScreen
import com.parsehub.ui.NamingSettings
import com.parsehub.ui.PageSpring
import com.parsehub.ui.ParseScreen
import com.parsehub.ui.Reveal
import com.parsehub.ui.SettingsHome
import com.parsehub.ui.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
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
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalWindowInfo
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect

private enum class Tab(val title: String, val icon: ImageVector) {
    PARSE("解析", MiuixIcons.Regular.Link),
    GALLERY("图库", MiuixIcons.Regular.GridView),
    HISTORY("记录", MiuixIcons.Regular.Folder),
    SETTINGS("设置", MiuixIcons.Regular.Settings),
}

@Composable
fun App(sharedText: MutableState<String>) {
    val ctx = LocalContext.current
    val store = remember { AndroidStore(ctx) }
    var config by remember { mutableStateOf(store.loadConfig()) }
    val sink = remember { AndroidSink(ctx) { config } }
    val controller = remember {
        HubController(
            tempDir = (ctx.cacheDir.absolutePath + "/parsehub_dl").toPath(),
            sink = sink,
            initialConfig = config,
            history = store.history(),
            onConfigChanged = { store.saveConfig(it) },
        )
    }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.PARSE) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var toast by remember { mutableStateOf("") }
    var immersive by remember { mutableStateOf(false) }
    // 记录页点「打开」时，用与图库相同的看图覆盖层预览已下载的图片/视频
    var historyViewer by remember { mutableStateOf<Pair<Int, List<MediaItem>>?>(null) }

    // 系统栏图标：浅色页面用深色图标，深色主题 / 看图时用白色图标
    val window = remember(ctx) { ctx.findActivity()?.window }
    val sysDark = isSystemInDarkTheme()
    val darkUi = when (config.theme) {
        "light" -> false
        "dark" -> true
        else -> sysDark
    }
    val lightIcons = !(darkUi || immersive)
    DisposableEffect(darkUi, immersive) {
        (ctx.findActivity() as? androidx.activity.ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { !lightIcons },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { !lightIcons },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isNavigationBarContrastEnforced = false
        }
        onDispose {}
    }
    SideEffect { setSystemBarsDarkIcons(window, lightIcons) }

    fun applyConfig(next: AppConfig) {
        config = next
        controller.updateConfig(next)
    }

    // 系统目录选择器：选完持久化读写权限，之后直接往该目录写
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, SAF_FLAGS) }
            applyConfig(config.copy(treeUri = uri.toString()))
            toast = "保存位置已切换"
        }
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
        BackHandler(enabled = page != null) { page = null }
        // 官方示例：layerBackdrop 捕获内容，textureBlur 在悬浮层上取模糊；
        // onDraw 里先画不透明底色，避免透明像素扩散成色块
        val bg = MiuixTheme.colorScheme.background
        val backdrop = rememberLayerBackdrop {
            drawRect(bg)
            drawContent()
        }
        val glass = config.navStyle == "glass" && isRuntimeShaderSupported()
        // 大屏（平板 / 横屏）按 Miuix 官方自适应：底栏改为左侧 NavigationRail
        val size = LocalWindowInfo.current.containerDpSize
        val wideScreen = size.width >= 840.dp || (size.width >= 600.dp && size.height / size.width < 1.2f)

        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            // 大屏：侧栏与 Scaffold 平级放在外层 Row，顶栏只覆盖右侧内容区
            // （参考 Miuix 官方 AppContent 的自适应布局）；窄屏仍是单栏 Scaffold。
            Row(Modifier.fillMaxSize()) {
                if (wideScreen && !immersive) {
                    NavigationRail(
                        expanded = false,
                        defaultWindowInsetsPadding = true,
                    ) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = page == null && tab == t,
                                onClick = { page = null; tab = t },
                                icon = t.icon,
                                label = t.title,
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (!immersive) {
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
                        }
                    },
                    bottomBar = {
                        // 贴底导航用 bottomBar，Scaffold 会把它的高度从内容区里扣掉；大屏改用侧栏
                        if (!immersive && !wideScreen && config.navStyle == "classic") {
                            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                                NavigationBar {
                                    Tab.entries.forEach { t ->
                                        NavigationBarItem(
                                            selected = page == null && tab == t,
                                            onClick = { page = null; tab = t },
                                            icon = t.icon,
                                            label = t.title,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    floatingToolbar = {
                        // 悬浮 / 液态玻璃导航用 floatingToolbar 叠在内容之上；大屏改用侧栏。
                        // 自己控制高度和圆角，不用 FloatingNavigationBar 的内置大底部内边距，
                        // 避免胶囊被拉成整条、模糊区域过高。
                        if (!immersive && !wideScreen && config.navStyle != "classic") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val shape = RoundedCornerShape(50)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .then(
                                            if (glass) {
                                                Modifier.textureBlur(
                                                    backdrop = backdrop,
                                                    shape = shape,
                                                    blurRadius = 20f,
                                                )
                                            } else {
                                                Modifier.background(MiuixTheme.colorScheme.surfaceContainer, shape)
                                            },
                                        )
                                        .clip(shape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                    ) {
                                        Tab.entries.forEach { t ->
                                            NavigationBarItem(
                                                modifier = Modifier.weight(1f),
                                                selected = page == null && tab == t,
                                                onClick = { page = null; tab = t },
                                                icon = t.icon,
                                                label = t.title,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    content = { pad ->
                        // 悬浮导航不占内容高度，这里额外留出空间，避免遮住列表末尾；大屏无悬浮栏
                        val floatExtra = if (!immersive && !wideScreen && config.navStyle != "classic") 96.dp else 0.dp
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layerBackdrop(backdrop)
                                .padding(
                                    top = pad.calculateTopPadding(),
                                    bottom = pad.calculateBottomPadding() + floatExtra,
                                ),
                        ) {
                            AnimatedContent(
                                targetState = page?.name ?: tab.name,
                                transitionSpec = {
                                    (slideInHorizontally(animationSpec = PageSpring) { it / 8 } + fadeIn())
                                        .togetherWith(slideOutHorizontally(animationSpec = PageSpring) { -it / 8 } + fadeOut())
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
                                        SettingsPage.DOWNLOAD -> DownloadSettings(
                                            config = config,
                                            onConfig = { applyConfig(it) },
                                            rootLabel = sink.describe(),
                                            onPickFolder = { treePicker.launch(null) },
                                        )
                                        SettingsPage.ABOUT -> AboutSettings { url -> openUrl(ctx, url) }
                                    }
                                    key == Tab.PARSE.name -> {
                                        val clip = LocalClipboardManager.current
                                        ParseScreen(controller, readClipboard = { clip.getText()?.text })
                                    }
                                    key == Tab.GALLERY.name -> GalleryScreen(
                                        ctx = ctx,
                                        config = config,
                                        onConfig = { applyConfig(it) },
                                        onMessage = { toast = it },
                                        onImmersive = { immersive = it },
                                    )
                                    key == Tab.HISTORY.name -> HistoryScreen(
                                        c = controller,
                                        onExportCsv = { csv ->
                                            scope.launch {
                                                val name = exportCsv(ctx, csv)
                                                toast = if (name == null) "导出失败" else "已导出到 Download/ParseHub/$name"
                                            }
                                        },
                                        onOpenFiles = { dir, fileNames ->
                                            scope.launch {
                                                val items = withContext(Dispatchers.IO) {
                                                    resolveDownloadedMedia(ctx, dir, fileNames)
                                                }
                                                if (items.isEmpty()) {
                                                    toast = "没找到已保存的媒体，确认已授权「所有文件访问」"
                                                } else {
                                                    historyViewer = 0 to items
                                                }
                                            }
                                        },
                                    )
                                    else -> SettingsHome(config) { page = it }
                                }
                            }
                        }
                    },
                )
            }   // Box(weight)
        }   // Row

        Reveal(toast.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Card(Modifier.padding(24.dp), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(toast, style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }

        // 记录页「打开」→ 与图库一致的看图/播放覆盖层（盖在系统栏之上）
        androidx.compose.animation.AnimatedVisibility(
            visible = historyViewer != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(180)),
        ) {
            val pair = historyViewer
            if (pair != null) {
                MediaViewer(
                    ctx = ctx,
                    items = pair.second,
                    startIndex = pair.first,
                    onClose = { historyViewer = null },
                    onMessage = { toast = it },
                    onImmersive = { immersive = it },
                )
            }
        }
    }
}

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
