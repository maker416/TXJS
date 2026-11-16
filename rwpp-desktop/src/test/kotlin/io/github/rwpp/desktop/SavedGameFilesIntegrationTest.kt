/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.y as EngineSaves
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.impl.GameImpl
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.game.map.SavedGameFile
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedGameFilesIntegrationTest {
    @Test fun savesCreatedAfterMapCacheWarmupAreImmediatelyListedAndReadable() {
        val root = Files.createTempDirectory("rwjs-save-list-").toFile()
        val context = Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) {
            _, method, args ->
            when (method.name) {
                "externalStoragePath" -> File(root, args!![0] as String).path
                "isDesktop" -> true
                else -> error("Unexpected AppContext call: ${method.name}")
            }
        } as AppContext
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single<AppContext> { context } }) }.koin
        try {
            val game = GameImpl()
            // 预热普通地图缓存，复现先开局、再在局内保存的顺序；无需创建原生窗口或 GL。
            GameImpl::class.java.getDeclaredField("_maps").apply { isAccessible = true }
                .set(game, mutableMapOf(MapType.SkirmishMap to emptyList<GameMap>()))
            assertTrue(game.getAllMaps().isEmpty())
            val file = File(root, "saves/测试进度.rwsave").apply {
                parentFile.mkdirs()
                writeText("saved fixture")
            }
            val save = game.getAllMapsByMapType(MapType.SavedGame).single()
            assertEquals(file.name, save.mapName + save.getMapSuffix())
            assertEquals("saved fixture", save.openInputStream().bufferedReader().use { it.readText() })
            assertEquals(listOf(file.name), game.getAllMaps().map { it.mapName })
            assertTrue(file.delete())
            assertTrue(game.getAllMapsByMapType(MapType.SavedGame).isEmpty())
            assertTrue(game.getAllMaps().isEmpty())
        } finally {
            stopKoin()
            root.deleteRecursively()
        }
    }

    @Test fun mapNameAndSuffixAddressTheExactFileInTheOriginalEngine() {
        val desktop = GameEngine.aU
        GameEngine.aU = true
        try {
            val saves = EngineSaves()
            listOf("Progress.rwsave", "Old progress").forEachIndexed { index, name ->
                val map = SavedGameFile(File("saves", name)).toGameMap(index)
                val engineFile = saves.a(map.mapName + map.getMapSuffix(), false)
                assertEquals(File("saves", name).canonicalFile, engineFile.canonicalFile)
            }
        } finally {
            GameEngine.aU = desktop
        }
    }
}
