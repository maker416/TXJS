/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.encodeToString
import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPersistenceTest {
    @Test
    fun animationsAreEnabledByDefaultAndOnlyResetOnce() {
        assertTrue(Settings().enableAnimations)

        fun reload(settings: Settings) =
            decodeSavedConfig(Settings::class, Toml.encodeToString(settings)).apply { migrate() }

        for (legacyVersion in listOf(null, 1, 2)) {
            val upgraded = reload(Settings(enableAnimations = false, configVersion = legacyVersion))
            assertTrue(upgraded.enableAnimations)
            assertFalse(reload(upgraded.copy(enableAnimations = false)).enableAnimations)
        }
    }

    @Test
    fun developerAddressesAreDiscardedWhenSettingsAreReloaded() {
        val temporary = "https://session.example.test"
        val account = AccountPreferences(apiUrl = temporary, forumUrl = temporary, lastUsername = "player")
        assertEquals(
            account.copy(apiUrl = DEFAULT_ACCOUNT_API_URL, forumUrl = DEFAULT_FORUM_URL),
            reloadConfig(account),
        )
        assertEquals(MultiplayerPreferences(), reloadConfig(MultiplayerPreferences(
            roomListApiUrls = temporary, modSyncApiUrls = temporary,
        )))
        assertEquals(ModPlaytimePreferences(), reloadConfig(ModPlaytimePreferences(apiUrl = temporary)))
        assertEquals(OnlinePresencePreferences(), reloadConfig(OnlinePresencePreferences(apiUrl = temporary)))
        assertEquals(FeedbackPreferences(), reloadConfig(FeedbackPreferences(apiUrl = temporary)))

        assertEquals(account.copy(apiUrl = DEFAULT_ACCOUNT_API_URL, forumUrl = DEFAULT_FORUM_URL),
            decodeSavedConfig(AccountPreferences::class, """
                apiUrl = "$temporary"
                forumUrl = "$temporary"
                lastUsername = "player"
            """.trimIndent()),
        )
    }

    private inline fun <reified T : Config> reloadConfig(config: T): T {
        val saved = Toml.encodeToString(config)
        assertFalse(saved.contains("https://session.example.test"))
        return decodeSavedConfig(T::class, saved)
    }
}
