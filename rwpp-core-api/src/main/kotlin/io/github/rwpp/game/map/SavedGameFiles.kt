/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.map

import io.github.rwpp.saveDir
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** 存档文件名是引擎的加载标识，不能用 nameWithoutExtension 截断旧档名中的点号。 */
data class SavedGameFile(val file: File, val engineName: String = file.name) {
    val name: String get() = file.name
    val mapName: String get() = engineName
    val lastModifiedMillis: Long get() = file.lastModified()
    val fileSizeBytes: Long get() = file.length()

    fun displayName(): String = name.removeSaveSuffix()

    fun toGameMap(id: Int): GameMap = object : GameMap {
        override val id = id
        override val mapName = this@SavedGameFile.mapName
        override val mapType = MapType.SavedGame
        override fun displayName() = this@SavedGameFile.displayName()
        // 原版 load 不补后缀；旧无扩展名存档与 .rwsave 都必须传递真实文件名。
        override fun getMapSuffix() = ""
        override fun openInputStream() = file.inputStream()
        override fun openImageInputStream() = null
    }
}

/**
 * 扫描引擎实际挂载的存档后端。Android 兼容应用私有存储中的旧档，并保留原版路径 tag。
 * 新存档与导入仍写入 primary（[saveDir]）；不能用 primary 路径加载同名的 secondary 存档。
 */
fun scanSavedGameFiles(): List<SavedGameFile> = scanSavedGameFilesFromContext(appKoin.get())

internal fun scanSavedGameFilesFromContext(context: AppContext): List<SavedGameFile> {
    val primary = File(context.externalStoragePath("saves/"))
    val entries = scanSavedGameFiles(primary)
    if (context.isDesktop()) return entries
    val secondary = File(context.internalStoragePath("saves/"))
    if (primary.canonicalFile == secondary.canonicalFile) return entries
    return (entries + scanSavedGameFiles(secondary).map {
        it.copy(engineName = "[INTERNAL-PATH]/${it.name}")
    }).sortedWith(savedGameFileOrder)
}

/** 新鲜扫描指定根目录的直属文件；显式根仅扫描该目录，便于文件导入/测试与边界检查。 */
fun scanSavedGameFiles(root: File): List<SavedGameFile> {
    if (!root.exists()) return emptyList()
    if (!root.isDirectory) throw IOException("存档路径不是文件夹：${root.path}")
    val files = root.listFiles() ?: throw IOException("无法读取存档文件夹：${root.path}")
    return files.asSequence()
        .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && isSavedGameFile(it) }
        .map(::SavedGameFile)
        .sortedWith(savedGameFileOrder)
        .toList()
}

private val savedGameFileOrder = compareByDescending<SavedGameFile> { it.lastModifiedMillis }
    .thenBy { it.name.lowercase() }.thenBy { it.file.absolutePath }

/** 仍展示损坏的 .rwsave 供用户管理；旧无扩展名/自定义扩展名存档只检查原版头标识。 */
fun isSavedGameFile(file: File): Boolean {
    if (!file.isFile || isSaveAuxiliaryName(file.name)) return false
    if (file.name.endsWith(".rwsave", ignoreCase = true)) return true
    return runCatching {
        DataInputStream(file.inputStream().buffered()).use { it.readUTF() == "rustedWarfareSave" }
    }.getOrDefault(false)
}

