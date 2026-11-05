package com.parsehub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.parsehub.core.AppConfig
import com.parsehub.core.AuthorAlias
import com.parsehub.core.CookieEntry
import com.parsehub.core.Fmt
import com.parsehub.core.Naming
import com.parsehub.core.Platform
import com.parsehub.core.maskSecret
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class SettingsPage(val title: String) {
    NAMING("命名与归档"),
    ALIASES("作者映射"),
    COOKIES("Cookie 池"),
    APPEARANCE("外观"),
    DOWNLOAD("下载"),
    ABOUT("关于"),
}

fun tierLabel(tier: String): String = when (tier) {
    "origin" -> "最高档"
    "h264" -> "H264 最高档"
    "small" -> "最小体积"
    else -> "不自动勾选"
}

fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

@Composable
fun SettingsHome(config: AppConfig, onOpen: (SettingsPage) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("设置", "偏好与本地环境")

        SectionLabel("内容与归档")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            ArrowPreference(
                title = "命名与归档",
                summary = "文件夹与文件名模板 · " + if (config.mergeAuthorFolders) "同名合并已开" else "每次新建目录",
                onClick = { onOpen(SettingsPage.NAMING) },
            )
            HairLine()
            ArrowPreference(
                title = "作者映射",
                summary = if (config.aliases.isEmpty()) "未配置 · 可把同一创作者的多号合并归档" else "已配置 ${config.aliases.size} 条",
                onClick = { onOpen(SettingsPage.ALIASES) },
            )
            HairLine()
            ArrowPreference(
                title = "Cookie 池",
                summary = if (config.cookies.isEmpty()) "未配置 · 部分平台需要登录态" else "已配置 ${config.cookies.size} 条 · 每次解析轮换",
                onClick = { onOpen(SettingsPage.COOKIES) },
            )
        }

        SectionLabel("偏好")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            ArrowPreference(
                title = "外观",
                summary = "${themeLabel(config.theme)} · 默认${tierLabel(config.defaultTier)}",
                onClick = { onOpen(SettingsPage.APPEARANCE) },
            )
            HairLine()
            ArrowPreference(
                title = "下载",
                summary = "${config.connections} 连接 · 超时 ${config.timeoutSec} 秒",
                onClick = { onOpen(SettingsPage.DOWNLOAD) },
            )
            HairLine()
            ArrowPreference(
                title = "关于",
                summary = "ParseHub 0.1.0 · Miuix",
                onClick = { onOpen(SettingsPage.ABOUT) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) { content() }
}

