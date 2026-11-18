/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.android.impl.inject.ModManagerInject
import io.github.rwpp.logger
import org.slf4j.LoggerFactory
import io.github.rwpp.game.mod.ContentModSource
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AndroidExpansionSourcesTest {
    @Test fun registersTheRealNativeModFromTheIndependentApplicationDirectory() {
        logger = LoggerFactory.getLogger("AndroidExpansionSourcesTest")
        val root = Files.createTempDirectory("rwjs-android-expansion-").toFile()
        val source = File(root, "expansions/game/hash/mods/sample.rwmod").apply { parentFile.mkdirs() }
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt")); zip.write("[mod]\ntitle: Native expansion fixture\ndescription: Independent source\n".toByteArray()); zip.closeEntry()
        }
        val singleton = GameEngine::class.java.getDeclaredField("ak").apply { isAccessible = true }
        val previous = singleton.get(null)
        val applicationContext = com.corrodinggames.rts.appFramework.d::class.java.getDeclaredField("b").apply { isAccessible = true }
        val previousContext = applicationContext.get(null)
        val context: Application = RuntimeEnvironment.getApplication()
        com.corrodinggames.rts.appFramework.d.a(context)
        val engine = allocate(Class.forName("com.corrodinggames.rts.game.i").asSubclass(GameEngine::class.java))
        engine.al = context
        engine.am = context
        val manager = allocate(EngineModManager::class.java)
        val ordinary = EngineMod().apply {
            c = "ordinary.rwmod"; d = "ordinary"; q = "Ordinary"; e = "ordinary"
            n = File(root, "ordinary.rwmod").path; o = n; f = false
        }
        manager.e = arrayListOf(ordinary)
        EngineModManager::class.java.getDeclaredField("d").apply { isAccessible = true }.set(manager, Any())
        engine.bN = allocate(SettingsEngine::class.java)
        engine.bW = manager
        singleton.set(null, engine)
        ContentProfiles.configureRoot(File(root, "expansions"))
        ContentProfiles.stage(ContentProfile.Expansion("game", "a".repeat(64), listOf(ContentModSource("sample", source))))
        try {
            with(ModManagerInject) {
                manager.invalidateChangedModSources()
                manager.applyReloadSelectionAfterScan()
            }
            @Suppress("UNCHECKED_CAST") val mods = manager.e as List<EngineMod>
            val registered = mods.single { it !== ordinary }
            assertEquals(source.canonicalPath, File(registered.n).canonicalPath)
            assertEquals("Native expansion fixture", registered.q)
            assertTrue(registered.k, "External sources must survive native rescans")
            assertFalse(registered.f)
            assertTrue(ordinary.f)
            assertFalse(File(root, "units/sample.rwmod").exists())
        } finally {
            ContentProfiles.finishNormal()
            if (singleton.get(null) === engine) singleton.set(null, previous)
            applicationContext.set(null, previousContext)
            root.deleteRecursively()
        }
    }

    private fun <T> allocate(type: Class<T>): T = type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    companion object {
        private val unsafeClass = Class.forName("sun.misc.Unsafe")
        private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    }
}
