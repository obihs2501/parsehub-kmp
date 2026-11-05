package com.parsehub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.parsehub.net.Http
import com.parsehub.core.Fmt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Extra clearance inside page scroll content; desktop and non-floating hosts keep zero. */
val LocalPageBottomPadding = staticCompositionLocalOf { 0.dp }

/** Miuix 风格的动效基元：轻阻尼弹簧，不做夸张回弹 */
val PageSpring: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.92f, stiffness = 600f)

fun <T> softSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 500f)

val EnterFade = fadeIn(animationSpec = tween(180))
val ExitFade = fadeOut(animationSpec = tween(120))

/** 页面头：大标题 + 说明 */
@Composable
fun PageHero(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
        Text(title, style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onBackground)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 区块标题（Miuix 规范：SmallTitle 放在 Card 外，紧贴 Card 上方） */
@Composable
fun SectionLabel(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
    }
}

/** Shared request policy for parsed covers, thumbnails and the Android full-size viewer. */
@Composable
fun parsedImageRequest(url: String?, referer: String): ImageRequest {
    val context = LocalPlatformContext.current
    return androidx.compose.runtime.remember(context, url, referer) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(
                NetworkHeaders.Builder()
                    .set("User-Agent", Http.UA)
                    .set("Referer", referer)
                    .build(),
            )
            .build()
    }
}

/** A bounded, uncropped cover; portrait images retain their entire frame. */
@Composable
fun Thumb(
    url: String?,
    modifier: Modifier = Modifier,
    radius: Int = 12,
    referer: String = "",
) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(MiuixTheme.colorScheme.surface)) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = parsedImageRequest(url, referer),
                contentDescription = "封面，点击查看大图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        }
    }
}

/** 带开关的显隐动画（结果卡、进度、列表项都用它） */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = softSpring()),
        exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = softSpring()),
    ) {
        content()
    }
}

/** 进度一行：x/y · 速度 */
@Composable
fun ProgressLine(done: Long, total: Long, speed: Long) {
    Text(
        "${Fmt.size(done)} / ${Fmt.size(total)}" + if (speed > 0) " · ${Fmt.size(speed)}/s" else "",
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    )
}

@Composable
fun EmptyHint(text: String) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
}

@Composable
fun Dot(active: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error),
    )
}
