package com.parsehub.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.imageio.ImageIO

private enum class Tab(val title: String) {
    PARSE("解析工作台"),
    TASKS("下载管理"),
    GALLERY("本地图库"),
    HISTORY("解析记录"),
    SETTINGS("设置"),
}

internal data class LocalFile(val file: File, val isVideo: Boolean)

@Composable
fun DesktopApp() {
    val store = remember { DesktopStore() }
    var outDir by remember { mutableStateOf(store.loadOutDir()) }
    val tempDir = remember {
        File(System.getProperty("java.io.tmpdir"), "parsehub_dl").apply { mkdirs() }.absolutePath.toPath()
    }
    val controller = remember {
        HubController(
            tempDir = tempDir,
            sink = DesktopSink { File(outDir) },
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
    // 缩略图缓存跨 tab 保留：切回图库不再重复解码（此前在组合期同步 ImageIO.read，切 tab 会卡）
    val thumbCache = remember { mutableStateMapOf<String, ImageBitmap>() }

    fun applyConfig(next: AppConfig) {
        config = next
        controller.updateConfig(next)
    }

    fun setOutDir(dir: String) {
        outDir = dir
        store.saveOutDir(dir)
    }

    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            kotlinx.coroutines.delay(2600)
            toast = ""
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(tab = tab, page = page, onTab = { tab = it; page = null })
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OutDirBar(
                    outDir = outDir,
                    onPick = { pickFolder(outDir)?.let { setOutDir(it) } },
                    onOpen = { openInFileManager(outDir) },
                )
                AnimatedContent(
                    targetState = page?.name ?: tab.name,
                    transitionSpec = {
                        (slideInHorizontally(animationSpec = PageSpring) { it / 8 } + fadeIn())
                            .togetherWith(slideOutHorizontally(animationSpec = PageSpring) { -it / 8 } + fadeOut())
                    },
                    label = "page",
                ) { key ->
                    val current = page
                    when {
                        current != null -> when (current) {
                            SettingsPage.NAMING -> NamingSettings(config) { applyConfig(it) }
                            SettingsPage.ALIASES -> AliasSettings(config) { applyConfig(it) }
                            SettingsPage.COOKIES -> CookieSettings(config) { applyConfig(it) }
                            SettingsPage.APPEARANCE -> AppearanceSettings(config) { applyConfig(it) }
                            SettingsPage.DOWNLOAD -> DownloadSettings(
                                config = config,
                                onConfig = { applyConfig(it) },
                                rootLabel = File(outDir).absolutePath,
                                onPickFolder = { pickFolder(outDir)?.let { picked -> setOutDir(picked) } },
                            )
                            SettingsPage.ABOUT -> AboutSettings { url -> openUrl(url) }
                        }
                        key == Tab.PARSE.name -> ParseScreen(controller, readClipboard = ::readClipboard)
                        key == Tab.TASKS.name -> TasksScreen(controller)
                        key == Tab.GALLERY.name -> StudioGallery(
                            root = File(outDir),
                            store = store,
                            thumbCache = thumbCache,
                            onMessage = { toast = it },
                        )
                        key == Tab.HISTORY.name -> HistoryScreen(controller) { csv ->
                            scope.launch {
                                val name = saveCsv(csv)
                                toast = if (name == null) "导出失败" else "已导出 $name"
                            }
                        }
                        else -> SettingsHome(config) { page = it }
                    }
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

@Composable
private fun Sidebar(tab: Tab, page: SettingsPage?, onTab: (Tab) -> Unit) {
    Column(
        Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ParseHub", style = MiuixTheme.textStyles.title3)
            Spacer(Modifier.width(6.dp))
            Text("Studio", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Tab.entries.forEach { t ->
            SideItem(t.title, page == null && tab == t) { onTab(t) }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "v0.1.0 · Miuix",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun SideItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.main,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

@Composable
private fun OutDirBar(outDir: String, onPick: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "保存到 $outDir",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = "选择目录", onClick = onPick)
        TextButton(text = "打开目录", onClick = onOpen)
    }
}

// ---------------------------------------------------------------- 平台工具
internal val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp")
internal val VIDEO_EXT = setOf("mp4", "mov", "mkv", "webm", "avi", "ts", "m4v")

internal fun scanLocal(root: File): List<LocalFile> {
    if (!root.isDirectory) return emptyList()
    val out = mutableListOf<LocalFile>()
    root.walkTopDown().maxDepth(3).forEach { f ->
        if (!f.isFile) return@forEach
        val ext = f.extension.lowercase()
        when (ext) {
            in IMAGE_EXT -> out += LocalFile(f, false)
            in VIDEO_EXT -> out += LocalFile(f, true)
        }
        if (out.size > 800) return out
    }
    return out
}

/** 文件相对扫描根目录的上一级目录名，用作筛选标签 */
private fun relFolder(root: File, file: File): String {
    val rel = file.parentFile?.absolutePath?.removePrefix(root.absolutePath) ?: return ""
    val parts = rel.split('/', '\\').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: ""
}

/** 相对根目录的完整目录路径（用于层级浏览判断） */
private fun relPath(root: File, file: File): String {
    val rel = file.parentFile?.absolutePath?.removePrefix(root.absolutePath) ?: return ""
    return rel.trim('/', '\\')
}

internal fun loadThumb(file: File, max: Int = 320) = try {
    val img = ImageIO.read(file) ?: return null
    val scale = max.toDouble() / maxOf(img.width, img.height).coerceAtLeast(1)
    val w = (img.width * scale).toInt().coerceAtLeast(1)
    val h = (img.height * scale).toInt().coerceAtLeast(1)
    val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = small.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    small.toComposeImageBitmap()
} catch (_: Throwable) {
    null
}

private fun pickFolder(initial: String): String? = try {
    val d = FileDialog(null as Frame?, "选择保存目录", FileDialog.LOAD)
    d.directory = initial
    d.isVisible = true
    val f = d.file
    if (f == null) null else File(d.directory, f).absolutePath
} catch (_: Exception) {
    null
}

private fun openInFileManager(path: String) {
    try {
        File(path).takeIf { it.exists() }?.let { Desktop.getDesktop().open(it) }
    } catch (_: Exception) {
    }
}

internal fun openFile(file: File) {
    try {
        Desktop.getDesktop().open(file)
    } catch (_: Exception) {
    }
}

private fun openUrl(url: String) {
    try {
        Desktop.getDesktop().browse(java.net.URI(url))
    } catch (_: Exception) {
    }
}

private fun readClipboard(): String? = try {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
} catch (_: Exception) {
    null
}

private fun saveCsv(text: String): String? = try {
    val dir = File(DesktopStore().defaultOutDir())
    dir.mkdirs()
    val f = File(dir, "parsehub-history-" + nowCompact() + ".csv")
    f.writeText(text)
    f.absolutePath
} catch (_: Exception) {
    null
}
