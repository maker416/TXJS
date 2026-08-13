/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ProtectedRwmodDetector
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedRwmodDetectorTest {
    @Test
    fun normalRwmodIsNotProtected() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "normal.rwmod"), 10)
        assertFalse(ProtectedRwmodDetector.isProtectedRwmod(rwmod))
        root.deleteRecursively()
    }

    @Test
    fun patchedCenFingerprintIsProtected() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "packed.rwmod"), 10)
        patchCentralDirectoryAsProtected(rwmod)
        assertTrue(ProtectedRwmodDetector.isProtectedRwmod(rwmod))
        root.deleteRecursively()
    }

    @Test
    fun tooFewEntriesAreIgnored() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "tiny.rwmod"), 3)
        patchCentralDirectoryAsProtected(rwmod)
        assertFalse(ProtectedRwmodDetector.isProtectedRwmod(rwmod))
        root.deleteRecursively()
    }

    @Test
    fun displayNamesIncludeEnabledUnloadedFile() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "reborn.rwmod"), 10)
        patchCentralDirectoryAsProtected(rwmod)
        val names = ProtectedRwmodDetector.displayNamesOfEnabled(
            engineMods = emptyList(),
            enabledByFileName = mapOf("reborn.rwmod" to true),
            searchDirs = listOf(root),
        )
        assertEquals(listOf("reborn"), names)
        root.deleteRecursively()
    }

    @Test
    fun displayNamesIncludeNewFileWhenSelectionMissing() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "newpack.rwmod"), 10)
        patchCentralDirectoryAsProtected(rwmod)
        val names = ProtectedRwmodDetector.displayNamesOfEnabled(
            engineMods = emptyList(),
            enabledByFileName = null,
            searchDirs = listOf(root),
        )
        assertEquals(listOf("newpack"), names)
        root.deleteRecursively()
    }

    @Test
    fun displayNamesSkipDisabledEngineMod() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "off.rwmod"), 10)
        patchCentralDirectoryAsProtected(rwmod)
        val mod = fakeMod(name = "OffMod", path = rwmod.absolutePath, enabled = false)
        val names = ProtectedRwmodDetector.displayNamesOfEnabled(
            engineMods = listOf(mod),
            enabledByFileName = mapOf("off.rwmod" to false),
            searchDirs = listOf(root),
        )
        assertTrue(names.isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun displayNamesSkipDisabledEngineModWhenSelectionMissing() {
        val root = createTempDirectory().toFile()
        val rwmod = writeNormalRwmod(File(root, "off.rwmod"), 10)
        patchCentralDirectoryAsProtected(rwmod)
        val mod = fakeMod(name = "OffMod", path = rwmod.absolutePath, enabled = false)
        val names = ProtectedRwmodDetector.displayNamesOfEnabled(
            engineMods = listOf(mod),
            enabledByFileName = null,
            searchDirs = listOf(root),
        )
        assertTrue(names.isEmpty())
        root.deleteRecursively()
    }

    private fun fakeMod(name: String, path: String, enabled: Boolean): Mod {
        return object : Mod {
            override val id: Int = 1
            override val name: String = name
            override val description: String = ""
            override val minVersion: String = ""
            override val errorMessage: String? = null
            override var isEnabled: Boolean = enabled
            override val path: String = path
            override fun getRamUsed(): String = "0"
            override fun getSize(): Long = 0
            override fun getBytes(): ByteArray = ByteArray(0)
        }
    }

    private fun writeNormalRwmod(file: File, count: Int): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(count) { i ->
                zip.putNextEntry(ZipEntry("unit$i.ini"))
                zip.write("[core]\nname: u$i\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun patchCentralDirectoryAsProtected(file: File) {
        val bytes = file.readBytes()
        var eocd = bytes.size - 22
        while (eocd >= 0 && !match(bytes, eocd, 0x50, 0x4b, 0x05, 0x06)) {
            eocd--
        }
        require(eocd >= 0) { "EOCD not found" }
        val n = u16(bytes, eocd + 10)
        var off = u32(bytes, eocd + 16).toInt()
        repeat(n) {
            require(match(bytes, off, 0x50, 0x4b, 0x01, 0x02)) { "CEN signature mismatch" }
            putu32(bytes, off + 16, 1L)
            putu32(bytes, off + 20, 0xFFFFFFFFL)
            putu32(bytes, off + 24, 0L)
            val namelen = u16(bytes, off + 28)
            val extra = u16(bytes, off + 30)
            val comment = u16(bytes, off + 32)
            off += 46 + namelen + extra + comment
        }
        file.writeBytes(bytes)
    }

    private fun match(bytes: ByteArray, off: Int, a: Int, b: Int, c: Int, d: Int): Boolean {
        return off + 3 < bytes.size &&
            (bytes[off].toInt() and 0xff) == a &&
            (bytes[off + 1].toInt() and 0xff) == b &&
            (bytes[off + 2].toInt() and 0xff) == c &&
            (bytes[off + 3].toInt() and 0xff) == d
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, off: Int): Long =
        (bytes[off].toLong() and 0xff) or
            ((bytes[off + 1].toLong() and 0xff) shl 8) or
            ((bytes[off + 2].toLong() and 0xff) shl 16) or
            ((bytes[off + 3].toLong() and 0xff) shl 24)

    private fun putu32(bytes: ByteArray, off: Int, value: Long) {
        bytes[off] = (value and 0xff).toByte()
        bytes[off + 1] = ((value shr 8) and 0xff).toByte()
        bytes[off + 2] = ((value shr 16) and 0xff).toByte()
        bytes[off + 3] = ((value shr 24) and 0xff).toByte()
    }
}
