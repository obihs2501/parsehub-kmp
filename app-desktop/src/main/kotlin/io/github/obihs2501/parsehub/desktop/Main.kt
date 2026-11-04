package io.github.obihs2501.parsehub.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ParseHub Studio",
        state = rememberWindowState(width = 1120.dp, height = 800.dp),
    ) {
        val themeController = remember { ThemeController(ColorSchemeMode.System) }
        MiuixTheme(controller = themeController) {
            DesktopApp()
        }
    }
}
