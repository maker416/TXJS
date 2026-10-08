/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.config.Config
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Properties
import kotlin.reflect.KClass
import kotlin.test.*

class NetworkJoinHistoryTest {
    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("NetworkJoinHistoryTest")
    }

    @Test
    fun launcherStorageSurvivesReopeningWithoutAccessingEngineFields() {
        val persisted = Properties()
        val config = TestConfigIO(persisted)
        assertEquals(emptyList(), NetworkJoinHistory.load(config))
        assertEquals(listOf("R1234"), NetworkJoinHistory.record(config, " R1234 "))
        assertEquals(
            listOf("example.com:5123", "R1234"),
            NetworkJoinHistory.record(config, "example.com:5123"),
        )
        assertEquals(listOf("example.com:5123", "R1234"), NetworkJoinHistory.load(TestConfigIO(persisted)))
        assertEquals(0, config.engineAccesses)
    }

    @Test
    fun rejoiningMovesAddressToFrontAndHistoryIsBounded() {
        val config = TestConfigIO()
        repeat(15) { NetworkJoinHistory.record(config, "R$it") }
        val expected = (14 downTo 3).map { "R$it" }
        assertEquals(expected, NetworkJoinHistory.load(config))
        assertEquals(listOf("R7") + expected.filter { it != "R7" }, NetworkJoinHistory.record(config, " R7 "))
        assertEquals(NetworkJoinHistory.load(config), NetworkJoinHistory.record(config, "  "))
    }

    @Test
    fun malformedHistoryIsTrimmedDeduplicatedAndLimited() {
        val config = TestConfigIO()
        config.saveSingleConfig(
            "io.github.rwpp.networkJoinHistory", "addresses",
            " R1 \n\nR1\n" + (2..20).joinToString("\n") { " R$it " },
        )
        assertEquals((1..12).map { "R$it" }, NetworkJoinHistory.load(config))
    }

    @Test
    fun historyWriteFailureDoesNotBlockTheJoinAction() {
        val config = TestConfigIO()
        NetworkJoinHistory.record(config, "previous")
        config.failSaves = true
        var addressToJoin: String? = null
        val history = NetworkJoinHistory.record(config, "next")
        addressToJoin = "next"
        assertEquals(listOf("next", "previous"), history)
        assertEquals("next", addressToJoin)
        assertEquals(listOf("previous"), NetworkJoinHistory.load(config))
    }

    private class TestConfigIO(private val persisted: Properties = Properties()) : ConfigIO {
        var failSaves = false
        var engineAccesses = 0

        override fun saveSingleConfig(group: String, key: String, value: Any?) {
            if (failSaves) throw IOException("fixture disk write failure")
            persisted.setProperty("$group/$key", value.toString())
        }
        override fun readSingleConfig(group: String, key: String): String? =
            persisted.getProperty("$group/$key")

        override fun <T> getGameConfig(name: String): T {
            engineAccesses++
            throw NoSuchFieldException(name)
        }
        override fun setGameConfig(name: String, value: Any?) {
            engineAccesses++
            throw NoSuchFieldException(name)
        }
        override fun saveConfig(config: Config) = error("Unexpected whole-config write")
        override fun <T : Config> readConfig(clazz: KClass<T>): T? = error("Unexpected whole-config read")
        override fun <T : Config> deleteConfig(clazz: KClass<T>) = error("Unexpected config deletion")
    }
}
