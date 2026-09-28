/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtThemeSpecTest {

    @Test
    fun parseColorHexAcceptsRgbAndArgb() {
        assertEquals(0xFFEEC4D8, parseColorHex("#EEC4D8"))
        assertEquals(0xFFEEC4D8, parseColorHex("#FFEEC4D8"))
        assertEquals(0x80EEC4D8, parseColorHex("#80EEC4D8"))
        assertEquals(0xFFFFFFFF, parseColorHex("#ffffff"))
        assertEquals(0xFF000000, parseColorHex(" #000000 "))
    }

    @Test
    fun parseColorHexRejectsMalformed() {
        assertNull(parseColorHex(""))
        assertNull(parseColorHex("#"))
        assertNull(parseColorHex("EEC4D8"))
        assertNull(parseColorHex("#12345"))
        assertNull(parseColorHex("#1234567"))
        assertNull(parseColorHex("#GGGGGG"))
        assertNull(parseColorHex("red"))
    }

    @Test
    fun validSpecParses() {
        val (spec, validation) = validateThemeToml(
            """
            [theme]
            id = "sakura"
            name = "樱花粉"
            author = "tester"
            version = "1.0"
            description = "粉色主题"

            [colors]
            primary = "#EEC4D8"
            onPrimary = "#FF000000"

            [menu]
            showTitleBadge = false
            """.trimIndent()
        )
        assertTrue(validation.isValid, "errors: ${validation.errors}")
        assertTrue(validation.warnings.isEmpty())
        assertNotNull(spec)
        assertEquals("sakura", spec.theme.id)
        assertEquals("樱花粉", spec.theme.name)
        assertEquals(2, spec.colors.size)
        assertFalse(spec.menu.showTitleBadge)
    }

    @Test
    fun missingMetaIsRejected() {
        // 完全缺 [theme] 段
        val (spec1, v1) = validateThemeToml("""[colors]
            |primary = "#EEC4D8"
            """.trimMargin())
        assertNull(spec1)
        assertFalse(v1.isValid)

        // id 为空
        val (spec2, v2) = validateThemeToml("""[theme]
            |id = ""
            |name = "x"
            """.trimMargin())
        assertNull(spec2)
        assertFalse(v2.isValid)

        // name 为空
        val (spec3, v3) = validateThemeToml("""[theme]
            |id = "ok"
            |name = "  "
            """.trimMargin())
        assertNull(spec3)
        assertFalse(v3.isValid)

        // id 含非法字符
        val (spec4, v4) = validateThemeToml("""[theme]
            |id = "../evil"
            |name = "x"
            """.trimMargin())
        assertNull(spec4)
        assertFalse(v4.isValid)
    }

    @Test
    fun malformedTomlIsRejected() {
        val (spec, validation) = validateThemeToml("this is not toml at all = = =")
        assertNull(spec)
        assertFalse(validation.isValid)
    }

    @Test
    fun unknownRoleAndBadHexBecomeWarnings() {
        val (spec, validation) = validateThemeToml(
            """
            [theme]
            id = "warn"
            name = "警告测试"

            [colors]
            primary = "#EEC4D8"
            primray = "#EEC4D8"
            secondary = "not-a-color"
            """.trimIndent()
        )
        assertTrue(validation.isValid, "errors: ${validation.errors}")
        assertNotNull(spec)
        assertEquals(2, validation.warnings.size)
        assertTrue(validation.warnings.any { it.contains("primray") })
        assertTrue(validation.warnings.any { it.contains("secondary") })
        // 非法/未知项仍在 colors 中，由运行时侧按 SUPPORTED_COLOR_ROLES + parseColorHex 过滤
        assertEquals(3, spec.colors.size)
    }

    @Test
    fun defaultsAreApplied() {
        val (spec, validation) = validateThemeToml("""[theme]
            |id = "minimal"
            |name = "极简"
            """.trimMargin())
        assertTrue(validation.isValid)
        assertNotNull(spec)
        assertTrue(spec.colors.isEmpty())
        assertTrue(spec.menu.showTitleBadge)
        assertEquals("", spec.theme.author)
    }

    @Test
    fun zipEntryNameSafety() {
        assertTrue(isSafeZipEntryName("theme.toml"))
        assertTrue(isSafeZipEntryName("assets/title.png"))
        assertFalse(isSafeZipEntryName(""))
        assertFalse(isSafeZipEntryName("../escape"))
        assertFalse(isSafeZipEntryName("a/../../b"))
        assertFalse(isSafeZipEntryName("/abs/path"))
        assertFalse(isSafeZipEntryName("C:\\windows"))
        assertFalse(isSafeZipEntryName("a\\b"))
    }
}
