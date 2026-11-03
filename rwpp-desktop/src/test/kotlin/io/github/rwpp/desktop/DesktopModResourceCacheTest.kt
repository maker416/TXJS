/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.desktop

import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.a.i as EngineSound
import com.corrodinggames.rts.gameFramework.m.e as EngineImage
import com.corrodinggames.rts.gameFramework.m.h as LazyTeamImage
import com.corrodinggames.rts.java.q as SlickSound
import com.corrodinggames.rts.java.s as SlickImage
import com.corrodinggames.rts.java.audio.Sound
import io.github.rwpp.desktop.impl.DesktopModResourceCache
import io.github.rwpp.game.mod.ModResourceCacheSweeper
import io.github.rwpp.game.mod.ReferenceFieldScanner
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.*

/** 用真实引擎类（无 GL 上下文）验证适配器对桌面结构的判断与释放顺序；GL 销毁由注入函数记录。 */
class DesktopModResourceCacheTest {
    private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as sun.misc.Unsafe

    private class Definition(@JvmField val body: EngineImage?, @JvmField val frames: Array<EngineImage?>)

    @Test fun engineStructureMatchesTheAdapterBinding() {
        assertTrue(DesktopModResourceCache.available, "game-lib 结构变化：适配器必须关闭清理而非误判")
        val fields = ReferenceFieldScanner(EngineImage::class.java).referenceFields(UnitDefinition::class.java)
        assertTrue(fields.size >= DesktopModResourceCache.MIN_DEFINITION_IMAGE_FIELDS, "单位定义贴图字段：${fields.map { it.name }}")
    }

    @Test fun staleImageReleasesTeamClonesThenParentAndKeepsReferencedPlaceholderAndLiveEntries() {
        val destroyed = Collections.newSetFromMap(IdentityHashMap<EngineImage, Boolean>())
        val order = mutableListOf<EngineImage>()
        val images = linkedMapOf<String, EngineImage>()

        val parent = SlickImage()
        val realizedClone = SlickImage()
        val lazy = LazyTeamImage(parent, 0xff0000, com.corrodinggames.rts.game.o.a, 1)
        LazyTeamImage::class.java.getDeclaredField("I").apply { isAccessible = true }.set(lazy, realizedClone)
        val eagerClone = SlickImage()
        val placeholder = SlickImage()
        parent.a = arrayOf(parent, lazy, eagerClone, placeholder)
        val live = SlickImage().apply { v = true }
        val referenced = SlickImage()
        images += mapOf("[false,false]mods/A/body.png" to parent, "[false,false]mods/B/body.png" to live,
            "[false,false]mods/C/body.png" to referenced, "[autoShadow:1,1]9-1" to placeholder)

        val adapter = DesktopModResourceCache(
            definitions = { listOf(Definition(null, arrayOf(referenced, null))) },
            placeholders = { listOf(placeholder) },
            destroyImage = { destroyed += it; order += it },
            imageMap = { images },
            soundMap = { linkedMapOf() },
        )
        val stats = assertNotNull(ModResourceCacheSweeper.sweep(adapter))

        assertEquals(listOf<EngineImage>(realizedClone, eagerClone, parent), order, "依赖先于父贴图，且各只销毁一次")
        assertFalse(placeholder in destroyed, "共享占位图不得销毁")
        assertFalse(lazy in destroyed, "着色包装不持有纹理")
        assertNull(parent.a, "父贴图的队伍缓存应被清空")
        assertEquals(setOf("[false,false]mods/B/body.png", "[false,false]mods/C/body.png", "[autoShadow:1,1]9-1"), images.keys)
        assertEquals(1, stats.removedImageKeys)
    }

    @Test fun lazyTeamWrapperAsACacheValueIsNeverReleasedOnItsOwn() {
        val parent = SlickImage()
        val lazy = LazyTeamImage(parent, 0xff0000, com.corrodinggames.rts.game.o.a, 1)
        val images = linkedMapOf<String, EngineImage>("odd" to lazy)
        val adapter = DesktopModResourceCache(definitions = { emptyList() }, placeholders = { emptyList() },
            destroyImage = { fail("不得销毁") }, imageMap = { images }, soundMap = { linkedMapOf() })

        ModResourceCacheSweeper.sweep(adapter)

        assertSame(lazy, images["odd"])
    }

    @Test fun staleSoundIsDisposedUnderThePlaybackLockAndCannotPlayAgain() {
        var disposed = 0
        val loaded = Proxy.newProxyInstance(Sound::class.java.classLoader, arrayOf(Sound::class.java)) { _, method, _ ->
            if (method.name == "dispose") disposed++
            when (method.returnType) { java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; Integer.TYPE -> 0; else -> null }
        } as Sound
        val sound = unsafe.allocateInstance(SlickSound::class.java) as SlickSound
        val field = SlickSound::class.java.getDeclaredField("a").apply { isAccessible = true }
        field.set(sound, loaded)
        val used = (unsafe.allocateInstance(SlickSound::class.java) as SlickSound).apply { g = true }
        val sounds = linkedMapOf<String, EngineSound>("mods/A/fire.wav" to sound, "mods/B/hit.wav" to used)
        val lock = Any()
        val adapter = DesktopModResourceCache(definitions = { emptyList() }, placeholders = { emptyList() },
            lockOf = { lock }, disposeSound = { s -> assertTrue(Thread.holdsLock(lock)); s.dispose() },
            imageMap = { linkedMapOf() }, soundMap = { sounds })

        ModResourceCacheSweeper.sweep(adapter)

        assertEquals(1, disposed)
        assertNull(field.get(sound), "释放后播放应走引擎的 \"Sound not loaded\" 分支")
        assertEquals(setOf("mods/B/hit.wav"), sounds.keys)
    }
}
