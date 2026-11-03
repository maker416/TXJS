/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.game.GameStartSafety
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 原版单位解析的贴图（`ag.g`）/ 音效（`ag.h`）缓存只在重载开始时把条目标记为未使用，
 * 从不清除；切换启用模组后旧模组资源永久留在进程内。平台适配器把引擎结构交给
 * [ModResourceCacheSweeper]，在一次**成功**的重载之后移除本轮未使用的条目并释放资源。
 *
 * 所有方法只在重载线程上调用；引擎缓存在此期间没有其他读写者。
 */
interface ModResourceCacheAdapter<I : Any, S : Any> {
    val images: MutableMap<String, I>
    val sounds: MutableMap<String, S>

    /** 本轮解析命中或新建过（引擎 `v` 标记）。无法判断的类型必须返回 true。 */
    fun isImageUsed(image: I): Boolean
    fun isSoundUsed(sound: S): Boolean

    /** 无论标记如何都不得释放：当前单位定义直接引用的贴图、引擎共享占位图等。 */
    fun retainedImages(): Set<I>

    /** 只属于 [image] 的派生贴图（队伍着色副本等），与父贴图一同释放。 */
    fun dependentImages(image: I): List<I>

    /** 释放单个贴图；可能需要在持有 GL 上下文的线程调用。 */
    fun releaseImage(image: I)
    fun releaseSound(sound: S)
}

/** 已从缓存移出、尚未释放的资源。可在另一线程交给 [ModResourceCacheSweeper.release]。 */
class DetachedModResources<I : Any, S : Any> internal constructor(
    internal val images: List<I>,
    internal val sounds: List<S>,
    val removedImageKeys: Int,
    val removedSoundKeys: Int,
) {
    val isEmpty: Boolean get() = images.isEmpty() && sounds.isEmpty() && removedImageKeys == 0 && removedSoundKeys == 0
}

data class ModResourceSweepStats(
    val removedImageKeys: Int,
    val removedSoundKeys: Int,
    val releasedImages: Int,
    val releasedSounds: Int,
    val failures: Int,
)

object ModResourceCacheSweeper {
    /**
     * 计算并移出本轮未使用的条目。计算全部完成后才修改缓存；任何异常都放弃本次清理
     * 且不修改缓存（失败时宁可保留旧资源）。重载失败、需重启时不得调用，内部再兜底检查。
     */
    fun <I : Any, S : Any> detach(adapter: ModResourceCacheAdapter<I, S>): DetachedModResources<I, S>? {
        if (GameStartSafety.requiresRestart) return null
        return try {
            val retained = identitySet<I>().apply { addAll(adapter.retainedImages()) }
            val usedImages = identitySet<I>()
            adapter.images.values.forEach { if (it in retained || adapter.isImageUsed(it)) usedImages += it }
            val staleImageKeys = adapter.images.filterValues { it !in usedImages }.keys.toList()

            val toRelease = identitySet<I>()
            val ordered = mutableListOf<I>()
            staleImageKeys.forEach { key ->
                val image = adapter.images.getValue(key)
                // 依赖先于父贴图释放；仍被使用或保留的对象（含共享占位图）一律跳过。
                (adapter.dependentImages(image) + image).forEach { candidate ->
                    if (candidate !in usedImages && candidate !in retained && toRelease.add(candidate)) ordered += candidate
                }
            }

            val usedSounds = identitySet<S>()
            adapter.sounds.values.forEach { if (adapter.isSoundUsed(it)) usedSounds += it }
            val staleSoundKeys = adapter.sounds.filterValues { it !in usedSounds }.keys.toList()
            val sounds = identitySet<S>()
            val orderedSounds = staleSoundKeys.mapNotNull { key -> adapter.sounds.getValue(key).takeIf { sounds.add(it) } }

            staleImageKeys.forEach { adapter.images.remove(it) }
            staleSoundKeys.forEach { adapter.sounds.remove(it) }
            DetachedModResources(ordered, orderedSounds, staleImageKeys.size, staleSoundKeys.size)
        } catch (error: Throwable) {
            // 清理是重载成功后的附加步骤，失败（含 OOM）只放弃清理，不能把成功的重载变成失败。
            log(true, "[MODCACHE] skip sweep: {}", error.toString())
            null
        }
    }

    /** 逐个释放；单个失败只记数，不影响其他资源。 */
    fun <I : Any, S : Any> release(
        adapter: ModResourceCacheAdapter<I, S>,
        detached: DetachedModResources<I, S>?,
    ): ModResourceSweepStats? {
        detached ?: return null
        var failures = 0
        var images = 0
        var sounds = 0
        detached.images.forEach { image ->
            try { adapter.releaseImage(image); images++ } catch (error: Throwable) {
                if (failures++ == 0) log(true, "[MODCACHE] release image failed: {}", error.toString())
            }
        }
        detached.sounds.forEach { sound ->
            try { adapter.releaseSound(sound); sounds++ } catch (error: Throwable) {
                if (failures++ == 0) log(true, "[MODCACHE] release sound failed: {}", error.toString())
            }
        }
        return ModResourceSweepStats(detached.removedImageKeys, detached.removedSoundKeys, images, sounds, failures).also {
            if (!detached.isEmpty) log(false, "[MODCACHE] swept {}", it)
        }
    }

    /** 同一线程内完成移出与释放。 */
    fun <I : Any, S : Any> sweep(adapter: ModResourceCacheAdapter<I, S>): ModResourceSweepStats? =
        release(adapter, detach(adapter))

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

    // 与 ModLoadSafety 相同：logger 只在应用启动后可用，日志失败不得影响清理。
    private fun log(warn: Boolean, message: String, arg: Any) {
        runCatching { if (warn) io.github.rwpp.logger.warn(message, arg) else io.github.rwpp.logger.info(message, arg) }
    }
}

/**
 * 按声明类型收集对象直接引用的 [type] 实例（单层字段及一维数组，含父类字段，不含静态字段）。
 * 只用于“仍被单位定义引用”的保底判断，不递归对象图。反射失败直接抛出，由调用方放弃清理。
 */
class ReferenceFieldScanner<T : Any>(private val type: Class<T>) {
    private val fields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun referenceFields(owner: Class<*>): List<Field> = fields.getOrPut(owner) {
        generateSequence(owner) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { field ->
                val declared = field.type
                type.isAssignableFrom(declared) || declared.isArray && type.isAssignableFrom(declared.componentType)
            }
            .onEach { it.isAccessible = true }
            .toList()
    }

    fun collect(roots: Iterable<Any>): Set<T> {
        val found: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
        roots.forEach { root ->
            referenceFields(root.javaClass).forEach { field ->
                when (val value = field.get(root)) {
                    null -> Unit
                    is Array<*> -> value.forEach { element -> if (type.isInstance(element)) found += type.cast(element) }
                    else -> if (type.isInstance(value)) found += type.cast(value)
                }
            }
        }
        return found
    }
}
