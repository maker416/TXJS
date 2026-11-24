/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidDirectResumeVaultTest {
    @Test fun keystoreRestoresAcrossInstancesAndAuthenticatesTheCiphertext() {
        val alias = "io.github.rwpp.direct-resume.test.${UUID.randomUUID()}"
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, alias)
        val vault = AndroidDirectResumeVault(file, alias)
        try {
            assertNull(vault.read())
            val bytes = "original reconnect grant".encodeToByteArray()
            vault.write(bytes)
            val first = file.readBytes()
            val reopened = AndroidDirectResumeVault(file, alias)
            assertArrayEquals(bytes, reopened.read())
            reopened.write(bytes)
            val second = file.readBytes()
            assertFalse(first.contentEquals(second))
            second[second.lastIndex] = (second.last().toInt() xor 1).toByte()
            file.writeBytes(second)
            try {
                reopened.read()
                fail("Modified ciphertext must fail authentication")
            } catch (_: DirectResumeCorruptException) { }
            vault.delete()
            assertNull(reopened.read())
        } finally {
            vault.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
