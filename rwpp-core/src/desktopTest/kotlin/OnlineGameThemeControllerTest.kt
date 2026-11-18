/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.event.broadcast
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.appearance.VerifiedGameServer
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.net.games.OnlineGameServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineGameThemeControllerTest {
    private lateinit var work: File
    private lateinit var settings: Settings
    private lateinit var game: OnlineGame
    private var token: Long? = null

    @BeforeTest fun setup() = runBlocking {
        runCatching { stopKoin() }
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        settings = Settings(language = "zh", autoCheckUpdate = false, launcherMusicVolume = 0f)
        appKoin = startKoin { modules(module { single { settings } }) }.koin
        koinInit = true
        logger = LoggerFactory.getLogger("OnlineGameThemeControllerTest")
        work = Files.createTempDirectory("rwjs-online-theme-test-").toFile()
        ArtThemeController.themeRootOverride = work
        for (id in listOf("personal", "game")) {
            File(work, "$id/theme.toml").apply { parentFile.mkdirs(); writeText("""
                [theme]
                id = "$id"
                name = "$id"
                version = "1.0.0"
                [personalization]
                platformPackId = "01234567-89ab-cdef-0123-456789abcdef"
                [personalization.slots."room.player.frame".variants.gold]
                borderColor = "#FFD700"
            """.trimIndent()) }
        }
        withContext(Dispatchers.Main) {
            ArtThemeController.scan()
            ArtThemeController.apply("personal")
            OnlineGameThemeController.init()
        }
        val theme = ArtThemeController.installedThemes.single { it.id == "game" }
        game = OnlineGame("11111111-1111-1111-1111-111111111111", "Approved game", available = true,
            servers = listOf(OnlineGameServer("approved-node", "Main", "127.0.0.1:5123", available = true)),
            artBinding = OnlineGameArtBinding(theme.spec.personalization.platformPackId, "1.0.0", theme.personalizedCatalog!!.catalogHash))
    }

    @AfterTest fun cleanup() = runBlocking {
        token?.let { OnlineGameThemeController.cancelPrepared(it) }
        withContext(Dispatchers.Main) {
            ArtThemeController.apply(null)
            ArtThemeController.themeRootOverride = null
        }
        stopKoin()
        koinInit = false
        work.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun preparesWithoutChangingThemeThenRestoresOnActualDisconnect() = runBlocking {
        val original = ArtThemeController.activeTheme
        val current = OnlineGameThemeController.prepare(game).also { token = it }
        assertSame(original, ArtThemeController.activeTheme)
        OnlineGameThemeController.activatePrepared(current)
        OnlineGameThemeController.activatePrepared(current) // Repeated callbacks must retain the original restoration target.
        assertEquals("game", ArtThemeController.activeTheme?.id)
        assertEquals("personal", settings.selectedThemePack)
        withContext(Dispatchers.Main) { OnlineGameThemeController.onRoomOpened(current) }
        DisconnectEvent("test").broadcast()
        assertSame(original, ArtThemeController.activeTheme)
        assertEquals("personal", settings.selectedThemePack)
    }

    @Test fun defaultJoinIsTemporaryAndOnlyAcceptsThePreparedGameAndNode() = runBlocking {
        val current = OnlineGameThemeController.useDefault(game).also { token = it }
        OnlineGameThemeController.activatePrepared(current)
        assertNull(ArtThemeController.activeTheme)
        assertEquals("personal", settings.selectedThemePack)
        assertTrue(OnlineGameThemeController.permitsServer(VerifiedGameServer("approved-node", game.appId, game.artBinding)))
        assertFalse(OnlineGameThemeController.permitsServer(VerifiedGameServer("another-node", game.appId, game.artBinding)))
        assertFalse(OnlineGameThemeController.permitsServer(VerifiedGameServer("approved-node")))
        OnlineGameThemeController.cancelPrepared(current)
        assertEquals("personal", ArtThemeController.activeTheme?.id)
    }

    @Test fun lateCancellationCannotRestoreOverANewerAttemptOrExplicitUserSelection() = runBlocking {
        val old = OnlineGameThemeController.prepare(game)
        val current = OnlineGameThemeController.useDefault(game).also { token = it }
        OnlineGameThemeController.activatePrepared(current)
        OnlineGameThemeController.cancelPrepared(old)
        assertNull(ArtThemeController.activeTheme)
        withContext(Dispatchers.Main) { assertTrue(ArtThemeController.apply("game")) }
        OnlineGameThemeController.cancelPrepared(current)
        assertEquals("game", ArtThemeController.activeTheme?.id)
        assertEquals("game", settings.selectedThemePack)
    }

    @Test fun packageVersionAndCatalogMustBothMatchWithoutChangingTheUsersTheme() = runBlocking {
        val original = ArtThemeController.activeTheme
        assertFailsWith<IOException> { OnlineGameThemeController.prepare(game.copy(artBinding = game.artBinding!!.copy(version = "2.0"))) }
        assertFailsWith<IOException> { OnlineGameThemeController.prepare(game.copy(artBinding = game.artBinding!!.copy(catalogHash = "0".repeat(64)))) }
        assertSame(original, ArtThemeController.activeTheme)
        assertEquals("personal", settings.selectedThemePack)
    }

    @Test fun oneGamesBindingCannotApplyToAnotherGameOrAnotherCatalog() = runBlocking {
        val current = OnlineGameThemeController.prepare(game).also { token = it }
        OnlineGameThemeController.activatePrepared(current)
        val theme = ArtThemeController.activeTheme!!
        val approved = VerifiedGameServer("approved-node", game.appId, game.artBinding)
        assertTrue(OnlineGameThemeController.permitsAppearance(approved, theme))
        assertFalse(OnlineGameThemeController.permitsAppearance(approved.copy(gameAppId = "22222222-2222-2222-2222-222222222222"), theme))
        assertFalse(OnlineGameThemeController.permitsAppearance(approved.copy(artBinding = game.artBinding!!.copy(catalogHash = "0".repeat(64))), theme))
        assertFalse(OnlineGameThemeController.permitsAppearance(approved.copy(artBinding = null), theme))
    }
}
