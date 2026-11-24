/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import com.sun.jna.Platform
import io.github.rwpp.expansion.ExpansionPackageInstaller
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionFileChangeTokenTest {
    @Test fun windowsDetectsSameLengthEditsAndFileReplacementWithRestoredModificationTime() {
        assumeTrue(Platform.isWindows())
        val root = Files.createTempDirectory("expansion-file-change-").toFile()
        try {
            val file = root.resolve("content.zip").apply { writeText("version1") }
            val originalTime = Files.getLastModifiedTime(file.toPath())
            val original = assertNotNull(expansionFileChangeToken(file))
            assertEquals(original, expansionFileChangeToken(file))
            file.writeText("version2")
            Files.setLastModifiedTime(file.toPath(), originalTime)
            val edited = assertNotNull(expansionFileChangeToken(file))
            assertNotEquals(original, edited)

            val replacement = root.resolve("replacement").apply { writeText("version2") }
            Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
            Files.setLastModifiedTime(file.toPath(), originalTime)
            assertNotEquals(edited, assertNotNull(expansionFileChangeToken(file)))
            assertNull(expansionFileChangeToken(replacement))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun windowsReusesVerifiedServerToolPackageAfterRescanAndRejectsChangedTheme() {
        val fixture = System.getenv("RWJS_EXPANSION_CONTRACT_FIXTURE")
        assumeTrue("Requires Windows and the independent server-tool ZIP", Platform.isWindows() && fixture != null)
        val root = Files.createTempDirectory("expansion-receipt-").toFile()
        try {
            val installed = ExpansionPackageInstaller.install(File(requireNotNull(fixture)), root)
            assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, ::expansionFileChangeToken))
            val rescanned = ExpansionPackageInstaller.scan(root).single()
            assertTrue(ExpansionPackageInstaller.verifyForActivation(rescanned, ::expansionFileChangeToken))

            val theme = rescanned.themeDir.resolve("theme.toml")
            val time = Files.getLastModifiedTime(theme.toPath())
            RandomAccessFile(theme, "rw").use {
                val changed = it.readUnsignedByte() xor 1
                it.seek(0)
                it.write(changed)
            }
            Files.setLastModifiedTime(theme.toPath(), time)
            assertFailsWith<IOException> {
                ExpansionPackageInstaller.verifyForActivation(rescanned, ::expansionFileChangeToken)
            }
        } finally {
            root.deleteRecursively()
        }
    }

}
