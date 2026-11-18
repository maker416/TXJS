/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import io.github.rwpp.desktop.impl.inject.ModManagerInject
import io.github.rwpp.logger
import org.slf4j.LoggerFactory
import io.github.rwpp.game.mod.ContentModSource
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesktopExpansionSourcesTest {
    @Test fun registersTheRealNativeModFromItsIndependentArchiveBeforeApplyingSelection() {
        logger = LoggerFactory.getLogger("DesktopExpansionSourcesTest")
        val root = Files.createTempDirectory("rwjs-desktop-expansion-").toFile()
        val source = File(root, "expansions/game/hash/mods/sample.rwmod").apply { parentFile.mkdirs() }
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt")); zip.write("[mod]\ntitle: Native expansion fixture\ndescription: Independent source\n".toByteArray()); zip.closeEntry()
        }
        val singleton = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previous = singleton.get(null)
        val previousDesktop = GameEngine.aU
        val engine = allocate(Class.forName("com.corrodinggames.rts.game.i").asSubclass(GameEngine::class.java))
        val manager = allocate(EngineModManager::class.java)
        val modsField = EngineModManager::class.java.getDeclaredField("e").apply { isAccessible = true }
        val ordinary = EngineMod().apply {
            c = "ordinary.rwmod"; d = "ordinary"; s = "Ordinary"; e = "ordinary"
            p = File(root, "ordinary.rwmod").path; q = p; f = false
        }
        modsField.set(manager, arrayListOf(ordinary))
        EngineModManager::class.java.getDeclaredField("d").apply { isAccessible = true }.set(manager, Any())
        engine.bQ = allocate(SettingsEngine::class.java)
        engine.bZ = manager
        singleton.set(null, engine)
        GameEngine.aU = true
        ContentProfiles.configureRoot(File(root, "expansions"))
        ContentProfiles.stage(ContentProfile.Expansion("game", "f".repeat(64), listOf(ContentModSource("sample", source))))
        try {
            with(ModManagerInject) {
                manager.invalidateChangedSourcesBeforeScan()
                manager.applyReloadSelectionAfterScan()
            }
            @Suppress("UNCHECKED_CAST") val mods = modsField.get(manager) as List<EngineMod>
            val registered = mods.single { it !== ordinary }
            assertEquals(source.canonicalPath, File(registered.p).canonicalPath)
            assertEquals("Native expansion fixture", registered.s)
            assertTrue(registered.m, "External sources must survive native rescans")
            assertFalse(registered.f)
            assertTrue(ordinary.f)
            assertFalse(File(root, "mods/units/sample.rwmod").exists())
        } finally {
            ContentProfiles.finishNormal()
            if (singleton.get(null) === engine) singleton.set(null, previous)
            GameEngine.aU = previousDesktop
            root.deleteRecursively()
        }
    }

    private fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
    companion object {
        private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    }
}
