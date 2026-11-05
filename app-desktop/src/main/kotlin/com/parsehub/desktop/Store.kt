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
    private val settingsFile = File(dir, "studio-settings.json")

    fun loadConfig(): AppConfig = configStore.load()

    fun saveConfig(cfg: AppConfig) = configStore.save(cfg)

    fun history(): HistoryStore = historyStore

    fun defaultOutDir(): String {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return if (downloads.isDirectory) downloads.absolutePath else System.getProperty("user.home")
    }

    /** 保存目录是桌面端独立设置，和共享命名模板分开存，重启后保持 */
    fun loadOutDir(): String = try {
        val path = settingsFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        path.ifBlank { defaultOutDir() }.also { File(it).mkdirs() }
    } catch (_: Exception) {
        defaultOutDir()
    }

    fun saveOutDir(dir: String) {
        try {
            dir.mkdirs()
            this.dir.mkdirs()
            settingsFile.writeText(dir)
        } catch (_: Exception) {
        }
    }

    /** 图库偏好：列数 / 排序 / 类型 / 显示模式 / 侧栏宽度 / 是否折叠 */
    data class GalleryPrefs(
        val columns: Int = 4,
        val sort: String = "newest",
        val filter: String = "all",
        val view: String = "thumbnail",
        val fit: String = "cover",
        val sidebar: Boolean = true,
        val panel: Boolean = false,
    )

    private val galleryFile = File(dir, "gallery-prefs.json")

    fun loadGallery(): GalleryPrefs {
        val def = GalleryPrefs()
        if (!galleryFile.isFile) return def
        return try {
            val map = Regex("\"(\\w+)\":\\s*(\"([^\"]*)\"|true|false|\\d+)")
                .findAll(galleryFile.readText())
                .associate { m ->
                    val raw = m.groupValues[2]
                    m.groupValues[1] to when {
                        raw == "true" -> true
                        raw == "false" -> false
                        raw.startsWith('"') -> m.groupValues[3]
                        else -> raw.toIntOrNull() ?: 0
                    }
                }
            GalleryPrefs(
                columns = (map["columns"] as? Int ?: def.columns).coerceIn(2, 8),
                sort = map["sort"] as? String ?: def.sort,
                filter = map["filter"] as? String ?: def.filter,
                view = map["view"] as? String ?: def.view,
                fit = map["fit"] as? String ?: def.fit,
                sidebar = map["sidebar"] as? Boolean ?: def.sidebar,
                panel = map["panel"] as? Boolean ?: def.panel,
            )
        } catch (_: Exception) {
            def
        }
    }

    fun saveGallery(p: GalleryPrefs) {
        try {
            dir.mkdirs()
            galleryFile.writeText(
                """{"columns":${p.columns},"sort":"${p.sort}","filter":"${p.filter}","view":"${p.view}","fit":"${p.fit}","sidebar":${p.sidebar},"panel":${p.panel}}""",
            )
        } catch (_: Exception) {
        }
    }
}
