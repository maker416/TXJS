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

class ModLoadSafetyErrorProcessTest {
    @Test fun ordinaryFailureIsDistinctAndAlsoBlocksDamagedRegistry() {
        val root = Files.createTempDirectory("mod-error-process").toFile()
        try {
            ModLoadSafety.configure(File(root, "journal"))
            ModLoadSafety.run("outer") {
                assertFailsWith<IllegalStateException> { ModLoadSafety.run("overlap") {} }
                assertTrue(ModLoadSafety.loading)
                assertFalse(GameStartSafety.requiresRestart)
            }
            val failure = assertFailsWith<ModLoadFailure> {
                ModLoadSafety.run("local-reload") { throw IllegalArgumentException("bad registry") }
            }
            assertFalse(failure.memoryExhausted)
            assertTrue(GameStartSafety.requiresRestart)
            assertFalse(GameStartSafety.memoryExhausted)
            assertFalse(ModLoadSafety.loading)
            assertEquals("load", ModLoadSafety.previousFailure()?.failure)
            assertFailsWith<ModLoadFailure> { GameStartSafety.requireCanReload() }
        } finally { root.deleteRecursively() }
    }
}

