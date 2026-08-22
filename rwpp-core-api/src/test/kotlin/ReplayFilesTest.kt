/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.map.parseReplayLabel
import io.github.rwpp.game.map.replayImportDestination
import io.github.rwpp.game.map.replayImportTargetName
import io.github.rwpp.game.map.scanReplayFiles
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayFilesTest {
    @Test
    fun scanReplayFilesReturnsOnlyReplayFilesNewestFirst() {
        val root = createTempDirectory().toFile()
        val older = root.resolve("bravo.replay")
        older.writeText("r")
        older.setLastModified(1_000L)
        val newer = root.resolve("Alpha.replay")
        newer.writeText("r")
        newer.setLastModified(2_000L)
        root.resolve("ignore.map").writeText("m")
        root.resolve("save.rwsave").writeText("s")
        root.resolve("nested").mkdirs()
        root.resolve("nested/hidden.replay").writeText("r")

        val files = scanReplayFiles(root)
        assertEquals(listOf("Alpha.replay", "bravo.replay"), files.map { it.name })
        assertEquals("Alpha", files.first().displayName())
        assertEquals("Alpha.replay", files.first().toReplay(0).name)
        assertEquals(2_000L, files.first().toReplay(0).lastModifiedMillis)
    }

    @Test
    fun parseReplayLabelSplitsVanillaFilename() {
        val zh = parseReplayLabel("Crossing Large (10p) [v1.15] (21 8月 2026 21.50.37)")
        assertEquals("Crossing Large", zh.title)
        assertEquals(10, zh.playerCount)
        assertEquals("v1.15", zh.version)
        assertEquals("2026-08-21 21:50:37", zh.recordedAt)

        val en = parseReplayLabel("Crossing Large (10p) [v1.15] (21 August 2026 21.50.37)")
        assertEquals("2026-08-21 21:50:37", en.recordedAt)

        val prefixed = parseReplayLabel("[replay] Crossing Large (10p) [v1.15] (21 Aug 2026 21.50.37)")
        assertEquals("Crossing Large", prefixed.title)
        assertEquals("2026-08-21 21:50:37", prefixed.recordedAt)
    }

    @Test
    fun parseReplayLabelKeepsUnknownNames() {
        val label = parseReplayLabel("my custom save")
        assertEquals("my custom save", label.title)
        assertNull(label.playerCount)
        assertNull(label.version)
        assertNull(label.recordedAt)
    }

    @Test
    fun scanReplayFilesReturnsEmptyWhenDirectoryMissing() {
        val missing = createTempDirectory().toFile().resolve("replays")
        assertTrue(scanReplayFiles(missing).isEmpty())
    }

    @Test
    fun replayImportDestinationNormalizesReplyExtension() {
        val destDir = createTempDirectory().toFile()
        val replay = destDir.resolve("game.replay")
        replay.writeText("r")
        val reply = destDir.resolve("game.reply")
        reply.writeText("r")
        val map = destDir.resolve("map.tmx")
        map.writeText("m")

        assertEquals("game.replay", replayImportTargetName("game.reply"))
        assertEquals("game.replay", replayImportTargetName("game.REPLY"))
        assertEquals("game.replay", replayImportDestination(replay, destDir)?.name)
        assertEquals("game.replay", replayImportDestination(reply, destDir)?.name)
        assertNull(replayImportDestination(map, destDir))
    }
}
