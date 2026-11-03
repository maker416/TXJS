/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.game.units.custom.ag as UnitLoader
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.a.i as EngineSound
import com.corrodinggames.rts.gameFramework.l as GameEngine
import com.corrodinggames.rts.gameFramework.m.e as EngineImage
import com.corrodinggames.rts.gameFramework.m.h as LazyTeamImage
import com.corrodinggames.rts.java.q as SlickSound
import com.corrodinggames.rts.java.s as SlickImage
import com.corrodinggames.rts.java.audio.Sound
import io.github.rwpp.game.mod.ModResourceCacheAdapter
import io.github.rwpp.game.mod.ReferenceFieldScanner

/**
 * 桌面引擎缓存适配器（字段名对应 lib/game-lib.jar）。
 *
 * - 贴图：`java.s` 构造时加入静态 `s.y`，只移出 `ag.g` 不会释放；必须 `o()`（移出 `s.y`、
 *   `Image.destroy()` 删除 GL 纹理并清 Slick 缓存），**须在持有主 GL 上下文的线程调用**。
 * - 队伍着色：`e.a/b/c` 下标 0 为父贴图；其余为 `m.h`（私有 `I` 为绘制时克隆）或直接克隆。
 * - 音效：`java.q.a` 为 OpenAL Sound；在播放锁内置空后 `dispose()`（先停播再删 buffer）。
 */
// public：rwpp-mod-heap-tool 的真实核心重载测试直接调用同一清理入口。
class DesktopModResourceCache internal constructor(
    private val definitions: () -> List<Any> = {
        synchronized(UnitDefinition.c) { UnitDefinition.c.toList() }
    },
    private val placeholders: () -> List<EngineImage> = { listOfNotNull(GameEngine.B()?.bO?.r()) },
    private val destroyImage: (EngineImage) -> Unit = { it.o() },
    private val lockOf: (SlickSound) -> Any? = { sound -> soundFactory.get(sound)?.let { (it as com.corrodinggames.rts.java.o).b() } },
    private val disposeSound: (Sound) -> Unit = { it.dispose() },
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

    // 着色器模式下 ag.a(e) 跳过 m.h，不会置 v；包装对象只能随父贴图释放。
    override fun isImageUsed(image: EngineImage): Boolean = image.v || image is LazyTeamImage

    override fun isSoundUsed(sound: EngineSound): Boolean = sound.g

    override fun retainedImages(): Set<EngineImage> =
        scanner.collect(definitions()) + placeholders()

    override fun dependentImages(image: EngineImage): List<EngineImage> {
        val result = mutableListOf<EngineImage>()
        listOf(image.a, image.b, image.c).forEach { team ->
            team?.forEach { child ->
                when {
                    child == null || child === image -> Unit
                    child is LazyTeamImage -> (lazyClone.get(child) as EngineImage?)
                        ?.takeIf { it !== image }?.let(result::add)
                    else -> result += child
                }
            }
        }
        return result
    }

    override fun releaseImage(image: EngineImage) {
        image.v()
        // m.h / m.ad 只是包装，o() 无实质释放；只销毁真正持有纹理的对象。
        if (image is SlickImage) destroyImage(image)
    }

    override fun releaseSound(sound: EngineSound) {
        if (sound !is SlickSound) return
        val lock = lockOf(sound) ?: return
        val loaded: Sound
        synchronized(lock) {
            loaded = soundField.get(sound) as Sound? ?: return
            soundField.set(sound, null)
            disposeSound(loaded)
        }
    }

    private val lazyClone get() = binding.getOrThrow().lazyClone
    private val soundField get() = binding.getOrThrow().soundField

    private class Binding {
        val lazyClone: java.lang.reflect.Field = LazyTeamImage::class.java.getDeclaredField("I").apply { isAccessible = true }
        val soundField: java.lang.reflect.Field = SlickSound::class.java.getDeclaredField("a").apply { isAccessible = true }
        val soundFactory: java.lang.reflect.Field = SlickSound::class.java.getDeclaredField("b").apply { isAccessible = true }
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
        private val soundFactory get() = binding.getOrThrow().soundFactory

        /** 引擎结构与预期一致；否则整个清理关闭（只记一次日志）。 */
        val available: Boolean get() = binding.isSuccess

        private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 只在一次成功重载之后、持有主 GL 上下文的游戏线程上调用。
         * 保连接重载请分开调用 [detach]（工作线程）与 [release]（游戏线程）。
         */
        fun sweep() {
            val adapter = adapter() ?: return
            io.github.rwpp.game.mod.ModResourceCacheSweeper.sweep(adapter)
        }

        fun detach(): io.github.rwpp.game.mod.DetachedModResources<EngineImage, EngineSound>? =
            adapter()?.let { io.github.rwpp.game.mod.ModResourceCacheSweeper.detach(it) }

        fun release(detached: io.github.rwpp.game.mod.DetachedModResources<EngineImage, EngineSound>?) {
            val adapter = adapter() ?: return
            io.github.rwpp.game.mod.ModResourceCacheSweeper.release(adapter, detached)
        }

        private fun adapter(): DesktopModResourceCache? {
            binding.exceptionOrNull()?.let { error ->
                if (warned.compareAndSet(false, true)) {
                    runCatching { io.github.rwpp.logger.warn("[MODCACHE] disabled: {}", error.toString()) }
                }
                return null
            }
            return DesktopModResourceCache()
        }
    }
}
