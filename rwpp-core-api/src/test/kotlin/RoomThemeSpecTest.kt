/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

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

class RoomThemeSpecTest {
    private val meta = "[theme]\nid = \"room-test\"\nname = \"Room Test\"\n"

    @Test
    fun oldPackagesHaveDefaultRoomAppearance() {
        val (spec, result) = validateThemeToml(meta)
        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty())
        assertNotNull(spec)
        assertEquals(RoomThemeSpec(), spec.room)
        assertEquals(spec.room, spec.room.sanitized())
    }

    @Test
    fun appearanceAssetsAndColorsRemainSupported() {
        val (spec, result) = validateThemeToml(meta + """

            [room.background]
            image = "room/wallpaper.png"
            scale = "fit"
            alpha = 70
            overlayColor = "#082C40"
            overlayAlpha = 20
            [room.panels.players]
            backgroundImage = "room/players.png"
            backgroundColor = "#FFF9EF"
            contentColor = "#123456"
            borderColor = "#806040"
            borderWidth = 2
            corner = 12
            opacity = 90
            [room.player]
            backgroundImage = "room/row.png"
            localImage = "room/local.png"
            hostImage = "room/host.png"
            aiImage = "room/ai.png"
            readyImage = "room/ready.png"
            notReadyImage = "room/not-ready.png"
            emptyImage = "room/empty.png"
            nameFont = "fonts/name.ttf"
            nameFontBold = "fonts/name-bold.ttf"
            nameWeight = 700
            nameColor = "#123456"
            nameUseTeamColor = false
            [room.player.slotImages]
            "1" = "room/slot-1.png"
            "8" = "room/slot-8.png"
            [room.player.profile]
            backgroundImage = "room/profile.png"
            litImage = "room/profile-lit.png"
            unavailableImage = "room/profile-unavailable.png"
            iconImage = "room/profile-icon.png"
            tint = "#345678"
            [room.buttons.start]
            backgroundImage = "room/start.png"
            backgroundColor = "#993322"
            contentColor = "#FFFFFF"
            borderColor = "#FFDD88"
            borderWidth = 2
            corner = 4
            opacity = 95
        """.trimIndent())
        assertNotNull(spec, result.errors.joinToString())
        assertTrue(result.warnings.isEmpty(), result.warnings.joinToString())
        assertEquals("room/wallpaper.png", spec.room.background.image)
        assertEquals("fit", spec.room.background.scale)
        assertEquals(70, spec.room.background.alpha)
        assertEquals("room/players.png", spec.room.panels.getValue("players").backgroundImage)
        assertEquals(90, spec.room.panels.getValue("players").opacity)
        assertEquals("room/slot-8.png", spec.room.player.slotImages["8"])
        assertEquals("fonts/name-bold.ttf", spec.room.player.nameFontBold)
        assertEquals(700, spec.room.player.nameWeight)
        assertFalse(spec.room.player.nameUseTeamColor)
        assertEquals("room/profile-icon.png", spec.room.player.profile.iconImage)
        assertEquals("#345678", spec.room.player.profile.tint)
        assertEquals("#993322", spec.room.buttons.getValue("start").backgroundColor)
        assertEquals(spec.room, spec.room.sanitized(), "外观清理必须幂等")
        val encoded = net.peanuuutz.tomlkt.Toml.encodeToString(ArtThemeSpec.serializer(), spec)
        val (reparsed, validation) = validateThemeToml(encoded)
        assertNotNull(reparsed, validation.errors.joinToString())
        assertEquals(spec.room, reparsed.room)
    }

    private val legacyStructure = """
        [room.layout]
        columns = 3
        minColumnWidth = 480
        spacing = 32
        padding = 32
        widthPercent = 50
        maxWidth = 2400
        breakpoint = 1400
        playerListHeight = 640
        chatHeight = 640
        mapHeight = 400
        [room.compact]
        columns = 3
        [room.wide]
        padding = 32
        [room.panels.players]
        order = 99
        span = 3
        padding = 32
        backgroundColor = "#123456"
        [room.player]
        rowHeight = 160
        spacing = 24
        padding = 24
        nameSize = 30.0
        nameMaxLines = 3
        nameLetterSpacing = 5.0
        columnOrder = ["profile", "ping", "name"]
        nameWidth = 10.0
        spawnWidth = 10.0
        teamWidth = 10.0
        pingWidth = 10.0
        showHeader = false
        showPing = false
        showSpawn = false
        showTeam = false
        localImage = "room/local.png"
        [room.player.profile]
        width = 160
        height = 120
        iconSize = 48
        showLabel = true
        labelSize = 18.0
        [room.buttons.start]
        order = 99
        minHeight = 120
        textSize = 24.0
        backgroundColor = "#654321"
    """.trimIndent()

    @Test
    fun legacyStructureIsIgnoredAtImportWhileArtworkIsPreserved() {
        val (spec, result) = validateThemeToml(meta + "\n" + legacyStructure)
        assertNotNull(spec, result.errors.joinToString())
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("已忽略旧版结构字段"))
        listOf("room.layout.columns", "room.compact.columns", "room.wide.padding",
            "room.panels.players.order", "room.player.columnOrder", "room.player.showHeader",
            "room.player.profile.width", "room.buttons.start.minHeight").forEach {
            assertTrue(it in result.warnings.single(), "迁移提示缺少 $it")
        }
        assertEquals(RoomThemeSpec(
            panels = mapOf("players" to RoomPanelSpec(backgroundColor = "#123456")),
            player = RoomPlayerSpec(localImage = "room/local.png"),
            buttons = mapOf("start" to RoomButtonSpec(backgroundColor = "#654321")),
        ), spec.room)
    }

    @Test
    fun explicitDefaultStructureIsAlsoReportedAsIgnored() {
        val (spec, validation) = validateThemeToml(meta + """

            [room.layout]
            columns = 2
            [room.panels.players]
            order = 0
            [room.player]
            showHeader = true
        """.trimIndent())
        assertNotNull(spec)
        assertTrue(validation.warnings.single().contains("room.layout.columns"))
        assertTrue(validation.warnings.single().contains("room.panels.players.order"))
        assertTrue(validation.warnings.single().contains("room.player.showHeader"))
    }

    @Test
    fun directModelsCannotBypassAppearanceOnlyRulesAndInvalidValuesAreSafe() {
        val raw = net.peanuuutz.tomlkt.Toml.decodeFromString(
            ArtThemeSpec.serializer(), meta + "\n" + legacyStructure,
        ).room
        val (parsed, _) = validateThemeToml(meta + "\n" + legacyStructure)
        assertEquals(parsed!!.room, raw.sanitized(), "绕过导入构造的模型在渲染前也必须归一化")
        val room = raw.copy(
            background = RoomBackgroundSpec(scale = "stretch", alpha = -1, overlayAlpha = 999),
            player = raw.player.copy(nameSize = Float.NaN, nameWeight = 9999,
                nameWidth = Float.NaN, nameLetterSpacing = Float.POSITIVE_INFINITY,
                profile = raw.player.profile.copy(width = -1, labelSize = Float.NaN)),
            panels = mapOf("players" to RoomPanelSpec(span = 100, opacity = -1, borderWidth = 999)),
            buttons = mapOf("start" to RoomButtonSpec(minHeight = 0, textSize = Float.NaN)),
        ).sanitized()
        assertEquals("crop", room.background.scale)
        assertEquals(0, room.background.alpha)
        assertEquals(100, room.background.overlayAlpha)
        assertEquals(RoomLayoutSpec(), room.layout)
        assertNull(room.compact)
        assertNull(room.wide)
        assertEquals(RoomPlayerSpec(localImage = "room/local.png", nameWeight = 900), room.player)
        assertEquals(RoomPanelSpec(opacity = 0, borderWidth = 6), room.panels.getValue("players"))
        assertEquals(RoomButtonSpec(), room.buttons.getValue("start"))
        assertEquals(room, room.sanitized())
    }

    @Test
    fun legacyPackageInstallsAndReloadsWithSafeLayout() {
        val work = Files.createTempDirectory("rwpp-room-legacy-").toFile()
        try {
            val archive = File(work, "legacy.rwtheme")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("theme.toml"))
                zip.write((meta + "\n" + legacyStructure).toByteArray())
                zip.closeEntry()
            }
            val root = File(work, "themes")
            val result = ThemePackInstaller.install(archive, root)
            assertTrue(result.success, result.errors.joinToString())
            assertTrue(result.warnings.single().contains("已忽略旧版结构字段"))
            // 重新扫描已安装的旧包，必须与首次导入应用同一套外观限制。
            val (loaded, validation) = validateThemeToml(File(root, "room-test/theme.toml").readText())
            assertNotNull(loaded, validation.errors.joinToString())
            assertEquals(RoomLayoutSpec(), loaded.room.layout)
            assertEquals(ROOM_PLAYER_COLUMN_IDS, loaded.room.player.columnOrder)
            assertEquals(48, loaded.room.player.profile.width)
            assertEquals("room/local.png", loaded.room.player.localImage)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun invalidColorsAndUnknownIdsWarnAndAreRemovedAtImport() {
        val (spec, result) = validateThemeToml(meta + """

            [room.background]
            scale = "diagonal"
            overlayColor = "red"
            [room.panels.unknown]
            backgroundColor = "wrong"
            [room.buttons.unknown]
            backgroundColor = "#123456"
            [room.player]
            nameColor = "wrong"
            [room.player.slotImages]
            "0" = "room/zero.png"
            "01" = "room/leading-zero.png"
            "1001" = "room/large.png"
            "1" = "room/one.png"
        """.trimIndent())
        assertNotNull(spec)
        assertTrue(result.isValid)
        assertEquals(9, result.warnings.size)
        assertTrue(spec.room.panels.isEmpty())
        assertTrue(spec.room.buttons.isEmpty())
        assertEquals("", spec.room.background.overlayColor)
        assertEquals("", spec.room.player.nameColor)
        assertEquals(mapOf("1" to "room/one.png"), spec.room.player.slotImages)
    }

    @Test
    fun everyAssetLocationRejectsOutsideOrAmbiguousPaths() {
        val assignments = listOf(
            "[room.background]\nimage = \"../outside.png\"",
            "[room.panels.players]\nbackgroundImage = \"/outside.png\"",
            "[room.panels.unknown]\nbackgroundImage = \"room/../outside.png\"",
            "[room.player]\nlocalImage = \"C:/outside.png\"",
            "[room.player]\nhostImage = \"room/host.png:stream\"",
            "[room.player]\naiImage = \"room//ai.png\"",
            "[room.player]\nreadyImage = \"room/./ready.png\"",
            "[room.player]\nnotReadyImage = \"room/ not-ready.png\"",
            "[room.player]\nemptyImage = \"room/empty.png/\"",
            "[room.player]\nnameFont = \"../font.ttf\"",
            "[room.player]\nnameFontBold = \"//server/font.ttf\"",
            "[room.player.slotImages]\n\"1\" = \"../slot.png\"",
            "[room.player.profile]\niconImage = \"room\\\\icon.png\"",
            "[room.player.profile]\nlitImage = \"../lit.png\"",
            "[room.player.profile]\nunavailableImage = \"../unavailable.png\"",
            "[room.buttons.start]\nbackgroundImage = \"../start.png\"",
        )
        assignments.forEach { assignment ->
            val (spec, result) = validateThemeToml(meta + "\n" + assignment)
            assertNull(spec, assignment)
            assertFalse(result.isValid, assignment)
            assertTrue(result.errors.any { "路径非法" in it }, result.errors.joinToString())
        }
    }

    @Test
    fun pathSafetyAllowsNormalUnicodeAssetsAndRejectsControls() {
        assertTrue(isSafeThemeAssetPath("room/玩家槽位.png"))
        assertTrue(isSafeThemeAssetPath("fonts/player-name.otf"))
        assertFalse(isSafeThemeAssetPath("room/name\u0000.png"))
        assertFalse(isSafeThemeAssetPath("room/name\n.png"))
        assertFalse(isSafeThemeAssetPath("room/name.png "))
        assertFalse(isSafeThemeAssetPath("room/name.png:stream"))
        assertFalse(isSafeThemeAssetPath("room/./name.png"))
        assertFalse(isSafeThemeAssetPath("room//name.png"))
    }

    @Test
    fun dangerousRoomPathsAreRejectedBeforeInstallationCreatesDirectory() {
        val work = Files.createTempDirectory("rwpp-room-theme-test-").toFile()
        try {
            val archive = File(work, "unsafe.rwtheme")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("theme.toml"))
                zip.write((meta + "\n[room.player]\nnameFont = \"../outside.ttf\"\n").toByteArray())
                zip.closeEntry()
            }
            val root = File(work, "themes")
            val result = ThemePackInstaller.install(archive, root)
            assertFalse(result.success)
            assertTrue(result.errors.any { "room.player.nameFont" in it })
            assertFalse(root.exists(), "校验失败必须在创建主题目录前返回")
        } finally {
            work.deleteRecursively()
        }
    }
}
