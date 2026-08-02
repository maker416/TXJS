/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HostManifestCacheTest {

    private fun source(name: String, vararg payload: Byte): HostModTransferSource {
        val bytes = if (payload.isEmpty()) byteArrayOf(1, 2, 3) else payload
        return HostModTransferSource(NetworkModDescriptor.fromBytes(name, bytes), bytes)
    }

    @Test
    fun cacheHitOnlyOnSameFingerprint() {
        val cache = HostManifestCache()
        val entry = HostManifestCache.Entry("fp-a", listOf(source("modA")))
        cache.put(entry)

        assertSame(entry, cache.get("fp-a"))
        assertNull(cache.get("fp-b"))
    }

    @Test
    fun putReplacesSingleEntry() {
        val cache = HostManifestCache()
        val first = HostManifestCache.Entry("fp-a", listOf(source("modA")))
        val second = HostManifestCache.Entry("fp-b", listOf(source("modB")))
        cache.put(first)
        cache.put(second)

        assertNull(cache.get("fp-a"))
        assertSame(second, cache.get("fp-b"))

        cache.clear()
        assertNull(cache.get("fp-b"))
    }

    @Test
    fun entryDescriptorsDeriveFromSources() {
        val sources = listOf(source("modA"), source("modB", 9))
        val entry = HostManifestCache.Entry("fp", sources)
        assertEquals(sources.map { it.descriptor }, entry.descriptors)
    }

    @Test
    fun fingerprintIsOrderIndependent() {
        val dir = Files.createTempDirectory("hmc-fp").toFile()
        val a = File(dir, "a.rwmod").apply { writeBytes(byteArrayOf(1)) }
        val b = File(dir, "b.rwmod").apply { writeBytes(byteArrayOf(2)) }

        val fp1 = HostManifestCache.fingerprint(listOf("modA" to a, "modB" to b))
        val fp2 = HostManifestCache.fingerprint(listOf("modB" to b, "modA" to a))
        assertEquals(fp1, fp2)

        dir.deleteRecursively()
    }

    @Test
    fun fingerprintChangesWithFileContentMetadata() {
        val dir = Files.createTempDirectory("hmc-fp2").toFile()
        val a = File(dir, "a.rwmod").apply { writeBytes(byteArrayOf(1)) }

        val before = HostManifestCache.fingerprint(listOf("modA" to a))
        a.setLastModified(a.lastModified() + 10_000)
        val afterMtime = HostManifestCache.fingerprint(listOf("modA" to a))
        assertNotEquals(before, afterMtime)

        a.writeBytes(byteArrayOf(1, 2))
        val afterSize = HostManifestCache.fingerprint(listOf("modA" to a))
        assertNotEquals(afterMtime, afterSize)

        dir.deleteRecursively()
    }

    @Test
    fun fingerprintTracksDirectoryContentsRecursively() {
        val dir = Files.createTempDirectory("hmc-fp3").toFile()
        val modDir = File(dir, "mod").apply { mkdirs() }
        val nested = File(modDir, "units").apply { mkdirs() }
        val unit = File(nested, "tank.ini").apply { writeText("[core]\nname=tank") }

        val before = HostManifestCache.fingerprint(listOf("modA" to modDir))
        unit.setLastModified(unit.lastModified() + 10_000)
        val afterEdit = HostManifestCache.fingerprint(listOf("modA" to modDir))
        assertNotEquals(before, afterEdit)

        dir.deleteRecursively()
    }
}
