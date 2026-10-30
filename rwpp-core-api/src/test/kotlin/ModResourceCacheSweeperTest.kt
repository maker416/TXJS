/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.ModResourceCacheAdapter
import io.github.rwpp.game.mod.ModResourceCacheSweeper
import io.github.rwpp.game.mod.ReferenceFieldScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class ModResourceCacheSweeperTest {
    // GameStartSafety 是进程级状态，其他测试（如 MatchStartGateTest）可能留下失败标记。
    @Suppress("UNCHECKED_CAST")
    private val safety = GameStartSafety::class.java.getDeclaredField("failure").run {
        isAccessible = true
        get(null) as MutableStateFlow<GameStartSafety.Failure>
    }
    private var previousSafety = GameStartSafety.Failure.NONE

    @BeforeTest fun clearSafety() { previousSafety = safety.value; safety.value = GameStartSafety.Failure.NONE }
    @AfterTest fun restoreSafety() { safety.value = previousSafety }

    private class Image(val name: String, var used: Boolean = false, val dependents: List<Image> = emptyList())
    private class Sound(val name: String, var used: Boolean = false)

    private class FakeAdapter : ModResourceCacheAdapter<Image, Sound> {
        override val images = linkedMapOf<String, Image>()
        override val sounds = linkedMapOf<String, Sound>()
        val retained = mutableSetOf<Image>()
        val releasedImages = mutableListOf<String>()
        val releasedSounds = mutableListOf<String>()
        var failRelease: String? = null
        var failRetained = false

        override fun isImageUsed(image: Image) = image.used
        override fun isSoundUsed(sound: Sound) = sound.used
        override fun retainedImages(): Set<Image> = if (failRetained) error("binding missing") else retained
        override fun dependentImages(image: Image) = image.dependents
        override fun releaseImage(image: Image) {
            if (image.name == failRelease) error("GL failure")
            releasedImages += image.name
        }
        override fun releaseSound(sound: Sound) { releasedSounds += sound.name }
    }

    @Test fun releasesOnlyUnusedUnretainedEntriesAndKeepsTheRest() {
        val adapter = FakeAdapter()
        val live = Image("live", used = true)
        val referenced = Image("referenced")
        adapter.images += mapOf("a/live.png" to live, "a/old.png" to Image("old"), "b/ref.png" to referenced)
        adapter.retained += referenced
        adapter.sounds += mapOf("a/fire.wav" to Sound("fire"), "b/hit.wav" to Sound("hit", used = true))

        val stats = assertNotNull(ModResourceCacheSweeper.sweep(adapter))

        assertEquals(setOf("a/live.png", "b/ref.png"), adapter.images.keys)
        assertEquals(setOf("b/hit.wav"), adapter.sounds.keys)
        assertEquals(listOf("old"), adapter.releasedImages)
        assertEquals(listOf("fire"), adapter.releasedSounds)
        assertEquals(1, stats.removedImageKeys)
        assertEquals(1, stats.releasedSounds)
        assertEquals(0, stats.failures)
    }

    @Test fun dependentsAreReleasedBeforeTheirParentButNeverWhenStillLive() {
        val adapter = FakeAdapter()
        val shared = Image("shared-placeholder")
        val livePeer = Image("live-peer", used = true)
        val parent = Image("parent", dependents = listOf(Image("team1"), shared, livePeer, Image("team2")))
        adapter.images += mapOf("a/body.png" to parent, "b/peer.png" to livePeer)
        adapter.retained += shared

        ModResourceCacheSweeper.sweep(adapter)

        assertEquals(listOf("team1", "team2", "parent"), adapter.releasedImages)
        assertEquals(setOf("b/peer.png"), adapter.images.keys)
    }

    @Test fun anObjectCachedUnderSeveralKeysIsReleasedOnceAndOnlyWhenNoKeyUsesIt() {
        val adapter = FakeAdapter()
        val aliased = Image("aliased")
        val sharedStale = Image("shared-stale")
        adapter.images += mapOf("a/x.png" to aliased, "b/x.png" to aliased, "c/y.png" to sharedStale, "d/y.png" to sharedStale)
        aliased.used = true

        ModResourceCacheSweeper.sweep(adapter)

        assertEquals(listOf("shared-stale"), adapter.releasedImages)
        assertEquals(setOf("a/x.png", "b/x.png"), adapter.images.keys)
    }

    @Test fun bindingFailureLeavesTheCachesUntouched() {
        val adapter = FakeAdapter()
        adapter.images["a/old.png"] = Image("old")
        adapter.sounds["a/fire.wav"] = Sound("fire")
        adapter.failRetained = true

        assertNull(ModResourceCacheSweeper.sweep(adapter))

        assertEquals(setOf("a/old.png"), adapter.images.keys)
        assertEquals(setOf("a/fire.wav"), adapter.sounds.keys)
        assertTrue(adapter.releasedImages.isEmpty())
    }

    @Test fun oneFailedReleaseDoesNotStopTheOthers() {
        val adapter = FakeAdapter()
        adapter.images += mapOf("a/1.png" to Image("one"), "a/2.png" to Image("two"), "a/3.png" to Image("three"))
        adapter.failRelease = "two"

        val stats = assertNotNull(ModResourceCacheSweeper.sweep(adapter))

        assertEquals(listOf("one", "three"), adapter.releasedImages)
        assertEquals(1, stats.failures)
        assertTrue(adapter.images.isEmpty(), "失败的释放仍应从缓存移除，避免下轮再次命中半释放对象")
    }

    @Test fun detachAndReleaseCanRunSeparately() {
        val adapter = FakeAdapter()
        adapter.images["a/old.png"] = Image("old")

        val detached = assertNotNull(ModResourceCacheSweeper.detach(adapter))
        assertTrue(adapter.images.isEmpty())
        assertTrue(adapter.releasedImages.isEmpty(), "移出阶段不得调用释放（可能不在 GL 线程）")

        ModResourceCacheSweeper.release(adapter, detached)
        assertEquals(listOf("old"), adapter.releasedImages)
    }

    @Test fun nothingIsSweptAfterAFailedLoad() {
        safety.value = GameStartSafety.Failure.LOAD_FAILED
        val adapter = FakeAdapter()
        adapter.images["a/old.png"] = Image("old")
        assertNull(ModResourceCacheSweeper.sweep(adapter))
        assertEquals(setOf("a/old.png"), adapter.images.keys)
    }

    private open class Base { val inherited: Image? = Image("inherited") }
    private class Holder : Base() {
        val single = Image("single")
        val array = arrayOf(Image("e0"), null, Image("e1"))
        val unrelated = "text"
        companion object { @JvmStatic val ignoredStatic = Image("static") }
    }

    @Test fun scannerCollectsInheritedFieldsAndArraysButNotStatics() {
        val scanner = ReferenceFieldScanner(Image::class.java)
        val holder = Holder()

        val found = scanner.collect(listOf(holder)).map { it.name }.toSet()

        assertEquals(setOf("inherited", "single", "e0", "e1"), found)
        assertEquals(3, scanner.referenceFields(Holder::class.java).size)
    }
}
