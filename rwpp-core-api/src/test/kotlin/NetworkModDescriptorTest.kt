/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.NETWORK_MOD_CACHE_FUTURE_SKEW_MILLIS
import io.github.rwpp.game.mod.NETWORK_MOD_CACHE_TTL_MILLIS
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkModDescriptorTest {
    private val hash64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val hash64b = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    @Test
    fun equalityRequiresAllThreeFields() {
        val a = NetworkModDescriptor("mod", 100L, hash64)
        assertEquals(a, NetworkModDescriptor("mod", 100L, hash64))
        assertNotEquals(a, NetworkModDescriptor("mod", 101L, hash64))
        assertNotEquals(a, NetworkModDescriptor("mod", 100L, hash64b))
        assertNotEquals(a, NetworkModDescriptor("other", 100L, hash64))
    }

    @Test
    fun sha256IsNormalizedToLowerCase() {
        val upper = hash64.uppercase()
        val descriptor = NetworkModDescriptor("mod", 1L, upper)
        assertEquals(hash64, descriptor.normalizedSha256)
    }

    @Test
    fun rejectsInvalidHashes() {
        assertNull(runCatching { NetworkModDescriptor("mod", 1L, "nothex") }.getOrNull())
        assertNull(runCatching { NetworkModDescriptor("mod", 1L, hash64.substring(1)) }.getOrNull())
    }

    @Test
    fun rejectsNegativeSize() {
        assertNull(runCatching { NetworkModDescriptor("mod", -1L, hash64) }.getOrNull())
    }

    @Test
    fun rejectsBlankName() {
        assertNull(runCatching { NetworkModDescriptor("  ", 1L, hash64) }.getOrNull())
    }

    @Test
    fun cacheKeyIsStableForUnicodeNames() {
        val descriptor = NetworkModDescriptor("模组-α", 42L, hash64)
        val first = descriptor.cacheKey()
        val rebuilt = NetworkModDescriptor("模组-α", 42L, hash64.uppercase())
        assertEquals(first, rebuilt.cacheKey(), "cache key must ignore hash case and be stable")
    }

    @Test
    fun cacheKeyDiffersWhenHashCaseVaryOnlyInNameBytes() {
        // Names that differ only in length-prefixed content must not collide with size/hash.
        val a = NetworkModDescriptor("ab", 1L, hash64)
        val b = NetworkModDescriptor("a", 11L, hash64)
        assertNotEquals(a.cacheKey(), b.cacheKey())
    }

    @Test
    fun matchesReturnsTrueOnlyForExactBytes() {
        val bytes = ByteArray(10) { it.toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("mod", bytes)
        assertTrue(descriptor.matches(bytes))
        assertFalse(descriptor.matches(ByteArray(10)))
    }

    @Test
    fun safeNamePrefixSanitizesReservedAndUnsafeChars() {
        assertEquals("mod_CON", NetworkModCacheFiles.safeNamePrefix("CON"))
        assertEquals("a_b", NetworkModCacheFiles.safeNamePrefix("a/b"))
        assertEquals("mod", NetworkModCacheFiles.safeNamePrefix("..."))
    }

    @Test
    fun expiryBoundaryAt24Hours() {
        val now = 1_000_000_000L
        assertFalse(NetworkModCacheFiles.isExpired(now, now))
        assertFalse(NetworkModCacheFiles.isExpired(now, now + NETWORK_MOD_CACHE_TTL_MILLIS - 1))
        assertTrue(NetworkModCacheFiles.isExpired(now, now + NETWORK_MOD_CACHE_TTL_MILLIS))
    }

    @Test
    fun futureTimestampBeyondSkewExpires() {
        val now = 1_000_000_000L
        val barelyFuture = now + NETWORK_MOD_CACHE_FUTURE_SKEW_MILLIS
        assertFalse(NetworkModCacheFiles.isExpired(barelyFuture, now))
        val farFuture = now + NETWORK_MOD_CACHE_FUTURE_SKEW_MILLIS + 1
        assertTrue(NetworkModCacheFiles.isExpired(farFuture, now))
    }
}
