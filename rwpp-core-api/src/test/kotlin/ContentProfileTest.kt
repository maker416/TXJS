/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ContentModSource
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import io.github.rwpp.game.mod.ModSettingsSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ContentProfileTest {
    private val root = Files.createTempDirectory("rwjs-content-profile-").toFile()
    @AfterTest fun cleanup() { ContentProfiles.resetForTests(); root.deleteRecursively() }

    @Test fun onlyTheExactActiveSourcesAreEnabledEvenWhenNamesCollide() {
        val expansions = File(root, "expansions")
        val active = File(expansions, "game/hash/mods/same.rwmod")
        val previous = File(expansions, "other/hash/mods/same.rwmod")
        val ordinary = File(root, "mods/same.rwmod")
        val network = File(root, "mods/same.network.rwmod")
        ContentProfiles.configureRoot(expansions)
        ContentProfiles.stage(ContentProfile.Expansion("game", "a".repeat(64), listOf(ContentModSource("same", active))))
        assertEquals(true, ContentProfiles.resolve(listOf(active.path)))
        for (source in listOf(previous.path, ordinary.path, network.path, "builtin_mods/example", "same.rwmod")) {
            assertEquals(false, ContentProfiles.resolve(listOf(source)), source)
        }
        assertTrue(ContentProfiles.preserveSelection)
        ContentProfiles.finishNormal()
        assertEquals(false, ContentProfiles.resolve(listOf(active.path)))
        assertNull(ContentProfiles.resolve(listOf(ordinary.path)))
        assertFalse(ContentProfiles.preserveSelection)
    }

    @Test fun crashRecoveryRestoresTheSavedSelectionBeforeTheFirstUnitScan() {
        val journal = File(root, "expansions/.session-baseline.properties")
        val ordinary = File(root, "mods/local.rwmod").canonicalPath
        val unwanted = File(root, "mods/unwanted.rwmod").canonicalPath
        ContentProfiles.recoveryFileOverride = journal
        val raw = ModSettingsSnapshot("原版选择:local|disabled=other\n", 2, 7)
        ContentProfiles.writeRecovery(raw, mapOf(ordinary to true, unwanted to false))
        assertTrue(journal.isFile)
        var restored: ModSettingsSnapshot? = null
        ContentProfiles.recoverBeforeFirstScan { restored = it }
        assertEquals(raw, restored)
        assertTrue(ContentProfiles.preserveSelection)
        assertEquals(true, ContentProfiles.resolve(listOf(ordinary)))
        assertEquals(false, ContentProfiles.resolve(listOf(unwanted)))
        assertEquals(false, ContentProfiles.resolve(listOf("newly-installed.rwmod")))
        ContentProfiles.completeStartupScan()
        assertFalse(journal.exists())
        assertFalse(ContentProfiles.preserveSelection)
        assertNull(ContentProfiles.resolve(listOf(ordinary)))
    }

    @Test fun malformedJournalCannotSilentlyFallBackToUnknownModSettings() {
        ContentProfiles.recoveryFileOverride = File(root, "journal.properties").apply { writeText("schema=wrong\n") }
        assertFailsWith<IllegalStateException> { ContentProfiles.recoverBeforeFirstScan { error("Must not restore bad settings") } }
        assertFailsWith<IllegalStateException> { ContentProfiles.recoverBeforeFirstScan { error("A failed recovery must remain blocked") } }
    }
}
