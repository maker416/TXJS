/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 主题美术包文本覆盖（[i18nOverrideTable]）的行为锚定：
 * 覆盖命中优先、缺键回落内置、清除后恢复，全程不得因覆盖表缺键抛异常。
 */
class I18nOverrideTest {

    @BeforeTest
    fun setup() {
        i18nTable = Toml.parseToTomlTable(
            """
            [menu]
            singlePlayerGame = "单人游戏"
            multiplayer = " multiplayer "
            """.trimIndent()
        )
        setI18nOverride(null)
    }

    @AfterTest
    fun tearDown() {
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun overrideWinsWhenPresent() {
        setI18nOverride(
            Toml.parseToTomlTable(
                """
                [menu]
                singlePlayerGame = "孤胆征程"
                """.trimIndent()
            )
        )
        assertEquals("孤胆征程", readI18n("menu.singlePlayerGame"))
    }

    @Test
    fun missingKeyFallsBackToBuiltIn() {
        setI18nOverride(
            Toml.parseToTomlTable(
                """
                [menu]
                singlePlayerGame = "孤胆征程"
                """.trimIndent()
            )
        )
        // 覆盖表没有 menu.multiplayer，必须回落内置值
        assertEquals(" multiplayer ", readI18n("menu.multiplayer"))
    }

    @Test
    fun partialPathInOverrideDoesNotBreakFallback() {
        // 覆盖表里 menu 是标量而非表：导航失败必须回落，不得抛异常
        setI18nOverride(
            Toml.parseToTomlTable(
                """
                menu = "oops"
                """.trimIndent()
            )
        )
        assertEquals("单人游戏", readI18n("menu.singlePlayerGame"))
    }

    @Test
    fun clearingOverrideRestoresBuiltIn() {
        setI18nOverride(
            Toml.parseToTomlTable(
                """
                [menu]
                singlePlayerGame = "孤胆征程"
                """.trimIndent()
            )
        )
        assertEquals("孤胆征程", readI18n("menu.singlePlayerGame"))
        setI18nOverride(null)
        assertEquals("单人游戏", readI18n("menu.singlePlayerGame"))
    }

    @Test
    fun emptyOverrideTableKeepsBuiltIn() {
        setI18nOverride(TomlTable())
        assertEquals("单人游戏", readI18n("menu.singlePlayerGame"))
    }
}
