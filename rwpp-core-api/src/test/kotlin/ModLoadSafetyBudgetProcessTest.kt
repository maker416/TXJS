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

class ModLoadSafetyBudgetProcessTest {
    @Test fun actualHeapPressureStopsBeforeAllocationFailure() {
        val root = Files.createTempDirectory("mod-budget-process").toFile()
        try {
            ModLoadSafety.configure(File(root, "journal"))
            var allocationFailed = false
            val held = ArrayList<ByteArray>()
            val failure = assertFailsWith<ModLoadFailure> {
                ModLoadSafety.run("local-reload") {
                    repeat(256) {
                        ModLoadSafety.checkpoint("heavy-fixture")
                        try { held.add(ByteArray(1024 * 1024)) }
                        catch (oom: OutOfMemoryError) { allocationFailed = true; throw oom }
                    }
                }
            }
            assertTrue(failure.memoryExhausted)
            assertFalse(allocationFailed, "The budget must stop parsing before the VM rejects allocations")
            assertTrue(held.size < 80)
            held.clear()
            assertEquals("memory", ModLoadSafety.previousFailure()?.failure)
        } finally { root.deleteRecursively() }
    }
}
