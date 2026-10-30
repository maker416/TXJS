/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.android

import android.media.SoundPool
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.a.a as AndroidSoundFactory
import com.corrodinggames.rts.gameFramework.a.b as PoolSound
import com.corrodinggames.rts.gameFramework.a.i as EngineSound
import com.corrodinggames.rts.gameFramework.m.e as EngineImage
import io.github.rwpp.android.impl.AndroidModResourceCache
import io.github.rwpp.game.mod.ModResourceCacheSweeper
import io.github.rwpp.game.mod.ReferenceFieldScanner
import kotlin.test.*

/** 用真实引擎类验证 Android 适配器；不构造 Bitmap / SoundPool（android.jar 为占位实现）。 */
class AndroidModResourceCacheTest {
    // 编译期 classpath 是 android.jar（无 sun.misc），运行在宿主 JVM 上，反射取得 Unsafe。
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    private fun <T> allocateInstance(type: Class<T>): T = type.cast(allocate.invoke(unsafe, type))

    private class Definition(@JvmField val body: EngineImage?)

    @Test fun engineStructureMatchesTheAdapterBinding() {
        assertTrue(AndroidModResourceCache.available, "android-game-lib 结构变化：适配器必须关闭清理而非误判")
        val fields = ReferenceFieldScanner(EngineImage::class.java).referenceFields(UnitDefinition::class.java)
        assertTrue(fields.size >= AndroidModResourceCache.MIN_DEFINITION_IMAGE_FIELDS, "单位定义贴图字段：${fields.map { it.name }}")
    }

    @Test fun staleImagesAreOnlyDroppedFromTheCacheNeverRecycled() {
        val stale = EngineImage()
        val live = EngineImage().apply { v = true }
        val referenced = EngineImage()
        val placeholder = EngineImage()
        val images = linkedMapOf("mods/A/body.png" to stale, "mods/B/body.png" to live,
            "mods/C/body.png" to referenced, "oom" to placeholder)
        val adapter = AndroidModResourceCache(
            definitions = { listOf(Definition(referenced)) },
            placeholders = { listOf(placeholder) },
            unload = { _, _ -> fail("没有音效需要卸载") },
            imageMap = { images },
            soundMap = { linkedMapOf() },
        )

        val stats = assertNotNull(ModResourceCacheSweeper.sweep(adapter))

        assertEquals(setOf("mods/B/body.png", "mods/C/body.png", "oom"), images.keys)
        assertEquals(1, stats.removedImageKeys)
    }

    @Test fun staleSoundIsUnloadedFromItsPoolAndMarkedNotLoaded() {
        val factory = allocateInstance(AndroidSoundFactory::class.java)
        val pool = allocateInstance(SoundPool::class.java)
        AndroidSoundFactory::class.java.getDeclaredField("g").apply { isAccessible = true }.set(factory, pool)
        val idField = PoolSound::class.java.getDeclaredField("b").apply { isAccessible = true }
        fun sound(id: Int, used: Boolean) = allocateInstance(PoolSound::class.java).also {
            PoolSound::class.java.getDeclaredField("a").apply { isAccessible = true }.set(it, factory)
            idField.setInt(it, id)
            it.g = used
        }
        val stale = sound(7, used = false)
        val sounds = linkedMapOf<String, EngineSound>("mods/A/fire.wav" to stale, "mods/B/hit.wav" to sound(8, used = true))
        val unloaded = mutableListOf<Pair<SoundPool, Int>>()
        val adapter = AndroidModResourceCache(definitions = { emptyList() }, placeholders = { emptyList() },
            unload = { p, id -> unloaded += p to id }, imageMap = { linkedMapOf() }, soundMap = { sounds })

        ModResourceCacheSweeper.sweep(adapter)

        assertEquals(1, unloaded.size)
        assertSame(pool, unloaded.single().first)
        assertEquals(7, unloaded.single().second)
        assertEquals(0, idField.getInt(stale), "id 置 0，排队中的播放请求只会失败")
        assertEquals(setOf("mods/B/hit.wav"), sounds.keys)
    }
}
