package com.parsehub.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController

/** 从任意 Context 里找到宿主 Activity（Compose 的 LocalContext 可能是 ContextWrapper） */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 系统栏图标配色：darkIcons = true 表示深色图标（配浅色背景），false 表示白色图标（配深色背景）。
 * 状态栏和导航栏一起设，避免看图时黑底白图标、其他页面白底黑图标来回打架。
 */
internal fun setSystemBarsDarkIcons(window: Window?, darkIcons: Boolean) {
    val w = window ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val c = w.insetsController ?: return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        c.setSystemBarsAppearance(if (darkIcons) mask else 0, mask)
    } else {
        @Suppress("DEPRECATION")
        val v: View = w.decorView
        @Suppress("DEPRECATION")
        val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        v.systemUiVisibility = if (darkIcons) v.systemUiVisibility or flags else v.systemUiVisibility and flags.inv()
    }
}
