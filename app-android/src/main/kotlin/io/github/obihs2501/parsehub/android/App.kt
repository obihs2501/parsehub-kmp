package io.github.obihs2501.parsehub.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehub.Hub
import io.github.obihs2501.parsehub.HubController
import io.github.obihs2501.parsehub.core.Fmt
import io.github.obihs2501.parsehub.core.Platform
import io.github.obihs2501.parsehub.core.sanitize
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun App(sharedText: MutableState<String>) {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    val controller = remember {
        HubController(tempDir = (ctx.cacheDir.absolutePath + "/parsehub_dl").toPath())
    }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText.value) {
        val t = sharedText.value
        if (t.isNotBlank() && controller.link.isBlank()) controller.link = t
    }

    val mode = when (settings.theme) {
        "light" -> ColorSchemeMode.Light
        "dark" -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val themeController = remember(mode) { ThemeController(mode) }

    MiuixTheme(controller = themeController) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {
            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    onSave = { settings = it; SettingsStore.save(ctx, it) },
                    onBack = { showSettings = false },
                )
            } else {
                MainScreen(c = controller, onSettings = { showSettings = true })
            }
        }
    }
}

@Composable
private fun MainScreen(c: HubController, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { SettingsStore.load(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ParseHub",
                style = MiuixTheme.textStyles.title1,
                modifier = Modifier.weight(1f),
            )
            TextButton(text = "设置", onClick = onSettings)
        }

        TextField(
            value = c.link,
            onValueChange = { c.link = it },
            label = "粘贴链接 / 分享文案（抖音·快手·微博·B站·头条·皮皮虾·最右·小红书）",
            useLabelAsPlaceholder = true,
        )

        val detected = Platform.detect(c.link)
        Text(
            if (detected != null) "已识别：${detected.label}" else
                "支持：${Hub.platforms.joinToString("、") { it.label }}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { c.parse(settings.toOptions()) },
                enabled = !c.busy && c.link.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (c.parsing) "解析中…" else "解析", style = MiuixTheme.textStyles.button)
            }
            Button(
                onClick = {
                    try {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        if (t.isNotBlank()) c.link = t
                    } catch (_: Exception) {
                    }
                },
                enabled = !c.busy,
            ) {
                Text("粘贴", style = MiuixTheme.textStyles.button)
            }
        }

        if (c.parsing) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }

        if (c.error.isNotEmpty()) {
            Text(c.error, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
        } else if (c.note.isNotEmpty()) {
            Text(
                c.note,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }

        val r = c.result
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
                SmallTitle(text = "档位 · 已选 ${c.pick.size}/${r.tiers.size}")
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
                                state = if (c.pick.contains(t.idx)) ToggleableState.On else ToggleableState.Off,
                                onClick = { c.togglePick(t.idx) },
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
                SmallTitle(text = "图集 · ${r.images.size} 张（实况自动带 .mp4）")
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    SettingRow(
                        title = "下载全部图片",
                        summary = "实况照片会同时下载动态视频",
                        checked = c.imagesOn,
                        onChecked = { c.imagesOn = it },
                    )
                }
            }

            if (r.aux.isNotEmpty()) {
                SmallTitle(text = "附属资源")
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    r.aux.forEachIndexed { i, a ->
                        SettingRow(
                            title = a.label,
                            summary = "${a.spec} · ${a.urls.size} 条线路",
                            checked = c.auxOn.getOrElse(i) { false },
                            onChecked = { c.toggleAux(i) },
                        )
                    }
                }
            }

            if (c.downloading) {
                val frac = if (c.curTotal > 0) c.curDone.toFloat() / c.curTotal else null
                LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth())
                Text(
                    "${c.doneJobs}/${c.totalJobs} · ${c.curName.take(40)}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Text(
                    "${Fmt.size(c.curDone)} / ${Fmt.size(c.curTotal)} · ${Fmt.size(c.speed)}/s",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Button(onClick = { c.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text("取消", style = MiuixTheme.textStyles.button)
                }
            } else {
                Button(
                    onClick = {
                        val rel = "${r.platform.dirPrefix}_${sanitize(r.author, 40)}"
                        c.startDownload(MediaStoreSink(ctx, rel))
                    },
                    enabled = c.hasSelection(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载", style = MiuixTheme.textStyles.button)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.main)
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    BackHandler(enabled = true) { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MiuixTheme.textStyles.title1, modifier = Modifier.weight(1f))
            TextButton(text = "返回", onClick = onBack)
        }

        SmallTitle(text = "解析后默认选中")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            listOf(
                "origin" to "最高档（原画优先，可能较大）",
                "h264" to "H264 最高档（兼容性最好）",
                "small" to "最小体积",
                "none" to "不自动勾选",
            ).forEach { (v, label) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (settings.defaultTier == v) ToggleableState.On else ToggleableState.Off,
                        onClick = { onSave(settings.copy(defaultTier = v)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MiuixTheme.textStyles.main)
                }
            }
        }

        SmallTitle(text = "外观")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (settings.theme == v) ToggleableState.On else ToggleableState.Off,
                        onClick = { onSave(settings.copy(theme = v)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MiuixTheme.textStyles.main)
                }
            }
        }

        SmallTitle(text = "Cookie（可选，提高成功率 / 画质）")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            TextField(
                value = settings.cookieBili,
                onValueChange = { onSave(settings.copy(cookieBili = it)) },
                label = "B站 Cookie（SESSDATA=...，1080P+ 需要）",
                useLabelAsPlaceholder = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = settings.cookieWeibo,
                onValueChange = { onSave(settings.copy(cookieWeibo = it)) },
                label = "微博 Cookie（可选）",
                useLabelAsPlaceholder = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = settings.cookieXhs,
                onValueChange = { onSave(settings.copy(cookieXhs = it)) },
                label = "小红书 Cookie（可选）",
                useLabelAsPlaceholder = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = settings.cookieToutiao,
                onValueChange = { onSave(settings.copy(cookieToutiao = it)) },
                label = "头条 Cookie（可选）",
                useLabelAsPlaceholder = true,
            )
        }

        Text(
            "保存位置：Download/<平台>_<作者>/ · 版本 0.1.0",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
