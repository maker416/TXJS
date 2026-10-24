/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Runs in a dedicated JVM: a real failure must remain irreversible for the process. */
class ModLoadSafetyMemoryProcessTest {
    @Test fun temporaryRecoveryAndWrappedOomNeverReportSuccess() {
        val root = Files.createTempDirectory("mod-memory-process").toFile()
        try {
            val file = File(root, "journal")
            ModLoadSafety.configure(file)
            ModLoadSafety.run("startup") { ModLoadSafety.checkpoint("test") }
            assertFalse(file.exists())
            ModLoadSafety.useRecoverySelection(mapOf("chosen.rwmod" to true))
            assertEquals(true, ModReloadSelection.resolve(listOf("chosen.rwmod")))
            assertEquals(false, ModReloadSelection.resolve(listOf("other.rwmod")))
            ModLoadSafety.run("startup") { ModLoadSafety.checkpoint("chosen") }
            assertEquals("temporary-recovery", ModLoadSafety.previousFailure()?.failure)
            assertTrue(ModLoadSafety.suppressSelectionSave)
            ModLoadSafety.commitRecoverySelection()
            ModLoadSafety.run("local-reload") {}
            assertFalse(file.exists())
            assertFalse(ModLoadSafety.suppressSelectionSave)

            var continued = false
            val failure = assertFailsWith<ModLoadFailure> {
                ModLoadSafety.run("room-reload") {
                    ModLoadSafety.checkpoint("heavy")
                    ModLoadSafety.onEngineError(RuntimeException("wrapped", OutOfMemoryError("fixture")))
                    continued = true
                }
            }
            assertFalse(continued)
            assertTrue(failure.memoryExhausted)
            assertTrue(GameStartSafety.requiresRestart)
            assertTrue(ModLoadSafety.suppressSelectionSave)
            assertFalse(ModLoadSafety.loading)
            assertEquals("memory", ModLoadSafety.previousFailure()?.failure)
            assertEquals("heavy", ModLoadSafety.previousFailure()?.mod)
            assertFailsWith<ModLoadFailure> { ModLoadSafety.run("must-not-retry") { fail("must not execute") } }
            assertEquals("room-reload", ModLoadSafety.previousFailure()?.stage)
        } finally { root.deleteRecursively() }
    }
}

