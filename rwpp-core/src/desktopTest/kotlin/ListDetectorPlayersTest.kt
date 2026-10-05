/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.compose.ui.text.AnnotatedString
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.ui.UI
import java.lang.reflect.Proxy
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListDetectorPlayersTest {
    @Test
    fun hidesProbeMessagesWithoutHidingPlayersDiscussingTheProbe() {
        assertTrue(ListDetectorPlayers.hideChatMessage("列表探测器", "hello"))
        assertTrue(ListDetectorPlayers.hideChatMessage("", "'列表探测器' reconnected."))
        assertTrue(ListDetectorPlayers.hideChatMessage("", "player '列表探测器' disconnected  (123456)"))
        assertTrue(ListDetectorPlayers.hideChatMessage("", "Player '列表探测器' joined the game"))
        assertFalse(ListDetectorPlayers.hideChatMessage("maker2", "列表探测器卡住了"))
        assertFalse(ListDetectorPlayers.hideChatMessage("maker2", "'列表探测器' reconnected."))
        assertFalse(ListDetectorPlayers.hideChatMessage("", "'列表探测器爱好者' reconnected."))
        assertFalse(ListDetectorPlayers.hideChatMessage("", "player 'RWProbe_03c832' disconnected (123456)"))
        assertFalse(ListDetectorPlayers.isDetector("我的列表探测器"))
    }

    @Test
    fun incomingProbeMessagesNeverEnterTheVisibleChatHistory() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings() } }) }.koin
        val previous = UI.chatMessages
        try {
            UI.chatMessages = AnnotatedString("已有聊天\n")
            UI.onReceiveChatMessage("", "'列表探测器' reconnected.", 0)
            UI.onReceiveChatMessage("", "player '列表探测器' disconnected  (123456)", 0)
            UI.onReceiveChatMessage("列表探测器", "hello", 0)
            assertEquals("已有聊天\n", UI.chatMessages.text)
        } finally {
            UI.chatMessages = previous
            stopKoin()
        }
    }

    @Test
    fun kicksAfterThirtySecondsAndKeepsTheDeadlineAcrossRefreshes() {
        val fixture = RoomFixture()
        val detector = player("列表探测器")
        fixture.players = listOf(detector)
        val guard = ListDetectorGuard()
        guard.poll(fixture.room, fixture.players, 0)
        guard.poll(fixture.room, fixture.players.toList(), 20_000)
        guard.poll(fixture.room, fixture.players, 30_000)
        assertTrue(fixture.kicked.isEmpty())
        guard.poll(fixture.room, fixture.players, 30_001)
        assertTrue(fixture.kicked.single() === detector)
        guard.poll(fixture.room, fixture.players, 31_000)
        assertEquals(1, fixture.kicked.size)
    }

    @Test
    fun shortVisitsAndNewConnectionsWithTheSameNameOrIdDoNotInheritTimeouts() {
        val fixture = RoomFixture()
        val original = player("列表探测器")
        fixture.players = listOf(original)
        val guard = ListDetectorGuard()
        guard.poll(fixture.room, fixture.players, 0)
        fixture.players = emptyList()
        guard.poll(fixture.room, fixture.players, 29_000)
        fixture.players = listOf(original)
        guard.poll(fixture.room, fixture.players, 40_000)
        assertTrue(fixture.kicked.isEmpty())
        // 正常刷新沿用同一 Player；新连接即使昵称与 connectHexId 相同也必须重新计时。
        val replacement = player("列表探测器")
        fixture.players = listOf(replacement)
        guard.poll(fixture.room, fixture.players, 60_000)
        guard.poll(fixture.room, fixture.players, 90_000)
        assertTrue(fixture.kicked.isEmpty())
        guard.poll(fixture.room, fixture.players, 90_001)
        assertTrue(fixture.kicked.single() === replacement)
    }

    @Test
    fun eachProbeHasItsOwnDeadlineAndRegularPlayersAreUntouched() {
        val fixture = RoomFixture()
        val first = player("列表探测器")
        val second = player("列表探测器")
        val regular = player("列表探测器爱好者")
        fixture.players = listOf(first, regular)
        val guard = ListDetectorGuard()
        guard.poll(fixture.room, fixture.players, 0)
        fixture.players = listOf(first, second, regular)
        guard.poll(fixture.room, fixture.players, 20_000)
        guard.poll(fixture.room, fixture.players, 30_001)
        assertTrue(fixture.kicked.single() === first)
        fixture.players = listOf(second, regular)
        guard.poll(fixture.room, fixture.players, 50_001)
        assertTrue(fixture.kicked.last() === second)
        assertEquals(2, fixture.kicked.size)
    }

    @Test
    fun onlyWaitingRoomHostsCanKickAndLocalPlayersAndHostsAreProtected() {
        val fixture = RoomFixture()
        val detector = player("列表探测器")
        fixture.players = listOf(detector, fixture.self, player("列表探测器", host = true), player("列表探测器", ai = true))
        val guard = ListDetectorGuard()
        fixture.host = false
        guard.poll(fixture.room, fixture.players, 0)
        guard.poll(fixture.room, fixture.players, 60_000)
        assertTrue(fixture.kicked.isEmpty())
        fixture.hostServer = true
        guard.poll(fixture.room, fixture.players, 60_001)
        guard.poll(fixture.room, fixture.players, 90_002)
        assertTrue(fixture.kicked.single() === detector)
        fixture.started = true
        guard.poll(fixture.room, fixture.players, 100_000)
        assertEquals(1, fixture.kicked.size)
        fixture.started = false
        fixture.singlePlayer = true
        assertFalse(ListDetectorPlayers.kickIfPresent(fixture.room, detector))
    }

    @Test
    fun manualKickRechecksPermissionNameAndTheExactCurrentPlayer() {
        val fixture = RoomFixture()
        val detector = player("列表探测器")
        fixture.players = listOf(player("列表探测器"))
        assertFalse(ListDetectorPlayers.kickIfPresent(fixture.room, detector))
        fixture.players = listOf(detector)
        fixture.host = false
        assertFalse(ListDetectorPlayers.kickIfPresent(fixture.room, detector))
        fixture.host = true
        detector.name = "普通玩家"
        assertFalse(ListDetectorPlayers.kickIfPresent(fixture.room, detector))
        detector.name = "列表探测器"
        assertTrue(ListDetectorPlayers.kickIfPresent(fixture.room, detector))
        assertTrue(fixture.kicked.single() === detector)
    }

    @Test
    fun failedOrPendingKicksRetryAtMostOnceEveryFiveSeconds() {
        val fixture = RoomFixture()
        fixture.players = listOf(player("列表探测器"))
        val guard = ListDetectorGuard()
        guard.poll(fixture.room, fixture.players, 0)
        fixture.failKick = true
        guard.poll(fixture.room, fixture.players, 30_001)
        fixture.failKick = false
        guard.poll(fixture.room, fixture.players, 35_000)
        assertTrue(fixture.kicked.isEmpty())
        guard.poll(fixture.room, fixture.players, 35_001)
        assertEquals(1, fixture.kicked.size)
    }

    private class RoomFixture {
        val self = player("列表探测器")
        var players = emptyList<Player>()
        var host = true
        var hostServer = false
        var started = false
        var singlePlayer = false
        var failKick = false
        val kicked = mutableListOf<Player>()
        val room: GameRoom = proxy(GameRoom::class.java) { method, args ->
            when (method) {
                "isHost" -> host
                "isHostServer" -> hostServer
                "isStartGame" -> started
                "isSinglePlayerGame" -> singlePlayer
                "getLocalPlayer" -> self
                "getPlayers" -> players
                "kickPlayer" -> { check(!failKick); kicked.add(args!![0] as Player); Unit }
                else -> error("Unexpected room call: $method")
            }
        }
    }

    companion object {
        private fun player(initialName: String, host: Boolean = false, ai: Boolean = false): Player {
            var name = initialName
            return proxy(Player::class.java) { method, args ->
                when (method) {
                    "getName" -> name
                    "setName" -> { name = args!![0] as String; Unit }
                    "getConnectHexId" -> "same-id"
                    "isRoomHost" -> host
                    "isAI" -> ai
                    else -> error("Unexpected player call: $method")
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args!![0]
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> type.simpleName
                    else -> call(method.name, args)
                }
            } as T
    }
}
