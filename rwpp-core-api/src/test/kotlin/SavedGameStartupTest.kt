/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.*

class SavedGameStartupTest {
    @Test
    fun synchronousPlatformInitializesBeforeChoosingSaveOnTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val events = mutableListOf<String>()
        var selected: GameMap? = null
        val save = proxy<GameMap> { _, method, _ ->
            when (method.name) {
                "getMapType" -> MapType.SavedGame
                else -> error("Unexpected map call: ${method.name}")
            }
        }
        val room = proxy<GameRoom> { _, method, args ->
            assertSame(caller, Thread.currentThread())
            when (method.name) {
                "setSelectedMap" -> { events += "select"; selected = args!![0] as GameMap; null }
                "startGame" -> { assertSame(save, selected); events += "start"; null }
                else -> error("Unexpected room call: ${method.name}")
            }
        }
        val game = proxy<Game> { instance, method, args ->
            assertSame(caller, Thread.currentThread())
            when (method.name) {
                "loadSavedGame" -> InvocationHandler.invokeDefault(instance, method, *args!!)
                "hostNewSinglePlayer" -> {
                    assertEquals(false, args!![0])
                    selected = null
                    events += "initialize"
                    null
                }
                "getGameRoom" -> room
                else -> error("Unexpected game call: ${method.name}")
            }
        }
        game.loadSavedGame(save)
        assertEquals(listOf("initialize", "select", "start"), events)
    }

    private inline fun <reified T> proxy(handler: InvocationHandler): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), handler) as T
}
