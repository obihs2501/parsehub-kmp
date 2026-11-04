package com.parsehub.core

import kotlinx.serialization.Serializable

/** 作者映射：把同一创作者在不同平台 / 不同账号的 ID 或昵称映射到同一个「归档作者」 */
@Serializable
data class AuthorAlias(
    val platform: String = "",
    val account: String = "",
    val creator: String = "",
    val matchBy: String = "id",   // id | name
)

/** Cookie 池条目：按平台多条，可启停，每次解析轮换 */
@Serializable
data class CookieEntry(
    val platform: String = "",
    val label: String = "",
    val cookie: String = "",
    val enabled: Boolean = true,
)

/** 应用配置（持久化为 JSON） */
@Serializable
data class AppConfig(
    val defaultTier: String = "origin",          // origin | h264 | small | none
    val theme: String = "system",                // system | light | dark
    val folderTemplate: String = Naming.DEFAULT_FOLDER,
    val fileTemplate: String = Naming.DEFAULT_FILE,
    val mergeAuthorFolders: Boolean = true,      // 同一作者归档进同一文件夹，不建 _2
    val aliases: List<AuthorAlias> = emptyList(),
    val cookies: List<CookieEntry> = emptyList(),
    val connections: Int = 4,
    val timeoutSec: Int = 180,
    val imagesByDefault: Boolean = true,         // 解析后默认勾选图集
    val saveJson: Boolean = false,               // 是否同时保存作品元数据 json
) {
    /** 取出该平台当前应使用的 Cookie（轮换） */
    fun cookieFor(platformKey: String, turn: Int): String {
        val pool = cookies.filter { it.platform == platformKey && it.enabled && it.cookie.isNotBlank() }
        if (pool.isEmpty()) return ""
        return pool[(turn % pool.size + pool.size) % pool.size].cookie
    }

    /** 按解析轮次生成解析选项 */
    fun optionsFor(platformKey: String, turn: Int): ParseOptions {
        val map = Platform.entries
            .associate { it.key to cookieFor(it.key, turn) }
            .filterValues { it.isNotEmpty() }
        return ParseOptions(
            defaultTier = defaultTier,
            cookieWeibo = map["weibo"].orEmpty(),
            cookieBili = map["bilibili"].orEmpty(),
            cookieXhs = map["xhs"].orEmpty(),
            cookieToutiao = map["toutiao"].orEmpty(),
            cookies = map,
        )
    }

    /** 归档作者：命中映射返回映射名，否则返回原名 */
    fun creatorOf(platformKey: String, authorId: String, authorName: String): String {
        for ((matchBy, value) in listOf("id" to authorId, "name" to authorName)) {
            if (value.isBlank()) continue
            val hit = aliases.firstOrNull { a ->
                a.platform == platformKey &&
                    a.matchBy.ifBlank { "id" } == matchBy &&
                    a.account == value
            }
            if (hit != null) return hit.creator.ifBlank { authorName }
        }
        return authorName
    }

    companion object {
        /** 旧版单条 Cookie 设置迁移到 Cookie 池 */
        fun migrateLegacy(
            defaultTier: String,
            theme: String,
            weibo: String,
            bili: String,
            xhs: String,
            toutiao: String,
        ): AppConfig {
            val entries = mutableListOf<CookieEntry>()
            if (weibo.isNotBlank()) entries += CookieEntry("weibo", "微博", weibo)
            if (bili.isNotBlank()) entries += CookieEntry("bilibili", "B站", bili)
            if (xhs.isNotBlank()) entries += CookieEntry("xhs", "小红书", xhs)
            if (toutiao.isNotBlank()) entries += CookieEntry("toutiao", "头条", toutiao)
            return AppConfig(defaultTier = defaultTier, theme = theme, cookies = entries)
        }
    }
}
