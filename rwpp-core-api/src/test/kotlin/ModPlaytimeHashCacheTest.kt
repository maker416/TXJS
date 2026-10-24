/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModPlaytimeHash
import io.github.rwpp.game.mod.ModPlaytimeHashCache
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ModPlaytimeHashCacheTest {
    private lateinit var root: File
    private lateinit var store: File
    @BeforeTest fun setup() {
        root = Files.createTempDirectory("playtime-cache-").toFile()
        store = File(root, "cache/identities.json")
    }
    @AfterTest fun cleanup() { root.deleteRecursively() }
    private fun mod(name: String = "large.rwmod") = File(root, name).apply { writeText("mod content") }
    private fun cache(calls: AtomicInteger, capacity: Int = 256) = ModPlaytimeHashCache(store, capacity) { f, check ->
        calls.incrementAndGet()
        ModPlaytimeHash.sha256(f, check)
    }

    @Test fun sameContentReusedAcrossMatchesAndRestart() = runBlocking {
        val source = mod()
        val calls = AtomicInteger()
        val first = cache(calls)
        val expected = first.sha256(source)
        repeat(10) { assertEquals(expected, first.sha256(source)) }
        assertEquals(expected, cache(calls).sha256(source))
        assertEquals(1, calls.get())
    }

    @Test fun directoryEditsAddsRemovesAndRenamesInvalidate() = runBlocking {
        val folder = File(root, "folder").apply { mkdirs() }
        val a = File(folder, "a.ini").apply { writeText("one") }
        val calls = AtomicInteger(); val cache = cache(calls)
        val first = cache.sha256(folder)
        assertEquals(ModPlaytimeHash.sha256(folder), first)
        a.writeText("longer")
        assertNotEquals(first, cache.sha256(folder))
        val b = File(folder, "b.ini").apply { writeText("two") }
        cache.sha256(folder)
        assertTrue(b.renameTo(File(folder, "c.ini"))); cache.sha256(folder)
        assertTrue(a.delete()); cache.sha256(folder)
        assertEquals(5, calls.get())
    }

    @Test fun explicitReplacementInvalidatesEvenWithSameMetadata() = runBlocking {
        val source = mod(); val time = source.lastModified(); val calls = AtomicInteger()
        val cache = cache(calls); val old = cache.sha256(source)
        ModPlaytimeHashCache.sourceChanged(source)
        source.writeText("new content")
        assertTrue(source.setLastModified(time))
        assertNotEquals(old, cache(calls).sha256(source))
        assertEquals(2, calls.get())
    }

    @Test fun fileLengthOrMtimeInvalidates() = runBlocking {
        val source = mod(); val calls = AtomicInteger(); val cache = cache(calls)
        cache.sha256(source)
        source.appendText(" changed"); cache.sha256(source)
        assertTrue(source.setLastModified(source.lastModified() + 2000)); cache.sha256(source)
        assertEquals(3, calls.get())
    }

    @Test fun concurrentRequestsOnlyComputeOnce() = runBlocking {
        val source = mod(); val calls = AtomicInteger(); val cache = cache(calls)
        val results = List(8) { async { cache.sha256(source) } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(1, results.toSet().size)
    }

    @Test fun changingSourceDuringHashDoesNotCache() = runBlocking {
        val source = mod()
        val cache = ModPlaytimeHashCache(store) { f, check ->
            val result = ModPlaytimeHash.sha256(f, check)
            f.appendText("changed")
            result
        }
        assertFailsWith<IOException> { cache.sha256(source) }
        assertFalse(store.exists())
        assertEquals(ModPlaytimeHash.sha256(source), ModPlaytimeHashCache(store).sha256(source))
    }

    @Test fun cancellationDoesNotCacheOrBlockNextRequest() = runBlocking {
        val source = mod()
        val entered = CompletableDeferred<Unit>()
        val cache = ModPlaytimeHashCache(store) { _, check ->
            entered.complete(Unit)
            while (true) { check(); Thread.yield() }
            @Suppress("UNREACHABLE_CODE") ""
        }
        val job = launch { cache.sha256(source) }
        entered.await(); job.cancelAndJoin()
        assertFalse(store.exists())
        assertEquals(ModPlaytimeHash.sha256(source), ModPlaytimeHashCache(store).sha256(source))
    }

    @Test fun invalidationDuringHashRejectsResult() = runBlocking {
        val source = mod()
        val cache = ModPlaytimeHashCache(store) { f, check ->
            val hash = ModPlaytimeHash.sha256(f, check)
            ModPlaytimeHashCache.sourceChanged(f)
            hash
        }
        assertFailsWith<IllegalStateException> { cache.sha256(source) }
        val calls = AtomicInteger(); cache(calls).sha256(source)
        assertEquals(1, calls.get())
    }

    @Test fun corruptOrOldStoreAndWriteFailureAreOptionalCacheMisses() = runBlocking {
        val source = mod(); store.parentFile.mkdirs(); store.writeText("broken")
        val calls = AtomicInteger(); cache(calls).sha256(source)
        store.writeText("{\"version\":99,\"entries\":[]}")
        cache(calls).sha256(source)
        assertEquals(2, calls.get())
        val blockedParent = File(root, "blocked").apply { writeText("file") }
        val blocked = ModPlaytimeHashCache(File(blockedParent, "cache.json"))
        assertEquals(ModPlaytimeHash.sha256(source), blocked.sha256(source))
    }

    @Test fun leastRecentlyUsedEntriesEvicted() = runBlocking {
        val a=mod("a"); val b=mod("b"); val c=mod("c")
        val calls=AtomicInteger(); val cache=cache(calls, 2)
        cache.sha256(a); cache.sha256(b); cache.sha256(a); cache.sha256(c)
        cache.sha256(a); assertEquals(3, calls.get())
        cache.sha256(b); assertEquals(4, calls.get())
    }

    @Test fun outsideLinksAndCyclesAreRejectedBeforeHashing() = runBlocking {
        val folder = File(root, "links").apply { mkdirs() }
        val outside = mod("outside")
        val link = File(folder, "out")
        // Windows 未授予符号链接权限的机器由设备验收补充。
        if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return@runBlocking
        assertFailsWith<IOException> { ModPlaytimeHashCache(store).sha256(folder) }
        Files.delete(link.toPath())
        Files.createSymbolicLink(link.toPath(), folder.toPath())
        try { assertFailsWith<IOException> { ModPlaytimeHashCache(store).sha256(folder) } }
        finally { Files.delete(link.toPath()) }
    }
}
