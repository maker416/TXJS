/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.theme.GameArtOverlaySynchronizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

class GameArtOverlaySynchronizerTest {
    @Test
    fun startupDoesNotReloadAndMatchingRestoreIsIdempotent() = runBlocking {
        var marker: String? = "forest"
        var rebuilds = 0
        var reloads = 0
        val sync = GameArtOverlaySynchronizer({ marker }, { rebuilds++ }, { marker = it }, { reloads++ })
        sync.atStartup("forest")
        sync.sync("forest")
        assertEquals(0, rebuilds)
        assertEquals(0, reloads)
    }

    @Test
    fun enableAndDisableReloadBeforeCommittingAndDoNotRepeat() = runBlocking {
        var marker: String? = null
        val calls = mutableListOf<String>()
        val sync = GameArtOverlaySynchronizer({ marker }, { calls += "rebuild" }, {
            calls += "commit:$it"; marker = it
        }, { calls += "reload" })
        sync.atStartup(null)
        sync.sync("forest")
        sync.sync(null)
        sync.sync(null)
        assertEquals(listOf("rebuild", "reload", "commit:forest", "rebuild", "reload", "commit:null"), calls)
        assertNull(marker)
    }

    @Test
    fun failedDisableRetriesEvenAfterDirectoryAndMarkerWereDeleted() = runBlocking {
        var marker: String? = "forest"
        var reloads = 0
        var fail = true
        val sync = GameArtOverlaySynchronizer({ marker }, { marker = null }, { marker = it }, {
            reloads++
            if (fail) error("texture decoder failed")
        })
        sync.atStartup("forest")
        assertFailsWith<IllegalStateException> { sync.sync(null) }
        assertNull(marker) // 磁盘已恢复，内存仍旧图；null == null 不能跳过重试。
        fail = false
        sync.sync(null)
        sync.sync(null)
        assertEquals(2, reloads)
    }

    @Test
    fun failedEnableDoesNotTreatNewDiskMarkerAsLoadedTextures() = runBlocking {
        var marker: String? = null
        var reloads = 0
        val sync = GameArtOverlaySynchronizer({ marker }, { marker = "forest" }, { marker = it }, {
            if (++reloads == 1) error("reload failed after overlay swap")
        })
        sync.atStartup(null)
        assertFailsWith<IllegalStateException> { sync.sync("forest") }
        sync.sync("forest")
        assertEquals(2, reloads)
    }

    @Test
    fun failedRebuildAndMarkerCommitBothRemainRetryable() = runBlocking {
        var marker: String? = null
        var rebuilds = 0
        var reloads = 0
        var commits = 0
        val sync = GameArtOverlaySynchronizer({ marker }, {
            if (++rebuilds == 1) error("disk full")
        }, {
            if (++commits == 1) error("marker write failed")
            marker = it
        }, { reloads++ })
        sync.atStartup(null)
        assertFailsWith<IllegalStateException> { sync.sync("forest") }
        assertEquals(0, reloads)
        assertFailsWith<IllegalStateException> { sync.sync("forest") }
        sync.sync("forest")
        assertEquals(3, rebuilds)
        assertEquals(2, reloads)
        assertEquals("forest", marker)
    }

    @Test
    fun sessionGuardRunsOnlyForActualSourceChangesAndRejectsBeforeRebuild() = runBlocking {
        var marker: String? = "forest"
        var guarded = true
        var rebuilds = 0
        val sync = GameArtOverlaySynchronizer({ marker }, { rebuilds++ }, { marker = it }, {}, {
            check(!guarded) { "active session" }
        })
        sync.atStartup("forest")
        sync.sync("forest") // 同一来源的启动恢复不应因为房间状态而失败。
        assertFailsWith<IllegalStateException> { sync.sync(null) }
        assertEquals(0, rebuilds)
        assertEquals("forest", marker)
        guarded = false
        sync.sync(null)
        assertEquals(1, rebuilds)
        assertNull(marker)
    }

    @Test
    fun simultaneousRequestsCannotRebuildDuringTextureLoading() = runBlocking {
        var marker: String? = null
        var rebuilds = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sync = GameArtOverlaySynchronizer({ marker }, { rebuilds++ }, { marker = it }, {
            entered.complete(Unit)
            release.await()
        })
        sync.atStartup(null)
        val first = async { sync.sync("forest") }
        entered.await()
        val second = async { sync.sync(null) }
        yield()
        assertEquals(1, rebuilds)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(2, rebuilds)
        assertNull(marker)
    }
}
