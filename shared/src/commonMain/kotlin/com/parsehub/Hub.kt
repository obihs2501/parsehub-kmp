package com.parsehub

import com.parsehub.core.ParseOptions
import com.parsehub.core.ParseResult
import com.parsehub.core.Platform
import com.parsehub.parsers.*

/** 统一门面：识别平台并分发到对应解析器 */
object Hub {
    val platforms: List<Platform> get() = Platform.entries

    fun detect(text: String): Platform? = Platform.detect(text)

    suspend fun parse(text: String, opts: ParseOptions = ParseOptions()): ParseResult {
        val p = detect(text)
            ?: throw RuntimeException("无法识别平台（支持：${platforms.joinToString("、") { it.label }}）")
        return when (p) {
            Platform.DOUYIN -> DouyinParser.parse(text, opts)
            Platform.KUAISHOU -> KuaishouParser.parse(text, opts)
            Platform.WEIBO -> WeiboParser.parse(text, opts)
            Platform.BILIBILI -> BilibiliParser.parse(text, opts)
            Platform.TIEBA -> TiebaParser.parse(text, opts)
            Platform.TOUTIAO -> ToutiaoParser.parse(text, opts)
            Platform.PIPIXIA -> PipixiaParser.parse(text, opts)
            Platform.ZUIYOU -> ZuiyouParser.parse(text, opts)
            Platform.XHS -> XhsParser.parse(text, opts)
            Platform.DOUBAN -> DoubanParser.parse(text, opts)
        }
    }
}
