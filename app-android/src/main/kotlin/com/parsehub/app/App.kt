package com.parsehub.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.parsehub.ui.TasksScreen
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
        BackHandler(enabled = page != null) { page = null }

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
                                    SettingsPage.DOWNLOAD -> DownloadSettings(config) { applyConfig(it) }
                                    SettingsPage.ABOUT -> AboutSettings { url -> openUrl(ctx, url) }
                                }
                                key == Tab.PARSE.name -> {
                                    val clip = LocalClipboardManager.current
                                    ParseScreen(controller, readClipboard = { clip.getText()?.text })
                                }
                                key == Tab.TASKS.name -> TasksScreen(controller)
                                key == Tab.GALLERY.name -> GalleryScreen(ctx) { toast = it }
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
