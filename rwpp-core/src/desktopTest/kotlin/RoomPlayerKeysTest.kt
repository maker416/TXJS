/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.game.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RoomPlayerKeysTest {
    @Test
    fun reorderRenameAndTeamChangesKeepTheExistingPlayerKeys() {
        val store = RoomPlayerKeyStore()
        val first = player("甲")
        val second = player("乙")
        val original = store.keysFor(listOf(first, second))
        first.name = "新昵称"
        first.spawnPoint = 8
        first.team = 4
        assertEquals(original.reversed(), store.keysFor(listOf(second, first)))
    }

    @Test
    fun equalPlayersWithDuplicateNamesAndConnectionIdsStillGetDistinctKeys() {
        val store = RoomPlayerKeyStore()
        val first = player("相同昵称")
        val second = player("相同昵称")
        // 模拟引擎代理按内容 equals/hashCode；列表 key 仍只依赖对象引用。
        assertEquals(first, second)
        assertEquals(first.connectHexId, second.connectHexId)
        val keys = store.keysFor(listOf(first, second))
        assertNotEquals(keys[0], keys[1])
        assertEquals(keys.reversed(), store.keysFor(listOf(second, first)))
    }

    @Test
    fun leavingReleasesTheIdentityAndRejoiningAllocatesAFreshKey() {
        val store = RoomPlayerKeyStore()
        val first = player("甲")
        val staying = player("乙")
        val before = store.keysFor(listOf(first, staying))
        assertEquals(listOf(before[1]), store.keysFor(listOf(staying)))
        val after = store.keysFor(listOf(first, staying))
        assertNotEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        store.keysFor(emptyList())
        val next = store.keysFor(listOf(first, staying))
        assertNotEquals(after[0], next[0])
        assertNotEquals(after[1], next[1])
    }

    @Suppress("UNCHECKED_CAST")
    private fun player(initialName: String): Player {
        var name = initialName
        var spawnPoint = 0
        var team = 0
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "equals" -> args!![0] is Player
                "hashCode" -> 1
                "toString" -> "Player($name)"
                "getName" -> name
                "setName" -> { name = args!![0] as String; null }
                "getSpawnPoint" -> spawnPoint
                "setSpawnPoint" -> { spawnPoint = args!![0] as Int; null }
                "getTeam" -> team
                "setTeam" -> { team = args!![0] as Int; null }
                "getConnectHexId" -> "duplicate-connection-id"
                else -> error("Unexpected player access: ${method.name}")
            }
        } as Player
    }
}
