package io.github.obihs2501.parsehub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehub.FileSink
import io.github.obihs2501.parsehub.Hub
import io.github.obihs2501.parsehub.HubController
import io.github.obihs2501.parsehub.core.Fmt
import io.github.obihs2501.parsehub.core.ParseOptions
import io.github.obihs2501.parsehub.core.Platform
import io.github.obihs2501.parsehub.core.sanitize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

/** 桌面版落点：把临时文件移动进选择的目录 */
private class DesktopSink(private val outDir: File) : FileSink {
    override suspend fun accept(tempPath: Path, fileName: String, mime: String) {
        withContext(Dispatchers.IO) {
            outDir.mkdirs()
            val fs = FileSystem.SYSTEM
            val dest = File(outDir, fileName).absolutePath.toPath()
            fs.delete(dest, mustExist = false)
            try {
                fs.atomicMove(tempPath, dest)
            } catch (_: Exception) {
                fs.copy(tempPath, dest)
                fs.delete(tempPath, mustExist = false)
            }
        }
    }
}

private fun pickFolder(initial: String): String? {
    val d = FileDialog(null as Frame?, "选择保存目录", FileDialog.LOAD)
    d.directory = initial
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, f).absolutePath
}

@Composable
fun DesktopApp() {
    val tempDir = remember {
        File(System.getProperty("java.io.tmpdir"), "parsehub_dl").apply { mkdirs() }.absolutePath.toPath()
    }
    val controller = remember { HubController(tempDir = tempDir) }
    var outDir by remember {
        mutableStateOf(File(System.getProperty("user.home"), "Downloads").absolutePath)
    }
    var opts by remember { mutableStateOf(ParseOptions()) }
    var showCookies by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ParseHub Studio", style = MiuixTheme.textStyles.title1)
        Text(
            "多平台解析下载 · ${Hub.platforms.joinToString("、") { it.label }}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )

        TextField(
            value = controller.link,
            onValueChange = { controller.link = it },
            label = "粘贴链接 / 分享文案",
            useLabelAsPlaceholder = true,
        )
        val detected = Platform.detect(controller.link)
        if (detected != null) {
            Text(
                "已识别：${detected.label}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { controller.parse(opts) },
                enabled = !controller.busy && controller.link.isNotBlank(),
            ) {
                Text(if (controller.parsing) "解析中…" else "解析", style = MiuixTheme.textStyles.button)
            }
            Button(
                onClick = {
                    try {
                        val t = Toolkit.getDefaultToolkit().systemClipboard
                            .getData(DataFlavor.stringFlavor) as? String
                        if (!t.isNullOrBlank()) controller.link = t
                    } catch (_: Exception) {
                    }
                },
                enabled = !controller.busy,
            ) {
                Text("粘贴", style = MiuixTheme.textStyles.button)
            }
            TextButton(
                text = if (showCookies) "收起 Cookie" else "Cookie 设置",
                onClick = { showCookies = !showCookies },
            )
        }

        if (showCookies) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                TextField(
                    value = opts.cookieBili,
                    onValueChange = { opts = opts.copy(cookieBili = it) },
                    label = "B站 Cookie（SESSDATA=...，1080P+ 需要）",
                    useLabelAsPlaceholder = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = opts.cookieWeibo,
                    onValueChange = { opts = opts.copy(cookieWeibo = it) },
                    label = "微博 Cookie（可选）",
                    useLabelAsPlaceholder = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = opts.cookieXhs,
                    onValueChange = { opts = opts.copy(cookieXhs = it) },
                    label = "小红书 Cookie（可选）",
                    useLabelAsPlaceholder = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = opts.cookieToutiao,
                    onValueChange = { opts = opts.copy(cookieToutiao = it) },
                    label = "头条 Cookie（可选）",
                    useLabelAsPlaceholder = true,
                )
            }
        }

        if (controller.parsing) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }
        if (controller.error.isNotEmpty()) {
            Text(controller.error, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
        } else if (controller.note.isNotEmpty()) {
            Text(
                controller.note,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }

        val r = controller.result
        if (r != null) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(r.title, style = MiuixTheme.textStyles.headline1)
                Text(
                    "${r.platform.label} · @${r.author} · ${r.date}" +
                        (if (r.durSec > 0) " · ${Fmt.dur(r.durSec)}" else ""),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                if (r.statLine.isNotEmpty()) {
                    Text(
                        r.statLine,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            if (r.tiers.isNotEmpty()) {
                SmallTitle(text = "档位 · 已选 ${controller.pick.size}/${r.tiers.size}")
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    r.tiers.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (controller.pick.contains(t.idx)) ToggleableState.On
                                else ToggleableState.Off,
                                onClick = { controller.togglePick(t.idx) },
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.gear + if (t.isOrigin) " ★" else "",
                                    style = MiuixTheme.textStyles.main,
                                    color = if (t.isOrigin) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                                val bits = buildList {
                                    if (t.w > 0 && t.h > 0) add(t.res)
                                    if (t.size > 0) add(Fmt.size(t.size))
                                    if (t.bps > 0) add(Fmt.bps(t.bps))
                                    if (t.fps > 0) add(Fmt.fps(t.fps, t.fpsEst))
                                    if (t.w > 0 || t.h > 0) add(if (t.h265) "H265" else "H264")
                                }
                                if (bits.isNotEmpty()) {
                                    Text(
                                        bits.joinToString(" · "),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (r.images.isNotEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("下载全部图片（${r.images.size} 张）", style = MiuixTheme.textStyles.main)
                            Text(
                                "实况照片会同时下载动态视频",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                        Switch(checked = controller.imagesOn, onCheckedChange = { controller.imagesOn = it })
                    }
                }
            }

            if (r.aux.isNotEmpty()) {
                SmallTitle(text = "附属资源")
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    r.aux.forEachIndexed { i, a ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MiuixTheme.textStyles.main)
                                Text(
                                    "${a.spec} · ${a.urls.size} 条线路",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                            }
                            Switch(
                                checked = controller.auxOn.getOrElse(i) { false },
                                onCheckedChange = { controller.toggleAux(i) },
                            )
                        }
                    }
                }
            }
        }

        SmallTitle(text = "保存位置")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Text(
                outDir,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { pickFolder(outDir)?.let { outDir = it } },
                    enabled = !controller.busy,
                ) {
                    Text("选择目录", style = MiuixTheme.textStyles.button)
                }
                Button(
                    onClick = {
                        try {
                            Desktop.getDesktop().open(File(outDir))
                        } catch (_: Exception) {
                        }
                    },
                    enabled = !controller.busy,
                ) {
                    Text("打开目录", style = MiuixTheme.textStyles.button)
                }
            }
        }

        if (controller.downloading) {
            val frac = if (controller.curTotal > 0) controller.curDone.toFloat() / controller.curTotal else null
            LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth())
            Text(
                "${controller.doneJobs}/${controller.totalJobs} · ${controller.curName.take(50)} · " +
                    "${Fmt.size(controller.curDone)} / ${Fmt.size(controller.curTotal)} · " +
                    "${Fmt.size(controller.speed)}/s",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            Button(onClick = { controller.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("取消", style = MiuixTheme.textStyles.button)
            }
        } else if (controller.result != null) {
            Button(
                onClick = { controller.startDownload(DesktopSink(File(outDir))) },
                enabled = controller.hasSelection(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("下载", style = MiuixTheme.textStyles.button)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
