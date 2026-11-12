/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomPlayerCardSpecTest {
    private val meta = "[theme]\nid = \"player-card\"\nname = \"玩家槽位示例\"\n"

    @Test
    fun existingPacksKeepTheirDefaultPlayerRows() {
        val (spec, validation) = validateThemeToml(meta + "\n[room.player]\nlocalImage = \"room/local.png\"\n")
        assertNotNull(spec)
        assertTrue(validation.warnings.isEmpty())
        assertFalse(spec.room.player.card.enabled)
        assertEquals(RoomPlayerCardSpec(), spec.room.player.card)
        assertEquals(spec.room, spec.room.sanitized())
    }

    @Test
    fun explicitCardTablesParseWithoutLegacyStructureWarningsAndRoundTrip() {
        val (spec, validation) = validateThemeToml(meta + """

            [room.player.card]
            enabled = true
            [room.player.card.avatar]
            enabled = true
            size = 56
            corner = 10
            borderWidth = 2
            borderColor = "#83C74D"
            backgroundColor = "#182116"
            tint = "#FFFFFF"
            [room.player.card.content]
            avatarImage = "room/default.png"
            [[room.player.card.content.rows]]
            [[room.player.card.content.rows.cells]]
            text = "默认称号"
            iconImage = "room/badge.png"
            contentColor = "#FFFFFF"
            backgroundColor = "#3C5A26"
            borderColor = "#83C74D"
            borderWidth = 1
            corner = 6
            [[room.player.card.content.rows.cells]]
            text = "战绩: 128胜 / 23负"
            [room.player.card.local]
            avatarImage = "room/local.png"
            rows = [{ cells = [{ text = "房主" }, { text = "战绩: 128胜 / 23负" }] }]
            [room.player.card.host]
            avatarImage = "room/host.png"
            [room.player.card.ai]
            avatarImage = "room/robot.png"
            rows = [{ cells = [{ text = "进攻专家" }, { text = "AI 难度: 困难" }] }]
            [room.player.card.spectator]
            rows = [{ cells = [{ text = "观战中" }] }]
            [room.player.card.slots."3"]
            avatarImage = "room/slot-3.png"
            rows = [{ cells = [{ text = "防守大师" }] }]
        """.trimIndent())
        assertNotNull(spec, validation.errors.joinToString())
        assertTrue(validation.warnings.isEmpty(), validation.warnings.joinToString())
        val card = spec.room.player.card
        assertTrue(card.enabled)
        assertEquals(56, card.avatar.size)
        assertEquals("#83C74D", card.avatar.borderColor)
        assertEquals("room/default.png", card.content.avatarImage)
        assertEquals(2, card.content.rows.single().cells.size)
        assertEquals("战绩: 128胜 / 23负", card.content.rows.single().cells[1].text)
        assertEquals("room/badge.png", card.content.rows.single().cells[0].iconImage)
        assertEquals("room/local.png", card.local?.avatarImage)
        assertEquals("进攻专家", card.ai?.rows?.single()?.cells?.first()?.text)
        assertEquals("观战中", card.spectator?.rows?.single()?.cells?.single()?.text)
        assertEquals("room/slot-3.png", card.slots.getValue("3").avatarImage)
        assertEquals(card, card.sanitized())
        val encoded = Toml.encodeToString(ArtThemeSpec.serializer(), spec)
        val (reparsed, reparsedValidation) = validateThemeToml(encoded)
        assertNotNull(reparsed, reparsedValidation.errors.joinToString())
        assertEquals(card, reparsed.room.player.card)
    }

    @Test
    fun contentSelectionUsesBirthPositionAndWholeRoleOverrides() {
        fun content(label: String) = RoomPlayerCardContentSpec(avatarImage = "room/$label.png")
        val normal = content("normal")
        val local = content("local")
        val host = content("host")
        val ai = content("ai")
        val spectator = content("spectator")
        val slot = content("slot-3")
        val card = RoomPlayerCardSpec(content = normal, local = local, host = host, ai = ai,
            spectator = spectator, slots = mapOf("3" to slot))
        assertEquals(slot, card.contentFor(3, isLocal = true, isHost = true, isAI = true))
        assertEquals(local, card.contentFor(2, isLocal = true, isHost = true, isAI = true))
        assertEquals(host, card.contentFor(2, isLocal = false, isHost = true, isAI = true))
        assertEquals(ai, card.contentFor(2, isLocal = false, isHost = false, isAI = true))
        assertEquals(normal, card.contentFor(2, isLocal = false, isHost = false, isAI = false))
        assertEquals(spectator, card.contentFor(3, false, false, false, isSpectator = true),
            "观战者不能命中出生位置配置")
        assertEquals(local, card.contentFor(3, true, true, true, isSpectator = true))
        assertEquals(normal, card.contentFor(0, false, false, false))
        assertEquals(normal, card.contentFor(1001, false, false, false))
        assertEquals(host, card.copy(local = null).contentFor(2, true, true, false))
        assertEquals(RoomPlayerCardContentSpec(),
            card.copy(local = RoomPlayerCardContentSpec()).contentFor(2, true, true, false),
            "显式空的角色配置应清空通用内容，而不是继承通用头像或信息")
        assertEquals(normal, card.copy(spectator = null).contentFor(-1, false, false, false, true))
    }

    @Test
    fun cardBoundsRemoveEmptyCellsAndRowsAndAreIdempotent() {
        val longText = "\u0000A\n\t\r" + "中".repeat(200)
        val cell = RoomPlayerInfoCellSpec(text = longText, iconImage = "../outside.png",
            contentColor = "wrong", backgroundColor = "wrong", borderColor = "wrong", borderWidth = 999, corner = -1)
        val content = RoomPlayerCardContentSpec(avatarImage = "../avatar.png", rows = listOf(
            RoomPlayerInfoRowSpec(),
            RoomPlayerInfoRowSpec(listOf(RoomPlayerInfoCellSpec(text = " \n\t"))),
            RoomPlayerInfoRowSpec(listOf(RoomPlayerInfoCellSpec(iconImage = "../empty.png"))),
        ) + List(5) { RoomPlayerInfoRowSpec(listOf(RoomPlayerInfoCellSpec()) + List(6) { cell }) })
        val raw = RoomPlayerCardSpec(enabled = true,
            avatar = RoomPlayerAvatarSpec(enabled = false, size = 999, corner = 999, borderWidth = -1,
                borderColor = "wrong", backgroundColor = "wrong", tint = "wrong"),
            content = content, local = content, host = content, ai = content, spectator = content,
            slots = mapOf("1" to content, "1000" to content, "0" to content, "01" to content, "1001" to content))
        val card = raw.sanitized()
        assertTrue(card.enabled)
        assertFalse(card.avatar.enabled)
        assertEquals(64, card.avatar.size)
        assertEquals(32, raw.avatar.copy(size = -1).sanitized().size)
        assertEquals(48, card.avatar.corner)
        assertEquals(0, card.avatar.borderWidth)
        assertEquals("", card.avatar.borderColor)
        assertEquals("", card.avatar.backgroundColor)
        assertEquals("", card.avatar.tint)
        assertEquals(setOf("1", "1000"), card.slots.keys)
        val cleanContents = listOf(card.content, card.local!!, card.host!!, card.ai!!, card.spectator!!) + card.slots.values
        cleanContents.forEach { clean ->
            assertEquals("", clean.avatarImage)
            assertEquals(3, clean.rows.size)
            clean.rows.forEach { row ->
                assertEquals(4, row.cells.size)
                row.cells.forEach {
                    assertEquals("A" + "中".repeat(159), it.text)
                    assertEquals("", it.iconImage)
                    assertEquals("", it.contentColor)
                    assertEquals("", it.backgroundColor)
                    assertEquals("", it.borderColor)
                    assertEquals(6, it.borderWidth)
                    assertEquals(0, it.corner)
                }
            }
        }
        assertEquals(card, card.sanitized())
        assertEquals(6, RoomPlayerAvatarSpec(borderWidth = 999).sanitized().borderWidth)
        assertEquals(48, RoomPlayerInfoCellSpec(corner = 999).sanitized().corner)
    }

    @Test
    fun cardColorsAndSlotKeysWarnAndNormalizeWithoutEnablingTheCard() {
        val (spec, validation) = validateThemeToml(meta + """

            [room.player.card.avatar]
            borderColor = "bad"
            backgroundColor = "bad"
            tint = "bad"
            [room.player.card.content]
            rows = [{ cells = [{ text = "静态信息", contentColor = "bad", backgroundColor = "bad", borderColor = "bad" }] }]
            [room.player.card.slots."0"]
            avatarImage = "room/zero.png"
            [room.player.card.slots."01"]
            avatarImage = "room/leading-zero.png"
            [room.player.card.slots."1001"]
            avatarImage = "room/too-large.png"
            [room.player.card.slots."4"]
            avatarImage = "room/four.png"
        """.trimIndent())
        assertNotNull(spec, validation.errors.joinToString())
        assertEquals(9, validation.warnings.size)
        assertFalse(spec.room.player.card.enabled)
        assertEquals(setOf("4"), spec.room.player.card.slots.keys)
        assertEquals(RoomPlayerAvatarSpec(), spec.room.player.card.avatar)
        assertEquals(RoomPlayerInfoCellSpec(text = "静态信息"), spec.room.player.card.content.rows.single().cells.single())
        assertTrue(validation.warnings.none { "旧版结构" in it })
    }

    @Test
    fun allCardAssetLocationsAreValidatedEvenWhenDisabledOrNotDisplayed() {
        val sections = listOf("content", "local", "host", "ai", "spectator", "slots.\"1\"", "slots.\"0\"")
        sections.forEach { section ->
            val assignments = listOf(
                "[room.player.card.$section]\navatarImage = \"../outside.png\"",
                "[room.player.card.$section]\nrows = [{ cells = [{ text = \"徽章\", iconImage = \"https://example.com/icon.png\" }] }]",
                "[room.player.card.$section]\nrows = [{ cells = [${List(4) { "{ text = \"ok\" }" }.joinToString()}, { iconImage = \"../fifth.png\" }] }]",
                "[room.player.card.$section]\nrows = [${List(3) { "{ cells = [{ text = \"ok\" }] }" }.joinToString()}, { cells = [{ iconImage = \"../fourth.png\" }] }]",
            )
            assignments.forEach { assignment ->
                val (spec, validation) = validateThemeToml(meta + "\n" + assignment)
                assertNull(spec, assignment)
                assertFalse(validation.isValid)
                assertTrue(validation.errors.any { "路径非法" in it }, validation.errors.joinToString())
            }
        }
    }

    @Test
    fun missingSafeAssetsRemainImportableAndStaticTextIsNotEvaluated() {
        val expression = "战绩: {wins} / " + '$' + "{losses}"
        val (spec, validation) = validateThemeToml(meta + """

            [room.player.card]
            enabled = true
            [room.player.card.content]
            avatarImage = "room/尚未提供的头像.png"
            rows = [{ cells = [{ text = "$expression", iconImage = "room/missing-icon.png" }] }]
        """.trimIndent())
        assertNotNull(spec, validation.errors.joinToString())
        assertTrue(validation.warnings.isEmpty())
        assertEquals("room/尚未提供的头像.png", spec.room.player.card.content.avatarImage)
        assertEquals(expression, spec.room.player.card.content.rows.single().cells.single().text)
    }

    @Test
    fun dangerousCardPathsAreRejectedBeforeAnyThemeDirectoryIsCreated() {
        val work = Files.createTempDirectory("rwpp-player-card-path-").toFile()
        try {
            val archive = File(work, "unsafe.rwtheme")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("theme.toml"))
                zip.write((meta + "\n[room.player.card.ai]\navatarImage = \"../outside.png\"\n").toByteArray())
                zip.closeEntry()
            }
            val root = File(work, "themes")
            val result = ThemePackInstaller.install(archive, root)
            assertFalse(result.success)
            assertTrue(result.errors.any { "room.player.card.ai.avatarImage" in it })
            assertFalse(root.exists())
        } finally {
            work.deleteRecursively()
        }
    }
}
