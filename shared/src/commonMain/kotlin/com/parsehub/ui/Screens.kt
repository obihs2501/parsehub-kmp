package com.parsehub.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.parsehub.HubController
import com.parsehub.core.Fmt
import com.parsehub.core.HistoryEntry
import com.parsehub.core.Platform
import com.parsehub.core.dateTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 解析工作台 */
@Composable
fun ParseScreen(
    c: HubController,
    readClipboard: () -> String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("解析工作台", "粘贴分享文案或链接，选好档位再下载")

        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = c.link,
                    onValueChange = { c.link = it },
                    label = "粘贴链接 / 分享文案",
                    useLabelAsPlaceholder = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { c.parse() },
                        enabled = !c.busy && c.link.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (c.parsing) "解析中…" else "解析", style = MiuixTheme.textStyles.button)
                    }
                    Button(
                        onClick = { readClipboard()?.let { if (it.isNotBlank()) c.link = it } },
                        enabled = !c.busy,
                    ) {
                        Text("粘贴", style = MiuixTheme.textStyles.button)
                    }
                }
                val p = Platform.detect(c.link)
                Text(
                    if (p != null) "已识别：${p.label}" else "支持：" + com.parsehub.Hub.platforms.joinToString("、") { it.label },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (p != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Reveal(c.parsing) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在解析…",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Reveal(c.error.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(c.error, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
            }
        }

        Reveal(c.error.isEmpty() && c.note.isNotEmpty() && !c.parsing) {
            Text(
                c.note,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        val r = c.result
        if (r != null) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!r.cover.isNullOrEmpty()) {
                        Thumb(r.cover, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                    }
                    Text(r.title, style = MiuixTheme.textStyles.headline1)
                    InfoRow("平台", "${r.platform.label} · @${r.author}")
                    if (c.creator.isNotBlank() && c.creator != r.author) InfoRow("归档作者", c.creator)
                    InfoRow("发布", if (r.date.isBlank()) "未知" else r.date)
                    if (r.durSec > 0) InfoRow("时长", Fmt.dur(r.durSec))
                    if (r.statLine.isNotEmpty()) InfoRow("数据", r.statLine)
                    if (r.images.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "媒体预览 · ${r.images.size} 张" +
                                if (r.tiers.isNotEmpty()) " · 视频 ${r.tiers.size} 档" else "",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            r.images.take(12).forEach { im ->
                                AsyncImage(
                                    model = im.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)),
                                )
                            }
                            if (r.images.size > 12) {
                                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        "+${r.images.size - 12}",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (r.tiers.isNotEmpty()) {
                SectionLabel("下载档位 · 已选 ${c.pick.size}/${r.tiers.size}")
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    r.tiers.forEach { t -> TierRow(t.idx, t.gear, t.isOrigin, tierBits(t), c.pick.contains(t.idx)) { c.togglePick(t.idx) } }
                }
            }

            if (r.images.isNotEmpty()) {
                SectionLabel("图集 · ${r.images.size} 张")
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
                    SwitchPreference(
                        title = "下载全部图片",
                        summary = "实况照片会同时下载动态视频",
                        checked = c.imagesOn,
                        onCheckedChange = { c.imagesOn = it },
                    )
                }
            }

            if (r.aux.isNotEmpty()) {
                SectionLabel("附属资源")
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp)) {
                    r.aux.forEachIndexed { i, a ->
                        SwitchPreference(
                            title = a.label,
                            summary = "${a.spec} · ${a.urls.size} 条线路",
                            checked = c.auxOn.getOrElse(i) { false },
                            onCheckedChange = { c.toggleAux(i) },
                        )
                    }
                }
            }

            if (c.downloading) {
                SectionLabel("下载进度")
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val frac by animateFloatAsState(
                            targetValue = if (c.curTotal > 0) (c.curDone.toFloat() / c.curTotal).coerceIn(0f, 1f) else 0f,
                            label = "progress",
                        )
                        LinearProgressIndicator(
                            progress = if (c.curTotal > 0) frac else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${c.doneJobs}/${c.totalJobs} · ${c.curName}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        ProgressLine(c.curDone, c.curTotal, c.speed)
                        Button(
                            onClick = { c.cancel() },
                            enabled = !c.cancelling,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (c.cancelling) "正在取消…" else "取消下载", style = MiuixTheme.textStyles.button)
                        }
                        Text(
                            if (c.cancelling) {
                                "已收到取消，正在停当前分片，稍等一两秒"
                            } else {
                                "取消只停掉没下完的部分，已保存的文件保留"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { c.startDownload() },
                    enabled = c.canDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载", style = MiuixTheme.textStyles.button)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun tierBits(t: com.parsehub.core.Tier): String {
    val bits = mutableListOf<String>()
    if (t.w > 0 && t.h > 0) bits += "${t.w}x${t.h}"
    if (t.size > 0) bits += Fmt.size(t.size)
    if (t.bps > 0) bits += Fmt.bps(t.bps)
    if (t.fps > 0) bits += Fmt.fps(t.fps, t.fpsEst)
    if (t.w > 0 || t.h > 0) bits += if (t.h265) "H265" else "H264"
    return bits.joinToString(" · ")
}

@Composable
private fun TierRow(
    idx: Int,
    gear: String,
    isOrigin: Boolean,
    bits: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                gear + if (isOrigin) " · 原画" else "",
                style = MiuixTheme.textStyles.main,
                color = if (isOrigin) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
            )
            if (bits.isNotEmpty()) {
                Text(
                    bits,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 下载管理（本次运行的任务） */
@Composable
fun TasksScreen(c: HubController) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("下载管理", "本次运行的任务与进度")
        if (c.tasks.isEmpty()) {
            EmptyHint("还没有下载任务")
        } else {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                c.tasks.asReversed().forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MiuixTheme.textStyles.main)
                            Text(
                                t.relDir + if (t.error.isNotBlank()) " · ${t.error}" else "",
                                style = MiuixTheme.textStyles.footnote2,
                                color = when (t.status) {
                                    "fail" -> MiuixTheme.colorScheme.error
                                    "done" -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    else -> MiuixTheme.colorScheme.primary
                                },
                            )
                            if (t.status == "running") {
                                val frac = if (c.curTotal > 0) {
                                    (c.curDone.toFloat() / c.curTotal).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                LinearProgressIndicator(
                                    progress = frac,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                                Spacer(Modifier.height(4.dp))
                                ProgressLine(c.curDone, c.curTotal, c.speed)
                            }
                        }
                        if (t.status == "running") {
                            TextButton(
                                text = if (c.cancelling) "取消中" else "取消",
                                onClick = { c.cancel() },
                                enabled = !c.cancelling,
                            )
                        } else {
                            Text(
                                if (t.status == "done") "完成" else "失败",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 解析 / 下载记录：平台 → 作者 两级折叠 */
@Composable
fun HistoryScreen(c: HubController, onExportCsv: (String) -> Unit) {
    val tick = c.historyTick
    val entries = remember(tick) { c.historyEntries() }
    var expandedPlatform by remember { mutableStateOf<String?>(null) }
    var expandedAuthor by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHero("解析记录", "共 ${entries.size} 条 · 按平台与作者分组")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(text = "导出 CSV", onClick = { onExportCsv(c.exportCsv()) }, enabled = entries.isNotEmpty())
            TextButton(text = "清空", onClick = { c.clearHistory() }, enabled = entries.isNotEmpty())
        }
        if (entries.isEmpty()) {
            EmptyHint("还没有记录")
        } else {
            val byPlatform = entries.groupBy { it.platform.ifBlank { "其他" } }
            for ((platformKey, list) in byPlatform) {
                val platformLabel = Platform.ofKey(platformKey)?.label ?: platformKey
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    GroupRow(
                        title = platformLabel,
                        summary = "${list.size} 条",
                        expanded = expandedPlatform == platformKey,
                    ) {
                        expandedPlatform = if (expandedPlatform == platformKey) null else platformKey
                        expandedAuthor = null
                    }
                    Reveal(expandedPlatform == platformKey) {
                        Column {
                            val byAuthor = list.groupBy { it.creator.ifBlank { it.author.ifBlank { "未知作者" } } }
                            for ((author, items) in byAuthor) {
                                val key = "$platformKey/$author"
                                GroupRow(title = author, summary = "${items.size} 条", expanded = expandedAuthor == key, indent = true) {
                                    expandedAuthor = if (expandedAuthor == key) null else key
                                }
                                Reveal(expandedAuthor == key) {
                                    Column {
                                        items.forEach { HistoryRow(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupRow(
    title: String,
    summary: String,
    expanded: Boolean,
    indent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 12.dp else 0.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.main)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextButton(text = if (expanded) "收起" else "展开", onClick = onClick)
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(e.status == "ok")
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title.ifBlank { if (e.kind == "parse") "解析" else "下载" },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            Text(
                dateTime(e.ts) +
                    (if (e.kind == "download") " · 下载" else " · 解析") +
                    (if (e.files > 0) " · ${e.files} 个文件" else "") +
                    (if (e.dir.isNotBlank()) " · ${e.dir}" else ""),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
