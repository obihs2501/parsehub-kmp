package io.github.obihs2501.parsehub.core

/** 一个可下载档位（画质 / 分P / 平台单档） */
data class Tier(
    val idx: Int,
    val gear: String,
    val w: Int,
    val h: Int,
    val bps: Long,
    val size: Long,
    val h265: Boolean,
    val fps: Double,
    val fpsEst: Boolean,
    val isOrigin: Boolean,
    val urls: List<String>,
    val uri: String? = null,
) {
    val res: String get() = "${w}x$h"
}

/** 图集单张（含实况视频直链） */
data class ImageItem(
    val index: Int,
    val url: String,
    val ext: String,
    val live: String?,
    val w: Int,
    val h: Int,
)

/** 附属资源（原声 / 配乐 / 带水印等） */
data class AuxItem(
    val kind: String,
    val label: String,
    val spec: String,
    val ext: String,
    val urls: List<String>,
)

/** 一次解析的完整结果（各平台统一契约） */
data class ParseResult(
    val platform: Platform,
    val id: String,
    val title: String,
    val author: String,
    val date: String,
    val durSec: Double,
    val statLine: String,
    val width: Int,
    val height: Int,
    val cover: String?,
    val referer: String,
    val tiers: List<Tier>,
    val aux: List<AuxItem>,
    val images: List<ImageItem>,
)

/** 解析选项（cookie 均为可选） */
data class ParseOptions(
    val defaultTier: String = "origin",   // origin | h264 | small | none
    val cookieWeibo: String = "",
    val cookieBili: String = "",
    val cookieXhs: String = "",
    val cookieToutiao: String = "",
)