/** 重命名保留原有扩展名风格，同时移动 `<完整存档文件名>.map`；同名文件绝不覆盖。 */
fun renameSavedGameFile(
    savedGame: SavedGameFile,
    newName: String,
    root: File = File(saveDir),
): SavedGameFile {
    val source = checkedSavedGame(savedGame, root)
    val input = newName.trim()
    requireValidSaveName(input)
    val targetName = if (source.name.endsWith(".rwsave", ignoreCase = true)) {
        input.removeSaveSuffix() + source.name.takeLast(7)
    } else input
    requireValidSaveName(targetName)
    if (targetName == source.name) return savedGame.copy(file = source)
    val target = File(source.parentFile, targetName)
    requireUnusedSaveName(target)
    val companion = companionFile(source)
    checkCompanion(companion, source.parentFile)
    Files.move(source.toPath(), target.toPath())
    try {
        if (companion.exists()) Files.move(companion.toPath(), companionFile(target).toPath())
    } catch (failure: Exception) {
        runCatching { Files.move(target.toPath(), source.toPath()) }
            .exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    val engineDirectory = savedGame.engineName.substringBeforeLast('/', "")
    return SavedGameFile(target, if (engineDirectory.isEmpty()) target.name else "$engineDirectory/${target.name}")
}

/** 仅删除存档根目录内的普通存档及其 .map 伴随文件；不跟随符号链接或递归删除。 */
fun deleteSavedGameFile(savedGame: SavedGameFile, root: File = File(saveDir)) {
    val source = checkedSavedGame(savedGame, root)
    val companion = companionFile(source)
    checkCompanion(companion, source.parentFile)
    Files.delete(source.toPath())
    Files.deleteIfExists(companion.toPath())
}

/**
 * 导入已识别的存档；重名时自动另存并同步复制可选的 .map，绝不覆盖已有进度。
 * 协程调用方用 runInterruptible(IO) 包住本函数；线程中断会撤销尚未发布的复制并清理临时文件。
 */
fun importSavedGameFile(source: File, root: File = File(saveDir)): SavedGameFile {
    require(isSavedGameFile(source)) { "请选择 .rwsave 存档文件。" }
    if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建存档文件夹：${root.path}")
    val canonicalRoot = root.canonicalFile
    val canonicalSource = source.canonicalFile
    if (canonicalSource.parentFile == canonicalRoot) return SavedGameFile(canonicalSource)
    val sourceName = source.name
    requireValidSaveName(sourceName)
    val stem = sourceName.removeSaveSuffix()
    val suffix = sourceName.removePrefix(stem)
    var number = 1
    var target = File(canonicalRoot, sourceName)
    while (saveNameExists(target)) {
        number++
        target = File(canonicalRoot, "$stem ($number)$suffix")
    }
    val companion = companionFile(source)
    if (companion.exists() && !Files.isRegularFile(companion.toPath(), NOFOLLOW_LINKS)) {
        throw IOException("存档地图伴随文件不是普通文件。")
    }
    // 复制期间只出现 .tmp2，扫描与引擎列表不会把未完成存档当成可加载条目。
    val staged = File.createTempFile("rwjs-save-", ".tmp2", canonicalRoot)
    var stagedMap: File? = null
    var publishedMap = false
    try {
        copySaveFileInterruptibly(source, staged)
        if (companion.exists()) {
            val mapStage = File.createTempFile("rwjs-map-", ".tmp2", canonicalRoot)
            stagedMap = mapStage
            copySaveFileInterruptibly(companion, mapStage)
            checkSaveImportInterrupted()
            Files.move(mapStage.toPath(), companionFile(target).toPath())
            publishedMap = true
        }
        checkSaveImportInterrupted()
        Files.move(staged.toPath(), target.toPath())
        target.setLastModified(source.lastModified())
        return SavedGameFile(target)
    } catch (failure: Exception) {
        if (publishedMap) runCatching { Files.deleteIfExists(companionFile(target).toPath()) }
            .exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    } finally {
        staged.delete()
        stagedMap?.delete()
    }
}

private fun checkedSavedGame(savedGame: SavedGameFile, root: File): File {
    val raw = savedGame.file.absoluteFile
    val source = raw.canonicalFile
    val allowed = root.canonicalFile
    require(raw.parentFile.canonicalFile == allowed && source.parentFile == allowed &&
        Files.isRegularFile(raw.toPath(), NOFOLLOW_LINKS) && isSavedGameFile(source)) {
        "只能管理存档文件夹中的普通存档文件。"
    }
    return source
}

private fun requireValidSaveName(name: String) {
    require(name.removeSaveSuffix().isNotBlank() && name != "." && name != ".." && !name.endsWith('.') &&
        !name.endsWith(' ') && name.toByteArray(Charsets.UTF_8).size <= 240 &&
        name.none { it.code < 32 || it in "<>:\"/\\|?*" } &&
        !Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(name) &&
        !isSaveAuxiliaryName(name)) { "存档名称无效，请避免路径符号、保留名称或过长的名称。" }
}

private fun saveNameExists(target: File): Boolean = target.parentFile.listFiles()?.any {
    it.name.equals(target.name, ignoreCase = true) ||
        it.name.equals(companionFile(target).name, ignoreCase = true)
} ?: throw IOException("无法读取存档文件夹：${target.parent}")

private fun requireUnusedSaveName(target: File) {
    if (saveNameExists(target)) throw IOException("已存在同名存档或地图文件，请使用其他名称。")
}

private fun checkCompanion(companion: File, root: File) {
    if (Files.exists(companion.toPath(), NOFOLLOW_LINKS) &&
        (!Files.isRegularFile(companion.toPath(), NOFOLLOW_LINKS) || companion.canonicalFile.parentFile != root)) {
        throw IOException("存档地图伴随文件不是存档目录中的普通文件。")
    }
}

private fun companionFile(file: File) = File(file.parentFile, file.name + ".map")
private fun copySaveFileInterruptibly(source: File, target: File) {
    source.inputStream().use { input ->
        target.outputStream().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkSaveImportInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }
}

private fun checkSaveImportInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("存档导入已取消。")
}

private fun String.removeSaveSuffix(): String = if (endsWith(".rwsave", ignoreCase = true)) dropLast(7) else this
private fun isSaveAuxiliaryName(name: String) = listOf(".map", ".tmp", ".tmp2").any {
    name.endsWith(it, ignoreCase = true)
}
