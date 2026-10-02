/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.io.zipFolderToByte
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ZipUtilsTest {
    @Test
    fun equalFolderContentsProduceEqualPayloadDespiteCreationOrderAndFileTimes() {
        val first = createTempDirectory().toFile()
        val second = createTempDirectory().toFile()
        try {
            first.resolve("z.txt").writeText("z")
            first.resolve("nested").mkdirs()
            first.resolve("nested/a.txt").writeText("a")
            second.resolve("nested").mkdirs()
            second.resolve("nested/a.txt").writeText("a")
            second.resolve("z.txt").writeText("z")
            first.resolve("z.txt").setLastModified(1_000L)
            second.resolve("z.txt").setLastModified(9_999_999L)

            val payload = first.zipFolderToByte()
            assertContentEquals(payload, second.zipFolderToByte())
            ZipInputStream(ByteArrayInputStream(payload)).use { input ->
                val entry = input.nextEntry!!
                assertEquals("nested/a.txt", entry.name)
                assertEquals(0L, entry.time, "ZIP 条目不得使用打包时刻")
                assertEquals("a", input.readBytes().decodeToString())
                val next = input.nextEntry!!
                assertEquals("z.txt", next.name)
                assertEquals(0L, next.time)
                assertEquals("z", input.readBytes().decodeToString())
                assertFalse(input.nextEntry != null)
            }
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }
}
