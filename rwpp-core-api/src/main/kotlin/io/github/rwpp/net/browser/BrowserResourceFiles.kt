/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

enum class BrowserResourceInstallKind { Mod, Map, MapPack }
enum class BrowserResourceInstallError { ModCollection, NoMaps, UnsafeArchive, ArchiveTooLarge, InvalidMap }
class BrowserResourceInstallException(val reason: BrowserResourceInstallError) : IOException(reason.name)

data class BrowserResourceInstallation(val file: File, val mapCount: Int = 0)

/** 下载完整后才提交到引擎目录；地图包独立存放，保留 TMX、缩略图和贴图间的相对路径。 */
object BrowserResourceFiles {
    private const val MAX_ENTRIES = 10000
    private const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024

    fun createPartial(directory: File): File {
        Files.createDirectories(directory.toPath())
        return File.createTempFile(".browser-resource-", ".part", directory)
    }

    @Synchronized
    fun install(partial: File, directory: File, fileName: String, kind: BrowserResourceInstallKind): BrowserResourceInstallation {
        require(browserResourceFileName("", suggestedName = fileName) == fileName) { "Invalid resource filename" }
        val root = directory.canonicalFile
        require(partial.canonicalFile.parentFile == root) { "Download outside resource directory" }
        return when (kind) {
            BrowserResourceInstallKind.Mod -> {
                require(browserResourceType(fileName) in setOf(BrowserResourceType.Mod, BrowserResourceType.Zip))
                if (browserResourceType(fileName) == BrowserResourceType.Zip) validateSingleMod(partial)
                // 引擎及模组管理器识别 .rwmod；完整模组 ZIP 只改后缀，保留原始内容。
                val targetName = fileName.substringBeforeLast('.') + ".rwmod"
                BrowserResourceInstallation(BrowserModFiles.install(partial, root, targetName))
            }
            BrowserResourceInstallKind.Map -> {
                require(browserResourceType(fileName) == BrowserResourceType.Map)
                validateMap(partial)
                BrowserResourceInstallation(commit(partial, root, fileName), 1)
            }
            BrowserResourceInstallKind.MapPack -> {
                require(browserResourceType(fileName) == BrowserResourceType.Zip)
                installMapPack(partial, root, fileName)
            }
        }
    }

    private fun validateSingleMod(archive: File) {
        ZipFile(archive).use { zip ->
            var descriptors = 0
            var packagedMods = 0
            var packagedZips = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                if (name.equals("mod-info.txt", true)) descriptors++
                if (name.endsWith(".rwmod", true)) packagedMods++
                if (name.endsWith(".zip", true)) packagedZips++
            }
            // 一个完整模组可以附带 ZIP 资源；不能仅凭嵌套压缩文件就认定为整合包。
            if (descriptors > 1 || packagedMods > 1 || descriptors == 0 && packagedZips > 1) {
                throw BrowserResourceInstallException(BrowserResourceInstallError.ModCollection)
            }
        }
    }

    private fun installMapPack(archive: File, root: File, fileName: String): BrowserResourceInstallation {
        // 在地图目录外解压，列表不会采样到尚未完成的地图；最终整目录提交。
        val staged = Files.createTempDirectory(root.parentFile.toPath(), ".browser-map-pack-").toFile()
        try {
            var maps = 0
            var extracted = 0L
            ZipFile(archive).use { zip ->
                val entries = buildList {
                    val iterator = zip.entries()
                    while (iterator.hasMoreElements()) {
                        if (size == MAX_ENTRIES) throw BrowserResourceInstallException(BrowserResourceInstallError.ArchiveTooLarge)
                        add(iterator.nextElement())
                    }
                }
                val names = HashSet<String>()
                // 所有路径先校验，再创建文件，拒绝目录穿越和跨平台同名覆盖。
                val validated = entries.map { entry ->
                    val originalName = entry.name.replace('\\', '/').removeSuffix("/")
                    // 引擎加载时拼接小写 .tmx；仅规范地图后缀，其他资源路径保持原样。
                    val name = if (!entry.isDirectory && originalName.endsWith(".tmx", true)) originalName.dropLast(4) + ".tmx" else originalName
                    val parts = name.split('/')
                    if (parts.any { it.isBlank() || it == "." || it == ".." ||
                            browserResourceStem(it) != it } || !names.add(name.lowercase(Locale.ROOT))) {
                        throw BrowserResourceInstallException(BrowserResourceInstallError.UnsafeArchive)
                    }
                    entry to File(staged, name)
                }
                for ((entry, target) in validated) {
                    if (!target.canonicalFile.toPath().startsWith(staged.canonicalFile.toPath())) {
                        throw BrowserResourceInstallException(BrowserResourceInstallError.UnsafeArchive)
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(target.toPath())
                        continue
                    }
                    if (entry.size > MAX_EXTRACTED_BYTES - extracted) {
                        throw BrowserResourceInstallException(BrowserResourceInstallError.ArchiveTooLarge)
                    }
                    Files.createDirectories(target.parentFile.toPath())
                    var copied = 0L
                    val crc = CRC32()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                extracted += count
                                copied += count
                                if (extracted > MAX_EXTRACTED_BYTES) {
                                    throw BrowserResourceInstallException(BrowserResourceInstallError.ArchiveTooLarge)
                                }
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (copied != entry.size || crc.value != entry.crc) throw IOException("Incomplete map archive")
                    if (target.extension.equals("tmx", true)) {
                        validateMap(target)
                        maps++
                    }
                }
            }
            if (maps == 0) throw BrowserResourceInstallException(BrowserResourceInstallError.NoMaps)
            val installed = commit(staged, root, fileName.substringBeforeLast('.'))
            return BrowserResourceInstallation(installed, maps)
        } finally {
            // staged 为本方法刚创建的独立临时目录；已提交时原路径不存在。
            if (staged.exists()) staged.deleteRecursively()
        }
    }

    private class MapRootFound : SAXException()

    private fun validateMap(file: File) {
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External map entity") }
            reader.contentHandler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                    if (localName == "map" || qName == "map") throw MapRootFound()
                    throw SAXException("Not a TMX map")
                }
            }
            // 仅验证 XML 根节点，地图解析仍由游戏完成，不读取外部实体或展开地图数据。
            file.inputStream().use { reader.parse(org.xml.sax.InputSource(it)) }
        } catch (_: MapRootFound) {
            return
        } catch (_: Exception) {
            throw BrowserResourceInstallException(BrowserResourceInstallError.InvalidMap)
        }
        throw BrowserResourceInstallException(BrowserResourceInstallError.InvalidMap)
    }

    private fun commit(source: File, root: File, fileName: String): File {
        val stem = if (source.isDirectory) fileName else fileName.substringBeforeLast('.')
        val suffix = if (source.isDirectory) "" else ".${fileName.substringAfterLast('.')}"
        for (index in 1..10000) {
            val name = if (index == 1) fileName else "$stem ($index)$suffix"
            if (root.list()?.any { it.equals(name, ignoreCase = true) } == true) continue
            val target = File(root, name)
            try {
                Files.move(source.toPath(), target.toPath())
                return target
            } catch (_: FileAlreadyExistsException) { }
        }
        throw IOException("Too many resources with the same filename")
    }
}