// ---------------------------------------------------------------- 命名与归档
@Composable
fun NamingSettings(config: AppConfig, onConfig: (AppConfig) -> Unit) {
    var folder by remember { mutableStateOf(config.folderTemplate) }
    var file by remember { mutableStateOf(config.fileTemplate) }
    val folderErr = Naming.validate(folder)
    val fileErr = Naming.validate(file)
    val sample = mapOf(
        "platform" to "抖音", "author" to "示例作者", "creator" to "示例作者",
        "title" to "示例标题", "id" to "7300000000000000000",
        "date" to "2026-11-05", "publish_date" to "2026-11-04", "time" to "101530", "type" to "视频",
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("命名与归档", "模板支持 {platform} {author} {creator} {title} {id} {publish_date} {date} {time} {index:03d} {type}")

        SectionLabel("文件夹模板")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = folder, onValueChange = { folder = it }, label = "例如 {platform}_{title}_{id}", useLabelAsPlaceholder = true)
                if (folderErr != null) Text(folderErr, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                else Text(
                    "示例：" + Naming.safeName(Naming.render(folder, sample, 1), 100),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (folderErr == null) onConfig(config.copy(folderTemplate = folder)) }, enabled = folderErr == null) {
                        Text("保存", style = MiuixTheme.textStyles.button)
                    }
                    TextButton(text = "恢复默认", onClick = { folder = Naming.DEFAULT_FOLDER })
                }
            }
        }

        SectionLabel("文件名模板")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = file, onValueChange = { file = it }, label = "例如 {index:03d}_{title}", useLabelAsPlaceholder = true)
                if (fileErr != null) Text(fileErr, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                else Text(
                    "示例：" + Naming.safeName(Naming.render(file, sample, 2), 100) + ".mp4",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (fileErr == null) onConfig(config.copy(fileTemplate = file)) }, enabled = fileErr == null) {
                        Text("保存", style = MiuixTheme.textStyles.button)
                    }
                    TextButton(text = "恢复默认", onClick = { file = Naming.DEFAULT_FILE })
                }
            }
        }

        SectionLabel("归档方式")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            SwitchPreference(
                title = "同名文件夹合并归档",
                summary = "同一作者的多次下载进入同一个文件夹，不再另建 _2 目录；重名文件自动加后缀，不覆盖已有文件",
                checked = config.mergeAuthorFolders,
                onCheckedChange = { onConfig(config.copy(mergeAuthorFolders = it)) },
            )
            HairLine()
            SwitchPreference(
                title = "解析后默认勾选图集",
                summary = "作品带图集时自动勾上「下载全部图片」",
                checked = config.imagesByDefault,
                onCheckedChange = { onConfig(config.copy(imagesByDefault = it)) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- 作者映射
@Composable
fun AliasSettings(config: AppConfig, onConfig: (AppConfig) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var platform by remember { mutableStateOf(Platform.DOUYIN) }
    var matchBy by remember { mutableStateOf("id") }
    var account by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("作者映射", "把同一创作者在不同平台或不同账号的 ID / 昵称映射到同一个归档作者，配合文件夹模板里的 {creator} 合并归档")

        if (config.aliases.isNotEmpty()) {
            SectionLabel("已配置 ${config.aliases.size} 条")
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                config.aliases.forEachIndexed { i, a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (Platform.ofKey(a.platform)?.label ?: a.platform) + " · " + a.account,
                                style = MiuixTheme.textStyles.main,
                            )
                            Text(
                                "按" + if (a.matchBy == "name") "昵称" else "作者 ID" + "匹配 → 归档为 " + a.creator,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(text = "删除", onClick = {
                            onConfig(config.copy(aliases = config.aliases.filterIndexed { idx, _ -> idx != i }))
                        })
                    }
                }
            }
        } else {
            EmptyHint("还没有作者映射")
        }

        Reveal(adding) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择平台", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Platform.entries.forEach { p ->
                            if (p == platform) Button(onClick = { platform = p }) { Text(p.label, style = MiuixTheme.textStyles.button) }
                            else TextButton(text = p.label, onClick = { platform = p })
                        }
                    }
                    TextField(value = account, onValueChange = { account = it }, label = if (matchBy == "name") "作者昵称（精确一致）" else "作者 ID", useLabelAsPlaceholder = true)
                    RadioButtonPreference(title = "按作者 ID 匹配", selected = matchBy == "id", onClick = { matchBy = "id" })
                    RadioButtonPreference(title = "按昵称匹配", selected = matchBy == "name", onClick = { matchBy = "name" })
                    TextField(value = creator, onValueChange = { creator = it }, label = "归档作者名（文件夹模板用 {creator}）", useLabelAsPlaceholder = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val entry = AuthorAlias(platform.key, account.trim(), creator.trim(), matchBy)
                                if (entry.account.isNotEmpty() && entry.creator.isNotEmpty()) {
                                    onConfig(config.copy(aliases = config.aliases + entry))
                                    account = ""
                                    creator = ""
                                    adding = false
                                }
                            },
                            enabled = account.isNotBlank() && creator.isNotBlank(),
                        ) { Text("保存映射", style = MiuixTheme.textStyles.button) }
                        TextButton(text = "取消", onClick = { adding = false })
                    }
                }
            }
        }

        if (!adding) {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Text("添加映射", style = MiuixTheme.textStyles.button)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Cookie 池
@Composable
fun CookieSettings(config: AppConfig, onConfig: (AppConfig) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var platform by remember { mutableStateOf(Platform.BILIBILI) }
    var label by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("Cookie 池", "按平台配置多个账号，每次解析轮换一条；显式填写的那条优先")

        if (config.cookies.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                config.cookies.forEachIndexed { i, e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (Platform.ofKey(e.platform)?.label ?: e.platform) + if (e.label.isBlank()) "" else " · ${e.label}",
                                style = MiuixTheme.textStyles.main,
                            )
                            Text(
                                maskSecret(e.cookie, 8),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(checked = e.enabled, onCheckedChange = { v ->
                            onConfig(config.copy(cookies = config.cookies.toMutableList().also { it[i] = e.copy(enabled = v) }))
                        })
                        Spacer(Modifier.width(8.dp))
                        TextButton(text = "删除", onClick = {
                            onConfig(config.copy(cookies = config.cookies.filterIndexed { idx, _ -> idx != i }))
                        })
                    }
                }
            }
        } else {
            EmptyHint("还没有 Cookie，B站 1080P+、微博、小红书、头条 视情况需要")
        }

        Reveal(adding) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择平台", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Platform.entries.forEach { p ->
                            if (p == platform) Button(onClick = { platform = p }) { Text(p.label, style = MiuixTheme.textStyles.button) }
                            else TextButton(text = p.label, onClick = { platform = p })
                        }
                    }
                    TextField(value = label, onValueChange = { label = it }, label = "备注（可选）", useLabelAsPlaceholder = true)
                    TextField(value = cookie, onValueChange = { cookie = it }, label = "Cookie 全文", useLabelAsPlaceholder = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (cookie.isNotBlank()) {
                                    onConfig(config.copy(cookies = config.cookies + CookieEntry(platform.key, label.trim(), cookie.trim(), true)))
                                    label = ""
                                    cookie = ""
                                    adding = false
                                }
                            },
                            enabled = cookie.isNotBlank(),
                        ) { Text("保存", style = MiuixTheme.textStyles.button) }
                        TextButton(text = "取消", onClick = { adding = false })
                    }
                }
            }
        }

        if (!adding) {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Text("添加 Cookie", style = MiuixTheme.textStyles.button)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- 外观 / 下载 / 关于
@Composable
fun AppearanceSettings(config: AppConfig, onConfig: (AppConfig) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("外观", "主题与解析默认选中")
        SectionLabel("主题")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                RadioButtonPreference(title = label, selected = config.theme == v, onClick = { onConfig(config.copy(theme = v)) })
            }
        }
        SectionLabel("解析后默认选中")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            listOf(
                "origin" to "最高档（原画优先）",
                "h264" to "H264 最高档（兼容性最好）",
                "small" to "最小体积",
                "none" to "不自动勾选",
            ).forEach { (v, label) ->
                RadioButtonPreference(title = label, selected = config.defaultTier == v, onClick = { onConfig(config.copy(defaultTier = v)) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DownloadSettings(
    config: AppConfig,
    onConfig: (AppConfig) -> Unit,
    rootLabel: String = "",
    onPickFolder: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("下载", "保存位置、分片连接数与超时")

        SectionLabel("保存位置")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    rootLabel.ifBlank { "Download/" },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onPickFolder != null) {
                        Button(onClick = onPickFolder) { Text("选择文件夹", style = MiuixTheme.textStyles.button) }
                    }
                    TextButton(text = "恢复默认位置", onClick = { onConfig(config.copy(treeUri = "")) })
                }
                TextField(
                    value = config.downloadRootDir,
                    onValueChange = { onConfig(config.copy(downloadRootDir = it)) },
                    label = "Download 下的子目录（留空即直接放 Download）",
                    useLabelAsPlaceholder = true,
                )
                Text(
                    "最终落点 = 保存位置 / 文件夹模板；选了系统目录就直接写进去，默认模式写媒体库 Download 下，相册立即可见。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        SectionLabel("分片连接数")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 8, 16).forEach { n ->
                    if (n == config.connections) Button(onClick = { onConfig(config.copy(connections = n)) }) { Text("$n", style = MiuixTheme.textStyles.button) }
                    else TextButton(text = "$n", onClick = { onConfig(config.copy(connections = n)) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "大于 16MB 的文件会分片并行下载，服务端不支持 Range 时自动回退单连接",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        SectionLabel("单请求超时")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 90, 180, 300).forEach { n ->
                    if (n == config.timeoutSec) Button(onClick = { onConfig(config.copy(timeoutSec = n)) }) { Text("${n}s", style = MiuixTheme.textStyles.button) }
                    else TextButton(text = "${n}s", onClick = { onConfig(config.copy(timeoutSec = n)) })
                }
            }
        }

        SectionLabel("其它")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
            SwitchPreference(
                title = "保存作品元数据",
                summary = "同时写入一条 json，包含作品信息与直链",
                checked = config.saveJson,
                onCheckedChange = { onConfig(config.copy(saveJson = it)) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AboutSettings(onOpenUrl: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("关于", "ParseHub · Kotlin Multiplatform")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("版本", "0.1.0")
                InfoRow("内核", "shared 共用解析与下载")
                InfoRow("界面", "Miuix（HyperOS 风格）")
                InfoRow("平台", Platform.entries.joinToString("、") { it.label })
                Spacer(Modifier.height(4.dp))
                Text(
                    "解析逻辑参考 z-mio/parsehub 与 jiuhunwl/short_videos 的开源实现，仅用于个人学习与备份；请遵守各平台的服务条款。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { onOpenUrl("https://github.com/w13630039663-bit/parsehub-kmp") }) {
                    Text("打开仓库", style = MiuixTheme.textStyles.button)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
