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
    @Test fun contentProviderStreamsPreserveNamesAndCancelWithoutPartialFiles() {
        val directory = Files.createTempDirectory("browser-saf-upload-").toFile()
        val cache = BrowserModUploadCache(directory)
        try {
            val bytes = ByteArray(150_000) { it.toByte() }
            val file = cache.prepareStream("模组.zip", { bytes.inputStream() })
            assertEquals("模组.zip", file.name)
            assertContentEquals(bytes, file.readBytes())
            var checks = 0
            assertFailsWith<CancellationException> {
                cache.prepareStream("cancelled.zip", { bytes.inputStream() }) {
                    if (++checks == 3) throw CancellationException()
                }
            }
            assertTrue(directory.walkTopDown().none { it.name == "cancelled.zip" })
            assertFailsWith<IllegalArgumentException> { cache.prepareStream("../escape", { bytes.inputStream() }) }
            cache.close()
            assertFalse(file.exists())
            assertFailsWith<IOException> { cache.prepareStream("closed.zip", { bytes.inputStream() }) }
        } finally { cache.close(); directory.deleteRecursively() }
    }
    @Test fun managedUploadsUseTitleWithoutChangingOriginalArchiveOrSameTitleSnapshots() {
        val directory = Files.createTempDirectory("browser-upload-names-").toFile()
        val cache = BrowserModUploadCache(File(directory, "cache"))
        try {
            val first = File(directory, "v033.rwmod").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val second = File(directory, "other.zip").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val title = "破境边缘 V0.33（烛烬华章：革新）"
            val firstName = browserModUploadName(first, title)
            assertEquals("$title.rwmod", firstName)
            assertEquals("$title.zip", browserModUploadName(second, title))
            assertEquals(firstName, browserModUploadName(first, "$title.RWMOD"))
            assertEquals("v033.rwmod", browserModUploadName(first))
            assertEquals("v033.rwmod", browserModUploadName(first, "  "))
            assertEquals("v033.rwmod", browserModUploadName(first, "<>:*?"))
            val one = cache.prepare(first, firstName)
            val two = cache.prepare(second, firstName)
            assertEquals(one.name, two.name)
            assertTrue(one.parentFile != two.parentFile, "Same titles must not overwrite each other")
            assertContentEquals(byteArrayOf(1, 2, 3), one.readBytes())
            assertContentEquals(byteArrayOf(4, 5, 6), two.readBytes())
            assertEquals("v033.rwmod", first.name)
            assertContentEquals(byteArrayOf(1, 2, 3), first.readBytes())
        } finally { cache.close(); directory.deleteRecursively() }
    }

    @Test fun generatedTitleNamesArePortableAndKeepUnicodeCodePointsAndSuffix() {
        val source = File("v033.rwmod")
        assertEquals("坏_名_标题.rwmod", browserModUploadName(source, "坏/名:标\r\n题"))
        assertEquals("_CON.rwmod", browserModUploadName(source, "CON"))
        assertEquals("_lpt1.rwmod", browserModUploadName(source, "lpt1."))
        assertEquals("v033.rwmod", browserModUploadName(source, ".".repeat(260) + "标题"))
        val title = "中文😀".repeat(100)
        val name = browserModUploadName(source, title)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(name.endsWith(".rwmod"))
        assertTrue(title.startsWith(name.removeSuffix(".rwmod")))
        assertEquals(name, name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

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
            val named = cache.prepare(folder, browserModUploadName(folder, "中文 文件夹 V0.33"))
            assertEquals("中文 文件夹 V0.33.rwmod", named.name)
            assertContentEquals(packed.readBytes(), named.readBytes())
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
