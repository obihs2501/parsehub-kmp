package com.parsehub.sidecar

import com.parsehub.core.AppConfig
import com.parsehub.core.HistoryStore
import com.parsehub.core.Naming
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Settings(
    val config: AppConfig = AppConfig(),
    val outDir: String = Path.of(System.getProperty("user.home"), "Downloads", "ParseHub").toString(),
    val galleryRoots: List<String> = emptyList(),
)

class Store(val root: Path, legacy: Path = Path.of(System.getProperty("user.home"), ".parsehub")) {
    private val settingsPath = root.resolve("settings.json")
    val history = HistoryStore(FileSystem.SYSTEM, root.resolve("history.json").toString().toPath())
    @Volatile var settings: Settings
        private set

    init {
        Files.createDirectories(root)
        if (!Files.exists(settingsPath)) {
            val config = runCatching { wireJson.decodeFromString<AppConfig>(Files.readString(legacy.resolve("config.json"))) }.getOrDefault(AppConfig())
            val output = runCatching { Files.readString(legacy.resolve("studio-settings.json")).trim() }.getOrDefault("")
            val initial = Settings(config = config, outDir = output.takeIf { it.isNotBlank() } ?: Settings().outDir)
            val oldHistory = legacy.resolve("history.json")
            if (Files.isRegularFile(oldHistory) && !Files.exists(root.resolve("history.json"))) Files.copy(oldHistory, root.resolve("history.json"))
            saveFile(settingsPath, wireJson.encodeToString(Settings.serializer(), initial))
        }
        settings = wireJson.decodeFromString(Files.readString(settingsPath))
    }

    @Synchronized fun update(value: Settings): Settings {
        require(value.config.theme in setOf("system", "light", "dark")) { "无效主题" }
        require(value.config.defaultTier in setOf("origin", "h264", "small", "none")) { "无效默认档位" }
        Naming.validate(value.config.folderTemplate)?.let { error(it) }
        Naming.validate(value.config.fileTemplate)?.let { error(it) }
        require(value.outDir.isNotBlank()) { "保存路径不能为空" }
        val out = Path.of(value.outDir).toAbsolutePath().normalize()
        Files.createDirectories(out)
        require(Files.isDirectory(out) && Files.isWritable(out)) { "保存目录不可写" }
        val next = value.copy(outDir = out.toString(), galleryRoots = value.galleryRoots.distinct())
        saveFile(settingsPath, wireJson.encodeToString(Settings.serializer(), next))
        settings = next
        return next
    }

    private fun saveFile(path: Path, text: String) {
        val tmp = Files.createTempFile(root, "settings-", ".tmp")
        try {
            Files.writeString(tmp, text)
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
}
