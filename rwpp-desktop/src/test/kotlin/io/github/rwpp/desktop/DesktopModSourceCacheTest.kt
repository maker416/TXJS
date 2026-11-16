/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.e.g as EngineFileCache
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import com.corrodinggames.rts.gameFramework.l as GameEngine
import com.corrodinggames.rts.gameFramework.utility.ag as EngineZipCache
import com.corrodinggames.rts.gameFramework.utility.ah as EngineZip
import io.github.rwpp.desktop.impl.DesktopModSourceCache
import io.github.rwpp.game.mod.ModSourceChanges
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesktopModSourceCacheTest {
    @Test fun replacementReopensTheRealEngineZipAndUnchangedScanKeepsItsHandle() = fixture { root ->
        val archive = File(root, "same.rwmod")
        writeArchive(archive, "old-root", "aaaa")
        val original = assertNotNull(EngineZipCache.d(archive.path, true))
        assertEquals("aaaa", read(original))
        val manager = manager()
        val cache = cache(root)
        cache.afterScan(manager)
        cache.beforeScan(manager)
        assertSame(original, EngineZipCache.d(archive.path, true), "无变动重载不得关闭正常模组")

        try {
            preserveOldHandleOnWindows(archive, original)
            replaceArchive(archive, "new-root", "bbbb")
            assertEquals("aaaa", read(assertNotNull(EngineZipCache.d(archive.path, true))), "复现同路径替换仍读旧 ZIP")
            cache.beforeScan(manager)
            val updated = assertNotNull(EngineZipCache.d(archive.path, true))
            assertNotSame(original, updated)
            assertEquals("bbbb", read(updated), "同时重新探测 ZIP 内层根目录")
        } finally { original.a() }
    }

    @Test fun recreatingChangedRegistrationPreservesSelectionAndDropsOldNestedRoot() = fixture { root ->
        val archive = File(root, "same.rwmod")
        writeArchive(archive, "old-root", "old")
        val old = EngineMod().apply {
            e = "same-identity"
            p = archive.path
            q = archive.path + "/old-root"
            f = true
            g = true
        }
        val manager = manager(old)
        val cache = cache(root)
        cache.afterScan(manager)
        replaceArchive(archive, "new-root", "new")
        cache.beforeScan(manager)
        assertTrue(mods(manager).isEmpty(), "旧注册对象保存内层路径，必须移出后由扫描重建")
        val fresh = EngineMod().apply {
            e = "same-identity"
            p = archive.path
            q = archive.path + "/new-root"
        }
        modsField.set(manager, arrayListOf(fresh))
        cache.afterScan(manager)
        assertTrue(fresh.f, "用户禁用状态不得因重建对象丢失")
        assertTrue(fresh.g, "原版选择已加载标记必须保留")
        assertEquals(archive.path + "/new-root", fresh.q)
        cache.beforeScan(manager)
        assertSame(fresh, mods(manager).single(), "重建后建立新基线，下一次无变动扫描保留对象")
    }

    @Test fun metadataInvalidationKeepsSiblingArchivesAndMatchesBothPathSeparators() = fixture { root ->
        val archive = File(root, "same.rwmod")
        writeArchive(archive, "root", "old")
        val manager = manager(EngineMod().apply { e = "same"; p = archive.path })
        val cache = cache(root)
        cache.afterScan(manager)
        val invalid = mutableListOf<File>()
        val retained = mutableListOf<File>()
        groups.forEach { group ->
            listOf(archive.path + ".meta", archive.path + ".data", archive.path + "/mod-info.txt.data",
                archive.path + "\\music\\.meta").forEach { path ->
                invalid += cacheFile(root, group, path).apply { parentFile.mkdirs(); writeText("cached") }
            }
            retained += cacheFile(root, group, archive.path + "2/mod-info.txt.data")
                .apply { parentFile.mkdirs(); writeText("sibling") }
        }
        replaceArchive(archive, "root", "new")
        cache.beforeScan(manager)
        assertTrue(invalid.none(File::exists), "三组元信息、目录、音乐缓存均需精确失效")
        assertTrue(retained.all(File::isFile), "来源名仅前缀相似的兄弟包必须保留")
    }

    @Test fun onlyChangedSourcesLoseTheirResourceKeys() = fixture { root ->
        val archive = File(root, "same.rwmod")
        val other = File(root, "same.rwmod2")
        writeArchive(archive, "root", "old")
        var belongs: ((String) -> Boolean)? = null
        val cache = DesktopModSourceCache(resolvePath = { it },
            cacheFile = { group, path -> cacheFile(root, group, path) },
            invalidateResources = { belongs = it })
        val manager = manager(EngineMod().apply { e = "same"; p = archive.path })
        cache.afterScan(manager)
        replaceArchive(archive, "root", "new")
        cache.beforeScan(manager)
        val match = assertNotNull(belongs)
        assertTrue(match("[false,false]${archive.path}/root/body.png"))
        assertTrue(match(archive.path + "\\root\\sound.ogg"))
        assertFalse(match("[false,false]${other.path}/body.png"))
    }

    @Test fun failedInvalidationIsRetriedEvenAfterTheTrackerObservedTheReplacement() = fixture { root ->
        val archive = File(root, "same.rwmod")
        writeArchive(archive, "root", "old")
        var attempts = 0
        val cache = DesktopModSourceCache(resolvePath = { it },
            cacheFile = { group, path -> cacheFile(root, group, path) },
            invalidateResources = { if (++attempts == 1) error("fixture failure") })
        val old = EngineMod().apply { e = "same"; p = archive.path }
        val manager = manager(old)
        cache.afterScan(manager)
        replaceArchive(archive, "root", "new")
        assertFailsWith<IllegalStateException> { cache.beforeScan(manager) }
        assertSame(old, mods(manager).single(), "失效失败时保留旧注册，下次可重试")
        cache.beforeScan(manager)
        assertEquals(2, attempts)
        assertTrue(mods(manager).isEmpty())
    }

    private fun cache(root: File) = DesktopModSourceCache(resolvePath = { it },
        cacheFile = { group, path -> cacheFile(root, group, path) }, invalidateResources = {})

    private fun cacheFile(root: File, group: String, path: String) =
        File(File(root, group), EngineFileCache.a(path))

    private fun read(zip: EngineZip): String = assertNotNull(zip.i("mod-info.txt")).bufferedReader().use { it.readText() }

    private fun writeArchive(file: File, nestedRoot: String, content: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$nestedRoot/mod-info.txt").apply { time = 0 })
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }

    private fun replaceArchive(archive: File, root: String, content: String) {
        val previousTime = archive.lastModified()
        val replacement = File(archive.parentFile, "replacement.tmp")
        writeArchive(replacement, root, content)
        Files.move(replacement.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        archive.setLastModified(previousTime)
        // 导入入口会发布显式来源变动，即使替换前后文件大小和时间戳相同也不能复用 ZIP。
        ModSourceChanges.sourceChanged(archive)
    }

    private fun preserveOldHandleOnWindows(archive: File, engineZip: EngineZip) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        // Windows 宿主锁住打开的 ZIP，不能像 Android/Linux 一样替换路径后继续持有旧 inode。
        // 仅将测试句柄转向旧包快照，保留真实引擎的索引和缓存表项；生产逻辑不作此处理。
        val snapshot = File(archive.parentFile, "old-handle.zip")
        Files.copy(archive.toPath(), snapshot.toPath())
        val zipFile = EngineZip::class.java.getDeclaredField("c").apply { isAccessible = true }
        (zipFile.get(engineZip) as ZipFile).close()
        zipFile.set(engineZip, ZipFile(snapshot))
    }

    private fun manager(vararg mods: EngineMod): EngineModManager =
        (unsafe.allocateInstance(EngineModManager::class.java) as EngineModManager).apply {
            modsField.set(this, arrayListOf(*mods))
            EngineModManager::class.java.getDeclaredField("d").apply { isAccessible = true }.set(this, Any())
        }

    @Suppress("UNCHECKED_CAST")
    private fun mods(manager: EngineModManager) = modsField.get(manager) as List<EngineMod>

    private fun fixture(test: (File) -> Unit) {
        val scratch = File("build/tmp/mod-source-cache-test").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "source-").toFile().canonicalFile
        val desktop = GameEngine.aU
        GameEngine.aU = true
        try { test(root) } finally {
            val entries = synchronized(zipMap) {
                zipMap.filterKeys { it.startsWith(root.path) }.also { removed -> removed.keys.forEach(zipMap::remove) }
            }
            entries.values.forEach(EngineZip::a)
            check(root.toPath().startsWith(scratch.toPath()) && root != scratch)
            root.deleteRecursively()
            GameEngine.aU = desktop
        }
    }

    companion object {
        private val groups = listOf("mods-info", "mods-dir-search", "mods-dir-music")
        private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        private val modsField = EngineModManager::class.java.getDeclaredField("e").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val zipMap = EngineZipCache::class.java.getDeclaredField("a").apply { isAccessible = true }
            .get(null) as MutableMap<String, EngineZip>
    }
}
