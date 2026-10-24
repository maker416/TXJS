/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ModLoadSafetyTest {
    @Test fun journalPreservesUnfinishedAttemptAndIgnoresStaleCompletion() {
        val root = Files.createTempDirectory("mod-journal").toFile()
        try {
            val file = File(root, "resource-load.properties")
            val journal = ModLoadJournal(file)
            assertNull(journal.read())
            val first = ModLoadJournalEntry("one", "previous-process", "startup", "模组", "")
            journal.write(first)
            assertEquals(first, ModLoadJournal(file).read())
            val second = first.copy(attempt = "two", stage = "room-reload", failure = "memory")
            journal.write(second)
            journal.complete("one")
            assertEquals(second, journal.read())
            journal.complete("two")
            assertFalse(file.exists())
            file.writeText("incomplete metadata")
            assertNull(journal.read())
        } finally { root.deleteRecursively() }
    }

    @Test fun reserveUsesHeapLimitWithBounds() {
        assertEquals(32L shl 20, modLoadReserveBytes(96L shl 20))
        assertEquals((512L shl 20) / 10, modLoadReserveBytes(512L shl 20))
        assertEquals(128L shl 20, modLoadReserveBytes(4096L shl 20))
    }

    @Test fun recoveryListsContainersWithoutReadingTheirContents() {
        val root = Files.createTempDirectory("mod-recovery").toFile()
        try {
            val mod = File(root, "sample.rwmod").apply { writeText("not even a ZIP") }
            val directory = File(root, "folder").apply { mkdir() }
            File(root, ".incomplete.rwmod").writeText("")
            File(root, "readme.txt").writeText("")
            File(root, "units.zip").writeText("An archive containing mods is not an engine-scanned mod")
            assertEquals(setOf(mod, directory), recoveryModFiles(listOf(root, root)).toSet())
        } finally { root.deleteRecursively() }
    }
}

