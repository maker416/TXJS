/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import com.corrodinggames.rts.gameFramework.utility.aj as ZipLoader
import io.github.rwpp.android.impl.AndroidModSourceCache
import io.github.rwpp.game.mod.ModSourceChanges
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AndroidModSourceCacheTest {
    @Test fun replacementReopensRealZipAndRebuildsRegistrationBeforeLoading() = fixture { root ->
        val file = File(root, "same.rwmod")
        writeZip(file, "old-root", "broken")
        val manager = manager(mod(file, "old-root", disabled = true))
        val cache = cache(root)
        cache.afterScan(manager)
        assertEquals("broken", read(file))
        val oldHandle = assertNotNull(zipCache[file.path])
        emulateUnlinkedHandleOnWindows(file, oldHandle)

        val replacement = File(root, "replacement.tmp")
        writeZip(replacement, "new-root", "repaired")
        ModSourceChanges.sourceChanged(file)
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("broken", read(file), "Reproduce the engine's stale handle before invalidation")

        cache.beforeScan(manager)
        assertTrue(manager.e.isEmpty(), "A replaced mod must re-detect its inner source folder and title")
        assertFalse(zipCache.containsKey(file.path))
        assertEquals("repaired", read(file))
        assertNotSame(oldHandle, zipCache[file.path])
        val refreshed = mod(file, "new-root", disabled = false)
        manager.e.add(refreshed)
        cache.afterScan(manager)
        assertTrue(refreshed.f, "Preserve the disabled selection before the explicit reload selection applies")
        assertTrue(refreshed.g)
        assertEquals(file.path + "/new-root", refreshed.n)
        (oldHandle.javaClass.getDeclaredField("c").apply { isAccessible = true }.get(oldHandle) as ZipFile).close()
    }

    @Test fun unchangedArchiveKeepsItsHandleAndRegistration() = fixture { root ->
        val file = File(root, "same.rwmod")
        writeZip(file, "old-root", "unchanged")
        val original = mod(file, "old-root", disabled = false)
        val manager = manager(original)
        val cache = cache(root)
        cache.afterScan(manager)
        read(file)
        val handle = zipCache.getValue(file.path)
        cache.beforeScan(manager)
        assertSame(original, manager.e.single())
        assertSame(handle, zipCache.getValue(file.path))
    }

    @Test fun interruptedScanPreservesThePendingDisabledSelectionForRetry() = fixture { root ->
        val file = File(root, "same.rwmod").apply { writeText("old") }
        val manager = manager(mod(file, "old-root", disabled = true))
        var invalidations = 0
        val cache = cache(root) { invalidations++ }
        cache.afterScan(manager)
        ModSourceChanges.sourceChanged(file)
        file.writeText("new")
        cache.beforeScan(manager)
        assertTrue(manager.e.isEmpty())
        // 模拟原扫描抛错，没有执行 afterScan；下一次重试仍须保留旧选择和待清理来源。
        cache.beforeScan(manager)
        assertEquals(2, invalidations)
        val refreshed = mod(file, "new-root", disabled = false).apply { g = false }
        manager.e.add(refreshed)
        cache.afterScan(manager)
        assertTrue(refreshed.f)
        assertTrue(refreshed.g)
    }

    @Test fun replacementWithSameMetadataClearsOnlyItsDiskAndResourceKeys() = fixture { root ->
        val file = File(root, "same.rwmod").apply { writeText("old") }
        val manager = manager(mod(file, "old-root", disabled = false))
        var belongs: ((String) -> Boolean)? = null
        val cache = cache(root) { belongs = it }
        cache.afterScan(manager)
        val oldTime = file.lastModified()
        for (group in groups) {
            diskFile(root, group, file.path + "/old-root/mod-info.txt.meta").apply { assertNotNull(parentFile).mkdirs(); writeText("old") }
            diskFile(root, group, file.path + ".meta").writeText("old")
            diskFile(root, group, file.path + "2/mod-info.txt.meta").writeText("unrelated")
        }
        ModSourceChanges.sourceChanged(file)
        file.writeText("new")
        assertTrue(file.setLastModified(oldTime))
        cache.beforeScan(manager)
        for (group in groups) {
            assertFalse(diskFile(root, group, file.path + "/old-root/mod-info.txt.meta").exists())
            assertFalse(diskFile(root, group, file.path + ".meta").exists())
            assertTrue(diskFile(root, group, file.path + "2/mod-info.txt.meta").exists())
        }
        assertTrue(assertNotNull(belongs)("false:lazy:" + file.path + "/old-root/body.png"))
        assertFalse(assertNotNull(belongs)(file.path + "2/body.png"))
    }

    private fun cache(root: File, resources: ((String) -> Boolean) -> Unit = {}) = AndroidModSourceCache(
        resolve = { it }, cacheFile = { group, path -> diskFile(root, group, path) }, invalidateResources = resources,
    )

    private fun diskFile(root: File, group: String, path: String): File =
        File(File(root, "$group.cachedata"), path.replace("%", "%%").replace("/", "%47").replace("\\", "%92").replace(":", "%58"))

    private fun mod(file: File, folder: String, disabled: Boolean) = EngineMod().apply {
        e = "stable-id"
        o = file.path
        n = file.path + "/" + folder
        w = true // Return the supplied physical path without needing a running Android engine.
        f = disabled
        g = true
        q = "old title"
    }

    private fun manager(mod: EngineMod): EngineModManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return (unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, EngineModManager::class.java) as EngineModManager)
            .apply {
                e = arrayListOf(mod)
                EngineModManager::class.java.getDeclaredField("d").apply { isAccessible = true }.set(this, Any())
            }
    }

    private fun writeZip(file: File, folder: String, title: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$folder/mod-info.txt"))
            zip.write(title.toByteArray())
            zip.closeEntry()
        }
    }

    private fun read(file: File): String = assertNotNull(ZipLoader().d(file.path + "/mod-info.txt"))
        .bufferedReader().use { it.readText() }

    private fun emulateUnlinkedHandleOnWindows(file: File, handle: Any) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        // Windows 禁止替换打开的 ZIP。用旧包副本承接底层句柄，模拟 Android/Linux unlink 后
        // 旧描述符仍读旧 inode；引擎的 ZIP 对象、路径映射和目录索引完全保留。
        val snapshot = File(file.parentFile, "old-handle.zip")
        file.copyTo(snapshot)
        val zipField = handle.javaClass.getDeclaredField("c").apply { isAccessible = true }
        (zipField.get(handle) as ZipFile).close()
        zipField.set(handle, ZipFile(snapshot))
    }

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("rwjs-android-mod-source-").toFile().canonicalFile
        try { test(root) } finally {
            synchronized(zipCache) {
                zipCache.keys.filter { it.startsWith(root.path) }.forEach { key ->
                    val handle = zipCache.remove(key)!!
                    (handle.javaClass.getDeclaredField("c").apply { isAccessible = true }.get(handle) as ZipFile).close()
                }
            }
            check(root.toPath().startsWith(File(assertNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile.toPath()))
            root.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val zipCache get() = ZipLoader::class.java.getDeclaredField("a").apply { isAccessible = true }
        .get(null) as MutableMap<String, Any>
    private val groups = listOf("mods-info", "mods-dir-search", "mods-dir-music")
}
