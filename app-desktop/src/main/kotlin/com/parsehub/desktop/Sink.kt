package com.parsehub.desktop

import com.parsehub.FileSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/** 桌面落点：<输出目录>/<relDir>/<name>；同名不覆盖 */
class DesktopSink(private val outDirProvider: () -> File) : FileSink {

    override suspend fun exists(relDir: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(File(outDirProvider(), relDir), fileName).exists()
    }

    override suspend fun accept(tempPath: Path, relDir: String, fileName: String, mime: String) {
        withContext(Dispatchers.IO) {
            val target = File(File(outDirProvider(), relDir), fileName)
            target.parentFile?.mkdirs()
            val fs = FileSystem.SYSTEM
            val dest = target.absolutePath.toPath()
            fs.delete(dest, mustExist = false)
            try {
                fs.atomicMove(tempPath, dest)
            } catch (_: Exception) {
                fs.copy(tempPath, dest)
                fs.delete(tempPath, mustExist = false)
            }
        }
    }
}
