/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.map.browserCustomMapPaths
import io.github.rwpp.net.browser.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BrowserResourceDownloadTest {
    private val map = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><map version=\"1.0\" width=\"1\" height=\"1\"/>".toByteArray()

    @Test fun classifiesAllSupportedExtensionsFromTheResponseFilename() {
        assertEquals("hello.tmx", browserResourceFileName("https://example.com/hello.TMX?token=secret"))
        assertEquals("中文地图.zip", browserResourceFileName("https://example.com/download", "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%E5%9C%B0%E5%9B%BE.ZIP"))
        assertEquals("mod.rwmod", browserResourceFileName("blob:https://example.com/id", suggestedName = "mod.RWMOD"))
        assertEquals("actual.tmx", browserResourceFileName("https://example.com/looks.zip", "attachment; filename=actual.tmx", "wrong.rwmod"))
        assertNull(browserResourceFileName("https://example.com/looks.tmx", "attachment; filename=actual.png"))
        assertNull(browserResourceFileName("https://example.com/download?name=map.tmx"))
        assertEquals("_CON.tmx", browserResourceFileName("", suggestedName = "../CON.tmx"))
        assertEquals("map.tmx", browserResourceFileName("", suggestedName = ".tmx"))
        assertEquals(BrowserResourceType.Zip, browserResourceType("maps.ZIP"))
    }

    @Test fun standaloneMapKeepsExistingFilesAndUsesTheMapDirectory() = inDirectory { root ->
        val maps = File(root, "maps").apply { mkdirs() }
        val existing = File(maps, "map.tmx").apply { writeText("keep existing") }
        val partial = BrowserResourceFiles.createPartial(maps).apply { writeBytes(map) }
        val installed = BrowserResourceFiles.install(partial, maps, "map.tmx", BrowserResourceInstallKind.Map)
        assertEquals("map (2).tmx", installed.file.name)
        assertContentEquals(map, installed.file.readBytes())
        assertEquals("keep existing", existing.readText())
        assertEquals(1, installed.mapCount)
        assertFalse(partial.exists())
        assertFailsWith<IllegalArgumentException> {
            BrowserResourceFiles.install(File(root, "outside.part"), maps, "map.tmx", BrowserResourceInstallKind.Map)
        }
    }

    @Test fun mapImagesAreRecognizedFromTheFilenameInsteadOfTheUrlDirectoryOrMimeType() {
        assertEquals("New 混乱街区_map.png", browserResourceFileName("https://example.com/download",
            "attachment; filename=\"New 混乱街区_map.PNG\""))
        for (extension in listOf("PNG", "JPG", "JPEG", "WEBP", "GIF", "BMP")) {
            assertEquals("level_MAP.${extension.lowercase()}",
                browserResourceFileName("https://example.com/level_MAP.$extension?token=secret"))
        }
        assertEquals(BrowserResourceType.MapThumbnail, browserResourceType("MapPreview.png"))
        assertEquals("level_map.jpg", browserResourceFileName("blob:https://example.com/id", suggestedName = "level_map.JPG"))
        assertNull(browserResourceFileName("https://example.com/map/avatar.png"))
        assertNull(browserResourceFileName("https://example.com/download?name=level_map.png"))
        assertNull(browserResourceFileName("https://example.com/level_map.png", "attachment; filename=avatar.png"))
        assertNull(browserResourceFileName("https://example.com/level_map.svg"))
        assertNull(browserResourceFileName("https://example.com/level_map.ini"))
    }

    @Test fun standaloneThumbnailKeepsItsNameAndBytesBesideTheMapWithoutOverwriting() = inDirectory { maps ->
        val mapFile = File(maps, "New 混乱街区.tmx").apply { writeBytes(map) }
        val name = "New 混乱街区_map.png"
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val installed = BrowserResourceFiles.install(BrowserResourceFiles.createPartial(maps).apply { writeBytes(bytes) },
            maps, name, BrowserResourceInstallKind.MapThumbnail)
        assertEquals(File(maps, name), installed.file)
        assertContentEquals(bytes, installed.file.readBytes())
        assertContentEquals(map, mapFile.readBytes())
        assertEquals(0, installed.mapCount)
        val second = BrowserResourceFiles.install(BrowserResourceFiles.createPartial(maps).apply { writeBytes(byteArrayOf(4, 5)) },
            maps, name, BrowserResourceInstallKind.MapThumbnail)
        assertEquals("New 混乱街区_map (2).png", second.file.name)
        assertContentEquals(bytes, installed.file.readBytes())
        val empty = BrowserResourceFiles.createPartial(maps)
        try {
            assertFailsWith<IOException> {
                BrowserResourceFiles.install(empty, maps, "empty_map.png", BrowserResourceInstallKind.MapThumbnail)
            }
            assertFalse(File(maps, "empty_map.png").exists())
        } finally { empty.delete() }
    }

    @Test fun mapPackPreservesNestedMapsAndCompanionResourcesAndIsVisibleToTheEngine() = inDirectory { root ->
        val maps = File(root, "maps")
        val bytes = archive("folder/one.TMX" to map, "folder/one_map.png" to byteArrayOf(1, 2),
            "folder/tiles/terrain.tsx" to "tiles".toByteArray(), "two.tmx" to map)
        val installed = BrowserResourceFiles.install(BrowserResourceFiles.createPartial(maps).apply { writeBytes(bytes) },
            maps, "maps.zip", BrowserResourceInstallKind.MapPack)
        assertEquals(File(maps, "maps").canonicalFile, installed.file)
        assertEquals(2, installed.mapCount)
        assertContentEquals(map, File(installed.file, "folder/one.tmx").readBytes())
        assertContentEquals(byteArrayOf(1, 2), File(installed.file, "folder/one_map.png").readBytes())
        assertEquals("tiles", File(installed.file, "folder/tiles/terrain.tsx").readText())
        val paths = browserCustomMapPaths(arrayOf("mod|existing.tmx", "maps/two.tmx"), maps)
        assertEquals(setOf("mod|existing.tmx", "maps/two.tmx", "maps/folder/one.tmx"), paths.toSet())
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".browser-map-pack-") })
        val second = BrowserResourceFiles.install(BrowserResourceFiles.createPartial(maps).apply { writeBytes(bytes) },
            maps, "maps.zip", BrowserResourceInstallKind.MapPack)
        assertEquals("maps (2)", second.file.name)
        assertContentEquals(map, File(installed.file, "two.tmx").readBytes())
    }

    @Test fun fullModZipBecomesAnEngineReadableRwmodWithoutChangingItsContents() = inDirectory { directory ->
        val bytes = archive("complete/mod-info.txt" to "[mod]\ntitle: Complete".toByteArray(), "complete/unit.ini" to byteArrayOf(1),
            "complete/resources/assets.zip" to archive("image.png" to byteArrayOf(1)))
        File(directory, "complete.rwmod").writeText("keep")
        val installed = BrowserResourceFiles.install(BrowserResourceFiles.createPartial(directory).apply { writeBytes(bytes) },
            directory, "complete.zip", BrowserResourceInstallKind.Mod)
        assertEquals("complete (2).rwmod", installed.file.name)
        assertContentEquals(bytes, installed.file.readBytes())
        assertEquals("keep", File(directory, "complete.rwmod").readText())
    }

    @Test fun obviousModCollectionsAreRejectedEvenWhenConfirmedAsACompleteMod() = inDirectory { directory ->
        listOf(
            archive("one.rwmod" to byteArrayOf(1), "two.rwmod" to byteArrayOf(2)),
            archive("nested/one.zip" to byteArrayOf(1), "nested/two.zip" to byteArrayOf(2)),
            archive("one/mod-info.txt" to byteArrayOf(1), "two/mod-info.txt" to byteArrayOf(2)),
        ).forEach { bytes ->
            val partial = BrowserResourceFiles.createPartial(directory).apply { writeBytes(bytes) }
            val error = assertFailsWith<BrowserResourceInstallException> {
                BrowserResourceFiles.install(partial, directory, "collection.zip", BrowserResourceInstallKind.Mod)
            }
            assertEquals(BrowserResourceInstallError.ModCollection, error.reason)
            assertTrue(directory.listFiles()!!.none { it.extension == "rwmod" })
            partial.delete()
        }
    }

    @Test fun mapPackRejectsTraversalAbsolutePathsWindowsAliasesAndDuplicateNames() = inDirectory { root ->
        val maps = File(root, "maps")
        val badNames = listOf("../escape.tmx", "nested/../../escape.tmx", "/escape.tmx", "C:/escape.tmx",
            "nested\\..\\escape.tmx", "CON.tmx", "folder./bad.tmx", "nested//bad.tmx")
        for (name in badNames) {
            val partial = BrowserResourceFiles.createPartial(maps).apply { writeBytes(archive(name to map)) }
            val error = assertFailsWith<BrowserResourceInstallException> {
                BrowserResourceFiles.install(partial, maps, "bad.zip", BrowserResourceInstallKind.MapPack)
            }
            assertEquals(BrowserResourceInstallError.UnsafeArchive, error.reason, name)
            assertEquals(listOf(partial.name), maps.list()!!.toList())
            assertFalse(File(root, "escape.tmx").exists())
            partial.delete()
        }
        val partial = BrowserResourceFiles.createPartial(maps).apply { writeBytes(archive("same.tmx" to map, "SAME.TMX" to map)) }
        assertEquals(BrowserResourceInstallError.UnsafeArchive, assertFailsWith<BrowserResourceInstallException> {
            BrowserResourceFiles.install(partial, maps, "duplicate.zip", BrowserResourceInstallKind.MapPack)
        }.reason)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".browser-map-pack-") })
    }

    @Test fun htmlEmptyArchivesAndPacksWithoutMapsDoNotInstall() = inDirectory { directory ->
        val partial = BrowserResourceFiles.createPartial(directory).apply { writeText("<html>login required</html>") }
        assertEquals(BrowserResourceInstallError.InvalidMap, assertFailsWith<BrowserResourceInstallException> {
            BrowserResourceFiles.install(partial, directory, "bad.tmx", BrowserResourceInstallKind.Map)
        }.reason)
        assertFailsWith<IOException> { BrowserResourceFiles.install(partial, directory, "bad.zip", BrowserResourceInstallKind.MapPack) }
        for (bytes in listOf(archive(), archive("readme.txt" to byteArrayOf(1)))) {
            partial.writeBytes(bytes)
            assertEquals(BrowserResourceInstallError.NoMaps, assertFailsWith<BrowserResourceInstallException> {
                BrowserResourceFiles.install(partial, directory, "empty.zip", BrowserResourceInstallKind.MapPack)
            }.reason)
        }
        assertEquals(listOf(partial.name), directory.list()!!.toList())
    }

    @Test fun externalXmlEntitiesAreNotResolvedAndBrokenPacksNeverAppearInMaps() = inDirectory { directory ->
        val partial = BrowserResourceFiles.createPartial(directory)
        partial.writeText("<!DOCTYPE map SYSTEM 'file:///does-not-exist'><map/>")
        assertEquals(BrowserResourceInstallError.InvalidMap, assertFailsWith<BrowserResourceInstallException> {
            BrowserResourceFiles.install(partial, directory, "external.tmx", BrowserResourceInstallKind.Map)
        }.reason)
        partial.writeBytes(archive("good.tmx" to map, "bad.tmx" to "<html/>".toByteArray()))
        assertFailsWith<BrowserResourceInstallException> {
            BrowserResourceFiles.install(partial, directory, "broken.zip", BrowserResourceInstallKind.MapPack)
        }
        assertEquals(listOf(partial.name), directory.list()!!.toList())
    }

    @Test fun oversizedEntryCountsAreRejectedBeforeExtracting() = inDirectory { directory ->
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                repeat(10001) { zip.putNextEntry(ZipEntry("$it.txt")); zip.closeEntry() }
            }
        }.toByteArray()
        val partial = BrowserResourceFiles.createPartial(directory).apply { writeBytes(bytes) }
        assertEquals(BrowserResourceInstallError.ArchiveTooLarge, assertFailsWith<BrowserResourceInstallException> {
            BrowserResourceFiles.install(partial, directory, "large.zip", BrowserResourceInstallKind.MapPack)
        }.reason)
        assertEquals(listOf(partial.name), directory.list()!!.toList())
    }

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun inDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("browser-resource-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
