/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.io.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

class ExternalFileImportTest {
    @Test fun realDisplayNameDefinesTypeIncludingLegacyReplaySpelling() {
        assertEquals(ExternalFileType.Map, externalFileType("map.TMX"))
        assertEquals(ExternalFileType.Mod, externalFileType("mod.rwmod"))
        assertEquals(ExternalFileType.Replay, externalFileType("battle.REPLY"))
        assertEquals(ExternalFileType.Replay, externalFileType("battle.replay"))
        assertEquals(ExternalFileType.Unknown, externalFileType("123"))
    }

    @Test fun failedCopyPreservesPreviousFileAndRemovesTemp() {
        val root = Files.createTempDirectory("external-import").toFile()
        try {
            val target = File(root, "map.tmx").apply { writeText("original") }
            val broken = object : InputStream() {
                var count = 0
                override fun read(): Int = if (count++ < 20) 1 else throw IOException("source vanished")
            }
            assertFailsWith<IOException> { copyImportedFile(broken, root, target.name) }
            assertEquals("original", target.readText())
            assertEquals(listOf(target.name), root.listFiles()!!.map { it.name })
            assertFailsWith<IllegalArgumentException> { copyImportedFile(byteArrayOf(1).inputStream(), root, "../escape") }
        } finally { root.deleteRecursively() }
    }
}
