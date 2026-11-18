/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.AppContext
import io.github.rwpp.desktop.impl.AppContextImpl
import io.github.rwpp.desktop.impl.inject.DesktopAssetOverlay
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.RootInfo
import io.github.rwpp.inject.runtime.InjectApi
import javassist.Loader
import net.peanuuutz.tomlkt.Toml
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.koin.core.Koin
import java.io.File
import java.io.InputStream
import kotlin.test.*

class DesktopAssetOverlayTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun directoryChangesAreLiveAndOnlyEngineAssetPathsAreRemapped() {
        val dir = File(files.root, "overlay")
        val root = dir.path + "/"
        val overlay = DesktopAssetOverlay(root)
        assertFalse(overlay.handles("units/tank.png"))
        assertTrue(dir.mkdir())
        assertTrue(overlay.handles("units/tank.png"))
        assertTrue(overlay.handles("bitmaps/icon.png"))
        for (path in listOf("builtin_mods/a.ini", "maps/a.tmx", "translations/en.properties")) {
            assertFalse(overlay.handles(path), path)
        }
        assertEquals(root + "units/tank.png", overlay.redirectResolvedPath("assets/units/tank.png"))
        assertEquals(root + "units/tank.png", overlay.redirectResolvedPath("assets\\units\\tank.png"))
        for (path in listOf(null, "mods/units/assets/tank.png", "C:\\my-assets\\tank.png", "assets2/tank.png")) {
            assertEquals(path, overlay.redirectResolvedPath(path))
        }
        assertTrue(dir.delete())
        assertFalse(overlay.handles("bitmaps/icon.png"))
    }

    @Test fun realInjectedLoaderKeepsItsReceiverAndOpensWindowsAssetNames() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val hooks = root.injectInfos.filter { ".AssetInject." in it.path }
        assertEquals(2, hooks.size)
        val library = GameLibraries.`game-lib`
        val previous = GameLibraries.includes.toSet()
        val context = object : AppContext by AppContextImpl() {
            override fun externalStoragePath(path: String) = files.root.path + "/" + path
        }
        val koin = Koin().apply { declare<AppContext>(context) }
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(library)
            library.load(File("../lib/game-lib.jar"))
            for (hook in hooks) InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName,
                hook.methodDesc, hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
            val loader = Loader(javaClass.classLoader, library.classTree.defPool)
            for (prefix in listOf("kotlin.", "org.koin.", "org.slf4j.", "io.github.rwpp.AppContext", "io.github.rwpp.inject.")) {
                loader.delegateLoadingOf(prefix)
            }
            // Keep the process-wide production path lazy isolated from every other test.
            loader.loadClass("io.github.rwpp.GlobalKt").getMethod("setAppKoin", Koin::class.java).invoke(null, koin)
            loader.loadClass("com.corrodinggames.rts.gameFramework.l").getField("aU").setBoolean(null, true)
            val resolver = loader.loadClass("com.corrodinggames.rts.gameFramework.e.d")
            val active = resolver.getConstructor(String::class.java, String::class.java).newInstance("instance-root/", "fixture")
            val resolve = resolver.getMethod("f", String::class.java)
            val open = resolver.getMethod("i", String::class.java)
            assertEquals("assets/units/icon", resolve.invoke(active, "units/icon"))
            val dir = context.externalStoragePath("resource_generated/")
            val image = File(dir, "units/icon").apply { parentFile.mkdirs(); writeText("overlay") }
            assertEquals(dir + "units/icon", resolve.invoke(active, "units/icon"))
            assertEquals("instance-root/demo", resolve.invoke(active, "/SD/demo"))
            assertEquals("mods/assets/icon", resolve.invoke(active, "mods/assets/icon"))
            for (path in listOf("units/icon", "assets/units/icon", "assets\\units\\icon")) {
                assertEquals("overlay", (open.invoke(active, path) as InputStream).bufferedReader().use { it.readText() })
            }
            assertTrue(image.delete())
            assertNull(open.invoke(active, "assets/units/icon"))
        } finally {
            koin.close()
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }
}
