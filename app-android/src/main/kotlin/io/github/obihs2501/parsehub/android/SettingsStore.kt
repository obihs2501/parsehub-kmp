package io.github.obihs2501.parsehub.android

import android.content.Context
import io.github.obihs2501.parsehub.core.ParseOptions

data class AppSettings(
    val defaultTier: String = "origin",
    val theme: String = "system",        // system | light | dark
    val cookieWeibo: String = "",
    val cookieBili: String = "",
    val cookieXhs: String = "",
    val cookieToutiao: String = "",
) {
    fun toOptions(): ParseOptions = ParseOptions(
        defaultTier = defaultTier,
        cookieWeibo = cookieWeibo,
        cookieBili = cookieBili,
        cookieXhs = cookieXhs,
        cookieToutiao = cookieToutiao,
    )
}

object SettingsStore {
    private const val NAME = "parsehub_settings"

    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return AppSettings(
            defaultTier = p.getString("defaultTier", "origin") ?: "origin",
            theme = p.getString("theme", "system") ?: "system",
            cookieWeibo = p.getString("cookieWeibo", "") ?: "",
            cookieBili = p.getString("cookieBili", "") ?: "",
            cookieXhs = p.getString("cookieXhs", "") ?: "",
            cookieToutiao = p.getString("cookieToutiao", "") ?: "",
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("defaultTier", s.defaultTier)
            .putString("theme", s.theme)
            .putString("cookieWeibo", s.cookieWeibo)
            .putString("cookieBili", s.cookieBili)
            .putString("cookieXhs", s.cookieXhs)
            .putString("cookieToutiao", s.cookieToutiao)
            .apply()
    }
}
