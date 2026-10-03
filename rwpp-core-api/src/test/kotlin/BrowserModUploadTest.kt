/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import io.github.rwpp.config.BrowserUploadSource
import io.github.rwpp.config.Settings
import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.IOException
import kotlinx.coroutines.CancellationException

class BrowserModUploadTest {
    @Test fun uploadPreferenceIsUnsetInOldConfigAndPersistsAcrossRestart() {
        assertNull(Toml.decodeFromString(Settings.serializer(), "language = \"zh\"").browserUploadSource)
        BrowserUploadSource.entries.forEach { source ->
            val encoded = Toml.encodeToString(Settings.serializer(), Settings(browserUploadSource = source))
            val restored = Toml.decodeFromString(Settings.serializer(), encoded).also { it.migrate() }
            assertEquals(source, restored.browserUploadSource)
        }
    }

    @Test fun imageInputsBypassModChoiceWhileArchiveInputsSupportMods() {
        listOf(emptyList(), listOf("*/*"), listOf(".rwmod,.zip"), listOf("application/zip"), listOf(".zip"), listOf("application/octet-stream"))
            .forEach { assertTrue(BrowserUploadAccept(it).supportsMods, it.toString()) }
        listOf(listOf("image/*"), listOf(".png", ".jpg"), listOf("image/jpeg,image/png"), listOf("video/*"))
            .forEach { assertFalse(BrowserUploadAccept(it).supportsMods, it.toString()) }
        assertTrue(BrowserUploadAccept(listOf(".RWMOD")).accepts("TEST.rwmod"))
    }

    @Test fun folderAndArchiveUploadsPreserveContentsAndRemainUntilBrowserCloses() {
        val directory = Files.createTempDirectory("browser-upload-test-").toFile()
        try {
            val folder = File(directory, "test-mod").apply { mkdir() }
            File(folder, "mod-info.txt").writeText("[mod]\ntitle: Test")
            File(folder, "units").mkdir()
            val unit = File(folder, "units/tank.ini").apply { writeText("[core]\nname: tank") }
            val cache = BrowserModUploadCache(File(directory, "cache"))
            val packed = cache.prepare(folder)
            assertEquals("test-mod.rwmod", packed.name)
            ZipFile(packed).use { zip ->
                assertEquals(setOf("mod-info.txt", "units/tank.ini"), zip.entries().asSequence().map { it.name }.toSet())
                assertContentEquals(unit.readBytes(), zip.getInputStream(zip.getEntry("units/tank.ini")).readBytes())
            }
            val copied = cache.prepare(packed, "test-mod.zip")
            assertContentEquals(packed.readBytes(), copied.readBytes())
            assertTrue(packed.exists())
            assertTrue(copied.exists())
            cache.close()
            assertFalse(packed.exists())
            assertFalse(copied.exists())
            assertTrue(unit.exists())
            assertFailsWith<IOException> { cache.prepare(folder) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingSourcesDoNotLeavePartialSnapshots() {
        val directory = Files.createTempDirectory("browser-upload-failure-").toFile()
        try {
            val cache = BrowserModUploadCache(directory)
            assertFailsWith<IOException> { cache.prepare(File(directory, "gone.rwmod")) }
            assertTrue(directory.walkTopDown().none { it.isFile })
            cache.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledPackingStopsStreamingAndRemovesItsPartialFile() {
        val directory = Files.createTempDirectory("browser-upload-cancel-").toFile()
        try {
            val source = File(directory, "large.rwmod").apply { writeBytes(ByteArray(256 * 1024)) }
            val cacheParent = File(directory, "cache").apply { mkdir() }
            val cache = BrowserModUploadCache(cacheParent)
            var chunks = 0
            assertFailsWith<CancellationException> {
                cache.prepare(source, checkCancelled = { if (++chunks == 2) throw CancellationException() })
            }
            assertTrue(cacheParent.walkTopDown().none { it.isFile })
            assertTrue(source.exists())
            cache.close()
        } finally { directory.deleteRecursively() }
    }
}
