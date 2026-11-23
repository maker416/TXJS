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

class ModLoadSafetyBudgetProcessTest {
    @Test fun loadCompletesBelowFormerReserveAndOnlyAnAllocationFailureBlocksReload() {
        val root = Files.createTempDirectory("mod-budget-process").toFile()
        try {
            val journal = File(root, "journal")
            ModLoadSafety.configure(journal)
            val runtime = Runtime.getRuntime()
            val loaded = ModLoadSafety.run("local-reload") {
                // Keep 64 MiB live through both the parser checkpoint and the final checkpoint in the 96 MiB JVM.
                val payload = Array(1024) { ByteArray(64 * 1024) }
                ModLoadSafety.checkpoint("heavy-fixture")
                payload
            }
            val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            assertTrue(headroom < (32L shl 20), "Fixture must cross the former 32 MiB reserve")
            assertEquals(64L shl 20, loaded.sumOf { it.size.toLong() })
            assertFalse(journal.exists())
            assertFalse(GameStartSafety.requiresRestart)

            val failure = assertFailsWith<ModLoadFailure> {
                ModLoadSafety.run("room-reload") {
                    ModLoadSafety.checkpoint("oversize-fixture")
                    try {
                        val impossible = ByteArray(Math.toIntExact(runtime.maxMemory()))
                        fail("Allocated ${impossible.size} bytes in an occupied heap")
                    } catch (error: OutOfMemoryError) {
                        throw IllegalStateException("Engine wrapped the allocation failure", error)
                    }
                }
            }
            assertTrue(failure.memoryExhausted)
            assertTrue(GameStartSafety.requiresRestart)
            assertEquals("oom", ModLoadSafety.previousFailure()?.failure)
            assertEquals("oversize-fixture", ModLoadSafety.previousFailure()?.mod)
            assertFailsWith<ModLoadFailure> { ModLoadSafety.run("retry") { fail("Damaged registry reused") } }
        } finally { root.deleteRecursively() }
    }
}
