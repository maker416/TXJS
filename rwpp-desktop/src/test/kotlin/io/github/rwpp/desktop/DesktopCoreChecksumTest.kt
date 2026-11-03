/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.game.b.b as EngineMap
import com.corrodinggames.rts.game.b.e as MapLayer
import com.corrodinggames.rts.game.b.g as MapTile
import com.corrodinggames.rts.game.units.e.b as BuilderUnit
import com.corrodinggames.rts.game.units.e.i as HovercraftUnit
import javassist.ClassClassPath
import javassist.ClassPool
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCoreChecksumTest {
    @Test
    fun menuWaterChangesRealUnitChecksumsWithoutChangingRegistrationChecksum() {
        val singleton = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previousEngine = singleton.get(null)
        val engine = allocate(Class.forName("com.corrodinggames.rts.game.i").asSubclass(GameEngine::class.java))
        val cached = engine.javaClass.getDeclaredField("d").apply { isAccessible = true }
        cached.setInt(engine, 678359601)
        singleton.set(null, engine)
        try {
            val game = allocate(gameFixtureClass)
            val builder = BuilderUnit(true)
            val hovercraft = HovercraftUnit(true)
            val dryBuilder = builder.bw()
            val dryHovercraft = hovercraft.bw()
            assertEquals(291396218, dryBuilder)
            assertEquals(-1386332285, dryHovercraft)
            assertEquals(678359601, game.getUnitsChecksum())

            engine.bL = waterMap()
            assertTrue(builder.cK())
            assertTrue(hovercraft.cK())
            assertEquals(266669688, builder.bw())
            assertEquals(-1357435015, hovercraft.bw())
            assertEquals(678359601, game.getUnitsChecksum(), "Menu terrain must not affect Direct admission")

            game.refreshHandshakeChecksumCache()
            assertEquals(678359601, engine.z(), "Reload must not overwrite the original packet 110 checksum")
            assertEquals(678359601, cached.getInt(engine))
            engine.bL = null
            assertEquals(678359601, game.getUnitsChecksum())
        } finally {
            if (singleton.get(null) === engine) singleton.set(null, previousEngine)
        }
    }

    /** Real one-tile map: the original terrain query returns water at template position (0, 0). */
    private fun waterMap(): EngineMap {
        val tile = allocate(MapTile::class.java).apply { e = true }
        val map = allocate(EngineMap::class.java).apply {
            r = 1f
            s = 1f
            C = 1
            D = 1
            B = arrayOf(tile)
        }
        map.u = allocate(MapLayer::class.java).apply {
            i = map
            n = 1
            o = 1
            q = shortArrayOf(0)
        }
        return map
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }

        // Only the two inherited checksum methods are called; no platform services or native window are created.
        private val gameFixtureClass: Class<out AbstractGame> by lazy {
            val pool = ClassPool(true).apply { insertClassPath(ClassClassPath(AbstractGame::class.java)) }
            val fixture = pool.makeClass("io.github.rwpp.desktop.CoreChecksumGameFixture")
            fixture.superclass = pool.get(AbstractGame::class.java.name)
            try {
                fixture.toClass(AbstractGame::class.java).asSubclass(AbstractGame::class.java)
            } finally {
                fixture.detach()
            }
        }

        private fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
    }
}
