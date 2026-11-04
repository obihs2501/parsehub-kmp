package io.github.obihs2501.parsehub.core

/** 支持的平台（v1：8 个；解析逻辑移植自 z-mio/parsehub 与 jiuhunwl/short_videos） */
enum class Platform(val label: String, val dirPrefix: String, val referer: String) {
    DOUYIN("抖音", "抖音", "https://www.douyin.com/"),
    KUAISHOU("快手", "快手", "https://www.kuaishou.com/"),
    WEIBO("微博", "微博", "https://weibo.com/"),
    BILIBILI("哔哩哔哩", "B站", "https://www.bilibili.com/"),
    TOUTIAO("今日头条", "头条", "https://www.toutiao.com/"),
    PIPIXIA("皮皮虾", "皮皮虾", "https://h5.pipix.com/"),
    ZUIYOU("最右", "最右", "https://share.xiaochuankeji.cn/"),
    XHS("小红书", "小红书", "https://www.xiaohongshu.com/");

    companion object {
        fun detect(text: String): Platform? {
            val t = text.lowercase()
            return when {
                "douyin.com" in t || "iesdouyin" in t -> DOUYIN
                "kuaishou" in t -> KUAISHOU
                "weibo" in t || "t.cn/" in t -> WEIBO
                "bilibili" in t || "b23.tv" in t || "acg.tv" in t -> BILIBILI
                "toutiao" in t -> TOUTIAO
                "pipix" in t -> PIPIXIA
                "izuiyou" in t || "xiaochuankeji" in t -> ZUIYOU
                "xiaohongshu" in t || "xhslink" in t -> XHS
                else -> null
            }
        }
    }
}

/** 从分享文案里提取第一个 http(s) 链接 */
fun extractUrl(text: String): String? =
    Regex("https?://[^\\s\"'<>]+").find(text)?.value?.trimEnd('\'', '"', '，', '。', '；', ',', '.', ';')
