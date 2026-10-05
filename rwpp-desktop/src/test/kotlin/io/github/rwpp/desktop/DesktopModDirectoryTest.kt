/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.i.a
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.impl.AppContextImpl
import io.github.rwpp.desktop.impl.inject.CustomUnitLoadInject
import io.github.rwpp.desktop.impl.inject.ModManagerInject
import io.github.rwpp.modDir
import io.github.rwpp.internalModDir
import org.koin.core.Koin
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModDirectoryTest {
    @Test
    fun engineScansImportDirectoryAndLegacyDirectoryWithoutRecursion() {
        val scratch = File("build/tmp/mod-directory-test").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "scan-").toFile().canonicalFile
        val context = object : AppContext by AppContextImpl() {
            override fun externalStoragePath(path: String): String = File(root, path).path
        }
        appKoin = Koin().apply { declare<AppContext>(context) }
        try {
            val launcher = File(root, "mods/units").apply { mkdirs() }
            val legacy = File(root, "units").apply { mkdirs() }
            assertEquals(launcher.canonicalFile, File(modDir).canonicalFile)
            assertEquals(modDir, CustomUnitLoadInject.customModDirectory())
            assertEquals(modDir, internalModDir)

            // 绕过原版构造器（依赖已初始化游戏核心）；仅记录真实扫描接口的调用。
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null) as Unsafe
            }
            val recorder = (unsafe.allocateInstance(RecordingManager::class.java) as RecordingManager)
                .apply { scans = mutableListOf() }
            with(ModManagerInject) {
                recorder.scanLegacyModsAfterLauncherDirectory(modDir, enabled = false, builtIn = false)
                recorder.scanLegacyModsAfterLauncherDirectory(legacy.path, enabled = false, builtIn = false)
                recorder.scanLegacyModsAfterLauncherDirectory("builtin_mods", enabled = false, builtIn = true)
            }
            assertEquals(listOf(Scan(legacy.absolutePath, false, false)), recorder.scans)
        } finally {
            appKoin.close()
            check(root.toPath().startsWith(scratch.toPath()) && root != scratch)
            root.deleteRecursively()
        }
    }

    private data class Scan(val path: String, val enabled: Boolean, val builtIn: Boolean)

    private class RecordingManager : a() {
        lateinit var scans: MutableList<Scan>
        override fun a(path: String, enabled: Boolean, builtIn: Boolean) {
            scans += Scan(path, enabled, builtIn)
        }
    }
}
