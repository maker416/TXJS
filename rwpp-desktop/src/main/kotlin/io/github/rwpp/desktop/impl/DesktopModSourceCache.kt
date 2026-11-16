/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.gameFramework.e.a as EngineFiles
import com.corrodinggames.rts.gameFramework.e.g as EngineFileCache
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import com.corrodinggames.rts.gameFramework.utility.ag as EngineZipCache
import io.github.rwpp.game.mod.ModSourceTracker
import io.github.rwpp.game.mod.archiveFile
import io.github.rwpp.game.mod.archivePath
import io.github.rwpp.game.mod.pathBelongsToSource
import java.io.File
import java.util.WeakHashMap

/** 在原扫描线程上使变动的模组来源失效；扫描阶段只摘除资源 key，不销毁 GL 资源。 */
internal class DesktopModSourceCache(
    private val tracker: ModSourceTracker = ModSourceTracker(),
    private val resolvePath: (String) -> String = EngineFiles::e,
    private val cacheFile: (String, String) -> File = { group, path -> File(EngineFileCache.a(group, path, false)) },
    private val invalidateResources: ((String) -> Boolean) -> Unit = { DesktopModResourceCache.invalidateSources(it) },
) {
    private data class Selection(val disabled: Boolean, val saved: Boolean)
    private val selections = WeakHashMap<EngineModManager, Map<String, Selection>>()
    private val pendingChanges = linkedMapOf<String, MutableSet<String>>()

    fun beforeScan(manager: EngineModManager) {
        val mods = mods(manager).toList()
        val sources = sources(mods)
        val changed = tracker.changed(sources.keys.map(::File)) + pendingChanges.keys
        if (changed.isEmpty()) return
        changed.forEach { source ->
            pendingChanges.getOrPut(source) { linkedSetOf() }.addAll(sources[source].orEmpty())
        }

        // ZIP 表使用逻辑路径作 key；同一物理文件可能经多种抽象路径打开。
        changed.forEach { source ->
            pendingChanges[source].orEmpty().forEach { alias ->
                EngineZipCache.e(alias, false)
                clearMetadata(alias)
            }
        }
        val aliases = changed.flatMap { pendingChanges[it].orEmpty() }
        invalidateResources { key ->
            aliases.any { pathBelongsToSource(key, it) } ||
                changed.any { pathBelongsToSource(resolvePath(key), it) }
        }
        val restore = linkedMapOf<String, Selection>().apply { putAll(selections[manager].orEmpty()) }
        mods.forEach { mod ->
            if (paths(mod).any { source(it) in changed }) {
                mod.e?.let { restore[it] = Selection(mod.f, mod.g) }
                // 引擎入口按原有锁复制并替换列表，避免并发读者观察到原地修改。
                manager.a(mod)
            }
        }
        selections[manager] = restore
    }

    /** 必须先于本次重载的显式 ModReloadSelection，以免覆盖调用方的新选择。 */
    fun afterScan(manager: EngineModManager) {
        val mods = mods(manager)
        selections.remove(manager)?.let { restore ->
            mods.forEach { mod ->
                restore[mod.e]?.let { selection ->
                    mod.f = selection.disabled
                    mod.g = selection.saved
                }
            }
        }
        tracker.remember(sources(mods).keys.map(::File))
        pendingChanges.clear()
    }

    private fun paths(mod: EngineMod): List<String> =
        listOfNotNull(mod.p, mod.q, runCatching { mod.h() }.getOrNull())

    private fun source(path: String): String? =
        archiveFile(resolvePath(path))?.canonicalPath

    private fun sources(mods: List<EngineMod>): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        fun add(path: String) {
            val physical = resolvePath(path)
            val file = archiveFile(physical) ?: return
            val canonical = file.canonicalPath
            val aliases = result.getOrPut(canonical) { linkedSetOf() }
            listOf(path, physical, canonical).forEach { candidate ->
                archivePath(candidate)?.let(aliases::add)
            }
        }
        mods.forEach { mod -> paths(mod).forEach(::add) }
        val zipMap = zipMap()
        val zipPaths = synchronized(zipMap) { zipMap.keys.toList() }
        zipPaths.forEach(::add)
        return result
    }

    private fun clearMetadata(archive: String) {
        CACHE_GROUPS.forEach { group ->
            val base = cacheFile(group, archive)
            val prefixes = listOf(cacheFile(group, "$archive/").name, cacheFile(group, "$archive\\").name)
            base.parentFile?.listFiles()?.forEach { file ->
                if (file.name == "${base.name}.meta" || file.name == "${base.name}.data" ||
                    prefixes.any(file.name::startsWith)) {
                    check(!file.isFile || file.delete() || !file.exists()) { "Cannot invalidate mod cache: $file" }
                }
            }
        }
    }

    companion object {
        val instance = DesktopModSourceCache()
        private val modsField = EngineModManager::class.java.getDeclaredField("e").apply { isAccessible = true }
        private val zipField = EngineZipCache::class.java.getDeclaredField("a").apply { isAccessible = true }
        private val CACHE_GROUPS = listOf("mods-info", "mods-dir-search", "mods-dir-music")

        @Suppress("UNCHECKED_CAST")
        private fun mods(manager: EngineModManager): List<EngineMod> = modsField.get(manager) as List<EngineMod>

        @Suppress("UNCHECKED_CAST")
        private fun zipMap(): MutableMap<String, Any> = zipField.get(null) as MutableMap<String, Any>
    }
}
