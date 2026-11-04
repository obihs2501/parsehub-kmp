package com.parsehub.desktop

import com.parsehub.core.AppConfig
import com.parsehub.core.ConfigStore
import com.parsehub.core.HistoryStore
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/** 桌面侧配置与记录：~/.parsehub/ 下两个 json */
class DesktopStore(home: File = File(System.getProperty("user.home"))) {

    private val dir = File(home, ".parsehub")
    private val fs = FileSystem.SYSTEM
    private val configStore = ConfigStore(fs, (dir.absolutePath + "/config.json").toPath())
    private val historyStore = HistoryStore(fs, (dir.absolutePath + "/history.json").toPath())

    fun loadConfig(): AppConfig = configStore.load()

    fun saveConfig(cfg: AppConfig) = configStore.save(cfg)

    fun history(): HistoryStore = historyStore

    fun defaultOutDir(): String {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return if (downloads.isDirectory) downloads.absolutePath else System.getProperty("user.home")
    }
}
