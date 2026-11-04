package com.parsehub.app

import android.content.Context
import com.parsehub.core.AppConfig
import com.parsehub.core.ConfigStore
import com.parsehub.core.HistoryStore
import okio.FileSystem
import okio.Path.Companion.toPath

/** Android 侧配置与记录存储：filesDir 下的两个 json */
class AndroidStore(private val ctx: Context) {

    private val fs = FileSystem.SYSTEM
    private val configStore = ConfigStore(fs, (ctx.filesDir.absolutePath + "/parsehub_config.json").toPath())
    private val historyStore = HistoryStore(fs, (ctx.filesDir.absolutePath + "/parsehub_history.json").toPath())

    /** 读取配置，并把早期版本存在 SharedPreferences 里的 Cookie 迁移进 Cookie 池 */
    fun loadConfig(): AppConfig {
        val cfg = configStore.load()
        val prefs = ctx.getSharedPreferences("parsehub_settings", Context.MODE_PRIVATE)
        val hasLegacy = prefs.contains("cookieBili") || prefs.contains("cookieWeibo") ||
            prefs.contains("cookieXhs") || prefs.contains("cookieToutiao")
        if (!hasLegacy) return cfg
        val migrated = AppConfig.migrateLegacy(
            defaultTier = prefs.getString("defaultTier", "origin") ?: "origin",
            theme = prefs.getString("theme", "system") ?: "system",
            weibo = prefs.getString("cookieWeibo", "") ?: "",
            bili = prefs.getString("cookieBili", "") ?: "",
            xhs = prefs.getString("cookieXhs", "") ?: "",
            toutiao = prefs.getString("cookieToutiao", "") ?: "",
        )
        prefs.edit().clear().apply()
        val merged = cfg.copy(
            defaultTier = if (cfg.defaultTier == "origin") migrated.defaultTier else cfg.defaultTier,
            theme = if (cfg.theme == "system") migrated.theme else cfg.theme,
            cookies = cfg.cookies + migrated.cookies,
        )
        configStore.save(merged)
        return merged
    }

    fun saveConfig(cfg: AppConfig) = configStore.save(cfg)

    fun history(): HistoryStore = historyStore
}
