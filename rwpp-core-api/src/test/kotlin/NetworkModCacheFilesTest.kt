/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.NETWORK_MOD_CACHE_TTL_MILLIS
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkModCacheFilesTest {
    private val hash64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun metadataRoundTrips() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod-name", 7L, hash64)
        val meta = NetworkModCacheFiles.metadataFile(root, descriptor)
        NetworkModCacheFiles.writeMetadata(meta, descriptor, 123456L, "payload.bin", root)
        val parsed = NetworkModCacheFiles.readMetadata(meta)!!
        assertEquals(descriptor, parsed.descriptor)
        assertEquals(123456L, parsed.createdAtMillis)
        assertEquals("payload.bin", parsed.payloadName)
    }

    @Test
    fun metadataRejectsTamperedCacheKey() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod-name", 7L, hash64)
        val meta = NetworkModCacheFiles.metadataFile(root, descriptor)
        // Write metadata with a wrong sha256 line vs cacheKey → the key no longer matches.
        meta.parentFile?.mkdirs()
        meta.writeText(
            "version=${NetworkModCacheFiles.FORMAT_VERSION}\n" +
                "name=bW9kLW5hbWU=\n" +
                "payloadSize=7\n" +
                "sha256=0000000000000000000000000000000000000000000000000000000000000000\n" +
                "createdAtMillis=1\n" +
                "cacheKey=${descriptor.cacheKey()}\n" +
                "payloadName=payload.bin\n"
        )
        assertNull(NetworkModCacheFiles.readMetadata(meta))
    }

    @Test
    fun atomicWriteReplacesTarget() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod", 3L, hash64)
        val target = NetworkModCacheFiles.activeFile(root, descriptor)
        NetworkModCacheFiles.atomicWrite(target, byteArrayOf(1, 2), root)
        assertTrue(target.exists())
        NetworkModCacheFiles.atomicWrite(target, byteArrayOf(3, 4, 5), root)
        assertEquals(3, target.length())
    }

    @Test
    fun atomicWriteRejectsPathOutsideRoot() {
        val root = createTempDirectory().toFile()
        val outside = createTempDirectory().toFile().resolve("escape.rwmod")
        assertNull(runCatching { NetworkModCacheFiles.atomicWrite(outside, byteArrayOf(1), root) }.getOrNull())
        assertFalse(outside.exists())
    }

    @Test
    fun validatePayloadChecksSizeAndHash() {
        val root = createTempDirectory().toFile()
        val bytes = ByteArray(4) { it.toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("mod", bytes)
        val file = NetworkModCacheFiles.activeFile(root, descriptor)
        NetworkModCacheFiles.atomicWrite(file, bytes, root)
        assertTrue(NetworkModCacheFiles.validatePayload(file, descriptor))

        val wrong = NetworkModDescriptor("mod", 4L, hash64)
        assertFalse(NetworkModCacheFiles.validatePayload(file, wrong))
    }

    @Test
    fun deleteOrQuarantineFallsBackToNonRwmodNameWhenDeleteFails() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod", 1L, hash64)
        val file = NetworkModCacheFiles.activeFile(root, descriptor)
        NetworkModCacheFiles.atomicWrite(file, byteArrayOf(1), root)
        assertTrue(file.exists())
        // Normal delete succeeds:
        assertTrue(NetworkModCacheFiles.deleteOrQuarantine(root, file))
        assertFalse(file.exists())
    }

    @Test
    fun partialMetaRoundTripsAndExpiryCleanupWorks() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod", 7L, hash64)
        val dir = NetworkModCacheFiles.partialDir(root, descriptor).apply { mkdirs() }
        NetworkModCacheFiles.ensurePartialMeta(dir, 1_000L)
        assertEquals(1_000L, NetworkModCacheFiles.readPartialCreatedAt(dir))
        // 已存在 meta 时不覆盖：追加块不刷新 TTL
        NetworkModCacheFiles.ensurePartialMeta(dir, 2_000L)
        assertEquals(1_000L, NetworkModCacheFiles.readPartialCreatedAt(dir))
        // 未过期 → 保留
        NetworkModCacheFiles.cleanupExpiredPartialDirs(root, 1_500L)
        assertTrue(dir.exists())
        // 过期 → 清理
        NetworkModCacheFiles.cleanupExpiredPartialDirs(root, 1_000L + NETWORK_MOD_CACHE_TTL_MILLIS + 1)
        assertFalse(dir.exists())
    }

    @Test
    fun partialFileNamingIsStable() {
        val root = createTempDirectory().toFile()
        val descriptor = NetworkModDescriptor("mod", 7L, hash64)
        val dir = NetworkModCacheFiles.partialDir(root, descriptor)
        assertTrue(dir.name.startsWith(NetworkModCacheFiles.PARTIAL_DIR_PREFIX))
        assertEquals("chunk-3", NetworkModCacheFiles.partialChunkFile(dir, 3).name)
    }

    @Test
    fun requireInsideRejectsRootItself() {
        val root = createTempDirectory().toFile()
        assertNull(runCatching { NetworkModCacheFiles.requireInside(root, root) }.getOrNull())
    }
}
