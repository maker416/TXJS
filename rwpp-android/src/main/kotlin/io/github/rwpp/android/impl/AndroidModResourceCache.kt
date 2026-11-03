/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.media.SoundPool
import com.corrodinggames.rts.game.units.custom.ag as UnitLoader
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.a.a as AndroidSoundFactory
import com.corrodinggames.rts.gameFramework.a.b as PoolSound
import com.corrodinggames.rts.gameFramework.a.i as EngineSound
import com.corrodinggames.rts.gameFramework.k as GameEngine
import com.corrodinggames.rts.gameFramework.m.e as EngineImage
import com.corrodinggames.rts.gameFramework.m.f as LazyTeamImage
import io.github.rwpp.game.mod.ModResourceCacheAdapter
import io.github.rwpp.game.mod.ModResourceCacheSweeper
import io.github.rwpp.game.mod.ReferenceFieldScanner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 引擎缓存适配器（字段名对应 lib/android-game-lib.jar）。
 *
 * - 贴图：只从 `ag.g` 移出，**不** `recycle()`、不清空 Bitmap 字段。GL 线程可能仍在上传或绘制该
 *   Bitmap；GL 纹理缓存（`b.h.c`）每 60 帧删除未绘制的纹理，之后 Bitmap 不可达，由 GC 回收像素。
 *   队伍着色副本（`m.f` 私有 `I`）随父贴图一同不可达，无需单独处理。
 * - 音效：`SoundPool.unload` 后把 id 置 0；播放 0 / 已卸载 id 时 SoundPool 只返回失败。
 */
internal class AndroidModResourceCache(
    private val definitions: () -> List<Any> = {
        synchronized(UnitDefinition.c) { UnitDefinition.c.toList() }
    },
    private val placeholders: () -> List<EngineImage> = { listOfNotNull(GameEngine.t()?.bL?.o()) },
    private val unload: (SoundPool, Int) -> Unit = { pool, id -> pool.unload(id) },
    private val imageMap: () -> MutableMap<String, EngineImage> = {
        @Suppress("UNCHECKED_CAST")
        UnitLoader.g as MutableMap<String, EngineImage>
    },
    private val soundMap: () -> MutableMap<String, EngineSound> = {
        @Suppress("UNCHECKED_CAST")
        UnitLoader.h as MutableMap<String, EngineSound>
    },
) : ModResourceCacheAdapter<EngineImage, EngineSound> {
    override val images: MutableMap<String, EngineImage> get() = imageMap()
    override val sounds: MutableMap<String, EngineSound> get() = soundMap()

    // 着色器模式下 ag.a(e) 跳过 m.f，不会置 v；包装对象只能随父贴图失效。
    override fun isImageUsed(image: EngineImage): Boolean = image.v || image is LazyTeamImage

    override fun isSoundUsed(sound: EngineSound): Boolean = sound.g

    override fun retainedImages(): Set<EngineImage> =
        scanner.collect(definitions()) + placeholders()

    /** Bitmap 与着色副本交给 GC；没有需要主动释放的依赖。 */
    override fun dependentImages(image: EngineImage): List<EngineImage> = emptyList()

    /** 移出缓存即完成；见类注释，主动 recycle 会与 GL 线程竞争。 */
    override fun releaseImage(image: EngineImage) = Unit

    override fun releaseSound(sound: EngineSound) {
        if (sound !is PoolSound) return
        val binding = binding.getOrThrow()
        val id = binding.soundId.getInt(sound)
        if (id <= 0) return
        val factory = (binding.factory.get(sound) ?: binding.outerFactory.get(sound)) as AndroidSoundFactory? ?: return
        val pool = binding.pool.get(factory) as SoundPool? ?: return
        binding.soundId.setInt(sound, 0)
        unload(pool, id)
    }

    private class Binding {
        val soundId: java.lang.reflect.Field = PoolSound::class.java.getDeclaredField("b").apply { isAccessible = true }
        val factory: java.lang.reflect.Field = PoolSound::class.java.getDeclaredField("a").apply { isAccessible = true }
        val outerFactory: java.lang.reflect.Field = PoolSound::class.java.getDeclaredField("c").apply { isAccessible = true }
        val pool: java.lang.reflect.Field = AndroidSoundFactory::class.java.getDeclaredField("g").apply { isAccessible = true }
        init {
            // 单位定义至少要有主体/残骸等贴图字段；为空说明引擎结构已变，不能依赖保底判断。
            check(scanner.referenceFields(UnitDefinition::class.java).size >= MIN_DEFINITION_IMAGE_FIELDS) {
                "unit definition image fields not found"
            }
        }
    }

    companion object {
        const val MIN_DEFINITION_IMAGE_FIELDS = 4
        private val scanner = ReferenceFieldScanner(EngineImage::class.java)
        private val binding: Result<Binding> by lazy { runCatching { Binding() } }
        private val warned = AtomicBoolean(false)

        /** 引擎结构与预期一致；否则整个清理关闭（只记一次日志）。 */
        val available: Boolean get() = binding.isSuccess

        /** 只在一次成功重载之后、执行重载的同一线程上调用。不需要 GL 上下文。 */
        fun sweep() {
            binding.exceptionOrNull()?.let { error ->
                if (warned.compareAndSet(false, true)) {
                    runCatching { io.github.rwpp.logger.warn("[MODCACHE] disabled: {}", error.toString()) }
                }
                return
            }
            ModResourceCacheSweeper.sweep(AndroidModResourceCache())
        }
    }
}
