/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModPlaytimeHash
import io.github.rwpp.io.zipFolderToByte
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModPlaytimeHashTest {
    @Test fun archiveUsesExactStreamingSha256() {
        val file = Files.createTempFile("playtime", ".rwmod").toFile()
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModPlaytimeHash.sha256(file))
        } finally { file.delete() }
    }

    @Test fun directoryIdentityMatchesTransferredArchiveAndIgnoresTimestampsAndRootName() {
        val root = Files.createTempDirectory("playtime-hash").toFile()
        try {
            val host = root.resolve("host").apply { mkdirs() }
            host.resolve("units").mkdirs()
            host.resolve("units/z.ini").writeText("[core]\nname=z")
            host.resolve("mod-info.txt").writeText("[mod]\ntitle=Demo")
            val before = ModPlaytimeHash.sha256(host)
            val download = root.resolve("download.network.rwmod")
            download.writeBytes(host.zipFolderToByte())
            assertEquals(before, ModPlaytimeHash.sha256(download))
            host.walkTopDown().forEach { it.setLastModified(1700000000000) }
            assertEquals(before, ModPlaytimeHash.sha256(host))
            val renamed = root.resolve("renamed")
            host.copyRecursively(renamed)
            assertEquals(before, ModPlaytimeHash.sha256(renamed))
            renamed.resolve("units/z.ini").appendText("\nmaxHp=300")
            assertNotEquals(before, ModPlaytimeHash.sha256(renamed))
        } finally { root.deleteRecursively() }
    }
}
