/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.*
import java.io.File
import kotlin.test.*

class ModPlaytimeSnapshotCacheTest {
    private fun mod() = object : Mod {
        override val id = 1
        override val name = "test"
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = true
        var pathReads = 0
        override val path: String get() { pathReads++; return "test.rwmod" }
        override fun getRamUsed() = ""
        override fun getSize() = 0L
        override fun getBytes() = byteArrayOf()
    }

    @Test fun stableMatchReusesFrozenSourcesAndEmptySnapshot() {
        val cache = ModPlaytimeSnapshotCache(); val mod = mod(); var scans = 0
        val first = cache.get(1) { scans++; listOf(mod) }
        repeat(1000) { assertSame(first, cache.get(1) { error("Must not rescan") }) }
        assertEquals(1, scans)
        assertEquals(listOf(ModPlaytimeSource("test", "test.rwmod")), first.sources)
        val empty = cache.get(2) { emptyList() }
        repeat(1000) { assertSame(empty, cache.get(2) { error("Empty is also cached") }) }
        cache.invalidate()
        assertNotSame(empty, cache.get(2) { emptyList() })
        cache.clear()
        assertNotSame(first, cache.get(1) { listOf(mod) })
    }

    @Test fun failedOrInvalidatedCaptureIsRetried() {
        val cache = ModPlaytimeSnapshotCache()
        assertFails { cache.get(1) { error("loading") } }
        assertFails { cache.get(1) { cache.invalidate(); emptyList() } }
        assertEquals(emptyList(), cache.get(1) { emptyList() }.sources)
    }

    @Test fun legacySourcesOnlyReadOncePerGenerationAndNeverOnUnreliableSamples() {
        var reads = 0
        val source = object : Mod by mod() {
            override val path: String get() { reads++; return "test.rwmod" }
        }
        val cache = ModPlaytimeSources()
        val state = ModPlaytimeState(hasMatch = true, generation = 1, mods = listOf(source))
        repeat(1000) { cache.read(state) }
        assertEquals(1, reads)
        cache.read(state.copy(reliable = false)); assertEquals(1, reads)
        cache.read(state.copy(generation = 2)); assertEquals(2, reads)
        cache.read(ModPlaytimeState()); cache.read(state); assertEquals(3, reads)
        cache.read(state.copy(sources = emptyList())); assertEquals(3, reads)
    }
}
