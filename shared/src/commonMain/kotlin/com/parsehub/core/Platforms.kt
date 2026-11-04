package com.parsehub.core

/** 支持的平台（key 用于作者映射 / Cookie 池 / 配置项） */
enum class Platform(val key: String, val label: String, val dirPrefix: String, val referer: String) {
    DOUYIN("douyin", "抖音", "抖音", "https://www.douyin.com/"),
    KUAISHOU("kuaishou", "快手", "快手", "https://www.kuaishou.com/"),
    WEIBO("weibo", "微博", "微博", "https://weibo.com/"),
    BILIBILI("bilibili", "哔哩哔哩", "B站", "https://www.bilibili.com/"),
    TIEBA("tieba", "贴吧", "贴吧", "https://tieba.baidu.com/"),
    TOUTIAO("toutiao", "今日头条", "头条", "https://www.toutiao.com/"),
    PIPIXIA("pipixia", "皮皮虾", "皮皮虾", "https://h5.pipix.com/"),
    ZUIYOU("zuiyou", "最右", "最右", "https://share.xiaochuankeji.cn/"),
    XHS("xhs", "小红书", "小红书", "https://www.xiaohongshu.com/"),
    DOUBAN("douban", "豆瓣", "豆瓣", "https://www.douban.com/");

    companion object {
        fun ofKey(key: String): Platform? = entries.firstOrNull { it.key == key }

        fun detect(text: String): Platform? {
            val t = text.lowercase()
            return when {
                "douyin.com" in t || "iesdouyin" in t -> DOUYIN
                "kuaishou" in t -> KUAISHOU
                "weibo" in t || "t.cn/" in t -> WEIBO
                "bilibili" in t || "b23.tv" in t || "acg.tv" in t -> BILIBILI
                "tieba.baidu.com" in t || "tieba.com/p/" in t -> TIEBA
                "toutiao" in t -> TOUTIAO
                "pipix" in t -> PIPIXIA
                "izuiyou" in t || "xiaochuankeji" in t -> ZUIYOU
                "xiaohongshu" in t || "xhslink" in t -> XHS
                "douban.com" in t || "douc.cc" in t -> DOUBAN
                else -> null
            }
        }
    }
}

/** 从分享文案里提取第一个 http(s) 链接，并清掉常见跟踪参数 */
fun extractUrl(text: String): String? {
    val raw = Regex("https?://[^\\s\"'<>]+").find(text)?.value
        ?.trimEnd('\'', '"', '，', '。', '；', ',', '.', ';')
        ?: return null
    return stripTracking(raw)
}

private val TRACKING_KEYS = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "share_token", "share_source", "share_medium", "share_plat", "share_app_id",
    "from_source", "from", "is_from_webapp", "sender_device", "web_id",
    "weibo_id", "wxurl", "spm_id_from", "vd_source", "share_from", "sec_source", "wxfid",
)

/** 去掉常见的分享跟踪参数，保留业务参数 */
fun stripTracking(url: String): String {
    val hash = url.indexOf('#')
    val base = if (hash >= 0) url.substring(0, hash) else url
    val frag = if (hash >= 0) url.substring(hash) else ""
    val q = base.indexOf('?')
    if (q < 0) return url
    val path = base.substring(0, q)
    val kept = base.substring(q + 1)
        .split('&')
        .filter { it.isNotEmpty() }
        .filter { pair ->
            val k = pair.substringBefore('=')
            k.lowercase() !in TRACKING_KEYS
        }
    return if (kept.isEmpty()) path + frag else path + "?" + kept.joinToString("&") + frag
}
