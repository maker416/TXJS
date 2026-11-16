/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import com.corrodinggames.rts.gameFramework.e.a as FileLoader
import com.corrodinggames.rts.gameFramework.e.g as DiskCache
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import com.corrodinggames.rts.gameFramework.utility.aj as ZipLoader
import io.github.rwpp.game.mod.ModSourceTracker
import io.github.rwpp.game.mod.archiveFile
import io.github.rwpp.game.mod.archivePath
import io.github.rwpp.game.mod.pathBelongsToSource
import java.io.File

/** 扫描前刷新被替换的来源；只在引擎原有扫描线程调用，不在文件导入线程操作引擎。 */
internal class AndroidModSourceCache(
    private val resolve: (String) -> String = FileLoader::e,
    private val cacheFile: (String, String) -> File = { group, path ->
        File(diskCachePath.invoke(null, group, path, false) as String)
    },
    private val invalidateResources: ((String) -> Boolean) -> Unit = AndroidModResourceCache::invalidateSources,
) {
    private val tracker = ModSourceTracker()
    private val selection = mutableMapOf<String, Pair<Boolean, Boolean>>()
    private val pendingChanges = linkedMapOf<String, MutableSet<String>>()

    fun beforeScan(manager: EngineModManager) {
        val mods = mods(manager)
        val zipKeys = synchronized(zipCache) { zipCache.keys.toList() }
        val aliases = (mods.flatMap(::paths) + zipKeys).mapNotNull(::archivePath).distinct()
        val sources = aliases.associateWith(::source)
        val changed = tracker.changed(sources.values) + pendingChanges.keys
        if (changed.isEmpty()) return
        changed.forEach { path ->
            pendingChanges.getOrPut(path) { linkedSetOf() }.addAll(
                sources.filterValues { it.canonicalPath == path }.keys + path
            )
        }

        // 原引擎重载只清 SAF，不清 ZIP。使用其移除/延迟关闭入口，避免关闭仍在读取的流。
        val changedAliases = pendingChanges.values.flatten().toSet()
        changedAliases.forEach { alias ->
            ZipLoader.m(alias)
            clearDiskCache(alias)
        }
        invalidateResources { key -> (changedAliases + changed).any { pathBelongsToSource(key, it) } }
        mods.forEach { mod ->
            val replaced = paths(mod).mapNotNull(::archivePath).any { source(it).canonicalPath in changed }
            if (replaced) {
                selection[mod.e] = mod.f to mod.g
                // 沿用引擎自己的锁与复制列表入口，扫描读者不会看到原地删除。
                manager.a(mod)
            }
        }
        // 旧对象还保存 ZIP 内层根目录和标题；仅关 ZipFile 不能让新包重新探测根目录。
        runCatching { io.github.rwpp.logger.info("[MODCACHE] refreshed changed archives: {}", changed.map { File(it).name }) }
    }

    fun afterScan(manager: EngineModManager) {
        val mods = mods(manager)
        mods.forEach { mod ->
            selection[mod.e]?.let { (disabled, settingsLoaded) ->
                mod.f = disabled
                mod.g = settingsLoaded
            }
        }
        selection.clear()
        tracker.remember(mods.flatMap(::paths).mapNotNull(::archivePath).map(::source))
        pendingChanges.clear()
    }

    private fun source(path: String): File = archiveFile(resolve(path)) ?: File(resolve(path))

    private fun paths(mod: EngineMod): List<String> =
        if (mod.x) emptyList() else listOfNotNull(mod.o, mod.n, mod.e())

    private fun clearDiskCache(archive: String) {
        for (group in listOf("mods-info", "mods-dir-search", "mods-dir-music")) {
            val base = cacheFile(group, archive)
            val prefixes = listOf(cacheFile(group, "$archive/").name, cacheFile(group, "$archive\\").name)
            base.parentFile?.listFiles()?.forEach { entry ->
                if (entry.isFile && (entry.name == base.name + ".meta" || entry.name == base.name + ".data" ||
                        prefixes.any(entry.name::startsWith))) {
                    check(entry.delete() || !entry.exists()) { "Cannot invalidate mod cache: $entry" }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mods(manager: EngineModManager): List<EngineMod> = manager.e as List<EngineMod>

    companion object {
        val instance = AndroidModSourceCache()
        private val diskCachePath by lazy {
            DiskCache::class.java.getDeclaredMethod("a", String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        }
        @Suppress("UNCHECKED_CAST")
        private val zipCache: MutableMap<String, Any> by lazy {
            ZipLoader::class.java.getDeclaredField("a").apply { isAccessible = true }
                .get(null) as MutableMap<String, Any>
        }
    }
}
