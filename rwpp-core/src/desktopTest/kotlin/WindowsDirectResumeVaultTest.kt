/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WindowsDirectResumeVaultTest {
    @Test fun dpapiRestoresAcrossInstancesAndRejectsCorruption() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = Files.createTempFile("rwjs-dpapi-test-", ".bin").toFile()
        try {
            val vault = WindowsDirectResumeVault(file)
            vault.delete()
            assertNull(vault.read())
            val bytes = "original reconnect grant".encodeToByteArray()
            vault.write(bytes)
            assertFalse(file.readBytes().contentEquals(bytes))
            val reopened = WindowsDirectResumeVault(File(file.absolutePath))
            assertContentEquals(bytes, reopened.read())
            assertFailsWith<IllegalArgumentException> { reopened.write(ByteArray(MAX_DIRECT_RESUME_BYTES + 1)) }
            assertContentEquals(bytes, reopened.read())
            file.writeBytes(file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
            assertFailsWith<DirectResumeCorruptException> { reopened.read() }
            reopened.write(bytes)
            assertContentEquals(bytes, vault.read())
            reopened.delete()
            assertNull(vault.read())
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}
