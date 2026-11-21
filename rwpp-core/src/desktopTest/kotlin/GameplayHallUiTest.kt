/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.use
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.net.games.OnlineGameServer
import io.github.rwpp.widget.UiMotionTheme
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.setUiAnimationsEnabled
import kotlinx.coroutines.CompletableDeferred
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GameplayHallUiTest {
    private lateinit var settings: Settings
    private val gameId = "11111111-1111-1111-1111-111111111111"
    private val otherId = "22222222-2222-2222-2222-222222222222"
    private val published = OnlineGame(gameId, "星际远征", "已审核的持续运营网游", available = true,
        servers = listOf(OnlineGameServer("main", "主区服", "127.0.0.1:5123", available = true,
            online = true, currentPlayers = 2, maxPlayers = 10),
            OnlineGameServer("offline", "维护区服", "127.0.0.1:5124", closedMessage = "正在更新")),
        artBinding = OnlineGameArtBinding("33333333-3333-3333-3333-333333333333", "1.0", "a".repeat(64)),
        portalUrl = "https://game.example/portal", portalRevision = 1)
    private val offline = OnlineGame(otherId, "边境探索", "暂停开放的区服仍可查看", servers =
        listOf(OnlineGameServer("closed", "维护区服", "127.0.0.1:6000")), portalUrl = "https://offline.example/", portalRevision = 2)

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        appKoin = startKoin { modules(module { single { settings } }) }.koin
        reloadI18n()
        language("zh")
        SwingUtilities.invokeAndWait {
            UI.showAccountView = false
            UI.showFriendsView = false
            UI.pendingOnlineGameJoin = null
            UI.pendingExpansionGameAppId = null
            resetNavigation(LauncherPage.MainMenu)
        }
    }

    @AfterTest
    fun cleanup() {
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        stopKoin()
        reloadI18n()
    }

    @Test
    fun desktopCategoriesSearchFavoritesAndRealDestination() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var destination: LauncherPage? = null
        show(onOpen = { destination = it })
        onNodeWithTag("hall-featured").assertIsDisplayed()
        save("desktop.png")
        onNodeWithTag("hall-category-extended").performClick()
        onNodeWithTag("hall-favorite-game-$gameId").performClick()
        onNodeWithTag("hall-category-favorites").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()
        onNodeWithTag("hall-category-all").performClick()
        onNodeWithTag("hall-search").performTextInput("生存挑战")
        onNodeWithTag("hall-card-survival").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(LauncherPage.Survival, destination) }
        onNodeWithTag("hall-search").performTextReplacement("nothing-matches")
        onNodeWithText("没有找到匹配的玩法，试试其他关键词或筛选。").assertIsDisplayed()
        onNodeWithTag("hall-reset").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-filter-status").performClick()
        onNode(hasText("可进入") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
        onNodeWithTag("hall-filter-tag").performClick()
        onNode(hasText("PVE") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        onNodeWithTag("hall-card-missions").assertIsDisplayed()
    }

    @Test
    fun phoneLandscapeAndPreviewStayUsable() = runDesktopComposeUiTest(width = 960, height = 432) {
        var closed = 0
        var destination: LauncherPage? = null
        show(onExit = { closed++ }, onOpen = { destination = it })
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-featured-open").assertIsDisplayed()
        save("landscape.png")
        onNodeWithTag("hall-featured-1").performClick()
        onNodeWithTag("hall-featured-open").performClick()
        runOnIdle { assertEquals(LauncherPage.GamePortal(otherId), destination) }
        onNodeWithTag("hall-detail").assertDoesNotExist()
        onNodeWithTag("hall-server-closed").assertDoesNotExist()
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun portraitAndEnglishHaveReachableContent() = runDesktopComposeUiTest(width = 412, height = 844) {
        language("en")
        show()
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-search").assertIsDisplayed()
        onNodeWithTag("hall-featured-open").assertIsDisplayed()
        save("portrait-en.png")
        onNodeWithTag("hall-search").performTextInput("Classic")
        onNodeWithTag("hall-content").performScrollToNode(hasTestTag("hall-card-multiplayer"))
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
    }

    @Test
    fun hallNavigationRestoresFiltersAndRespectsLiveAnimationToggle() = runDesktopComposeUiTest(width = 960, height = 640) {
        mainClock.autoAdvance = false
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    if (launcherPage == LauncherPage.MainMenu) Text("home")
                    GameplayHallPageHost {
                        GameplayHallView({ closePage(LauncherPage.GameplayHall) }, { navigateTo(it) }, loadGames = { emptyList() })
                    }
                }
            }
        }
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("gameplay-hall").assertIsDisplayed()
        onNodeWithTag("hall-category-original").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("hall-card-multiplayer").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeBy(48)
        runOnIdle { assertEquals(LauncherPage.Multiplayer, launcherPage) }
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
        runOnIdle { navigateBack() }
        mainClock.advanceTimeBy(48)
        onNodeWithTag("hall-featured").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
        onNodeWithTag("hall-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeBy(48)
        onNodeWithText("home").assertIsDisplayed()
        runOnIdle {
            settings.setUiAnimationsEnabled(true)
            navigateTo(LauncherPage.GameplayHall)
        }
        mainClock.advanceTimeBy(500)
        onNodeWithTag("gameplay-hall").assertIsDisplayed()
        onNodeWithTag("hall-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("gameplay-hall").assertExists() // 动画保留绘制。
        runOnIdle { settings.setUiAnimationsEnabled(false) }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
    }

    @Test
    fun approvedGameOpensItsWebsiteWithoutNativeServerSelection() = runDesktopComposeUiTest(width = 412, height = 844) {
        var destination: LauncherPage? = null
        show(onOpen = { destination = it })
        onNodeWithTag("hall-featured-open").performClick()
        runOnIdle { assertEquals(LauncherPage.GamePortal(gameId), destination) }
        onNodeWithTag("hall-detail").assertDoesNotExist()
        onNodeWithTag("hall-server-main").assertDoesNotExist()
    }

    @Test
    fun portalRequiresLoginAndResumesIntoTheWebsiteAfterAuthentication() = runDesktopComposeUiTest(width = 960, height = 640) {
        var destination: LauncherPage? = null
        var loggedIn by mutableStateOf(false)
        var login = 0
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                GameplayHallView({}, { destination = it }, loadGames = { listOf(published) }, loggedIn = loggedIn,
                    onLogin = { login++; UI.showAccountView = true })
            }
        }
        onNodeWithTag("hall-featured-open").performClick()
        runOnIdle { assertEquals(1, login); assertNull(destination); loggedIn = true }
        waitUntil { destination != null }
        runOnIdle { assertEquals(LauncherPage.GamePortal(gameId), destination); assertFalse(UI.showAccountView) }
        onNodeWithTag("hall-detail").assertDoesNotExist()
    }

    @Test
    fun directoryFailureDoesNotInventOnlineGamesOrHideClassicModes() = runDesktopComposeUiTest(width = 1280, height = 720) {
        show(load = { error("unavailable") })
        onNodeWithTag("hall-directory-status").assertTextContains("暂时无法获取网游目录", substring = true)
        onNodeWithTag("hall-category-extended").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()
        onNodeWithTag("hall-card-royale").assertDoesNotExist()
        onNodeWithTag("hall-category-original").performClick()
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
        save("directory-failure-desktop.png")
    }

    @Test
    fun missingPortalKeepsPublicCardButCannotOpenNativeServerSelection() = runDesktopComposeUiTest(width = 960, height = 640) {
        var destination: LauncherPage? = null
        show(load = { listOf(published.copy(portalUrl = "", portalRevision = 0)) }, onOpen = { destination = it })
        onNodeWithTag("hall-featured-open").assertIsNotEnabled()
        runOnIdle { assertNull(destination) }
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$gameId").assertIsNotEnabled()
        onNodeWithTag("hall-detail").assertDoesNotExist()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun hallTransitionAnimatesWithoutPerFrameRecompositionAndStopsCleanlyWhenDisabled() {
        var featuredDotPos = Offset.Zero
        var categoryOriginalPos = Offset.Zero
        var favoriteMultiplayerPos = Offset.Zero
        runDesktopComposeUiTest(width = 960, height = 720) {
            setContent {
                UiMotionTheme(darkColorScheme(), Typography()) {
                    GameplayHallView({}, {}, loadGames = { emptyList() })
                }
            }
            waitForIdle()
            featuredDotPos = onNodeWithTag("hall-featured-1").fetchSemanticsNode().boundsInRoot.center
            categoryOriginalPos = onNodeWithTag("hall-category-original").fetchSemanticsNode().boundsInRoot.center
            onNodeWithTag("hall-category-original").performClick()
            waitForIdle()
            favoriteMultiplayerPos = onNodeWithTag("hall-favorite-multiplayer").fetchSemanticsNode().boundsInRoot.center
        }

        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        settings.setUiAnimationsEnabled(true)
        var hallCompositions = 0
        ImageComposeScene(width = 960, height = 720) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    GameplayHallPageHost {
                        SideEffect { hallCompositions++ }
                        GameplayHallView({ closePage(LauncherPage.GameplayHall) }, { navigateTo(it) }, loadGames = { emptyList() })
                    }
                }
            }
        }.use { scene ->
            scene.render(0L).close()
            SwingUtilities.invokeAndWait { navigateTo(LauncherPage.GameplayHall) }
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000L).close()
            scene.render(32_000_000L).close()
            val earlyPng = scene.render(48_000_000L).use { it.encodeToData()!!.bytes }
            val startCompositions = hallCompositions
            for (frame in 4..14) {
                scene.render(frame * 16_000_000L).close()
            }
            val midPng = scene.render(240_000_000L).use { it.encodeToData()!!.bytes }
            assertFalse(earlyPng.contentEquals(midPng), "开启动画时入场应随时间推进")
            assertEquals(startCompositions, hallCompositions, "转场动画数值应只在 graphicsLayer 读取，不能逐帧触发重组")

            for (frame in 16..45) {
                scene.render(frame * 16_000_000L).close()
            }
            assertFalse(scene.hasInvalidations(), "转场完成后不得残留持续动画调度")

            fun settleFrames(startMs: Long) {
                repeat(5) { step ->
                    Snapshot.sendApplyNotifications()
                    scene.render((startMs + step * 16L) * 1_000_000L).close()
                }
            }

            settings.setUiAnimationsEnabled(false)
            settleFrames(750L)
            assertFalse(scene.hasInvalidations(), "关闭动画后不得残留待渲染帧")

            fun clickAt(pos: Offset, startMs: Long) {
                scene.sendPointerEvent(PointerEventType.Press, pos)
                Snapshot.sendApplyNotifications()
                scene.render(startMs * 1_000_000L).close()
                scene.sendPointerEvent(PointerEventType.Release, pos)
                settleFrames(startMs + 16L)
            }

            clickAt(featuredDotPos, 850L)
            assertFalse(scene.hasInvalidations(), "关闭动画时切换精选不得请求动画帧")
            clickAt(categoryOriginalPos, 950L)
            assertFalse(scene.hasInvalidations(), "关闭动画时切换分类不得请求动画帧")
            clickAt(favoriteMultiplayerPos, 1050L)
            assertFalse(scene.hasInvalidations(), "关闭动画时点击收藏不得请求动画帧")
        }
    }

    @Test
    fun hallDirectoryCacheReusesSnapshotAcrossReentryAndClearsOnFailure() = runDesktopComposeUiTest(width = 960, height = 640) {
        var failLoad = false
        var pendingGate: CompletableDeferred<List<OnlineGame>>? = null
        var loads = 0
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    GameplayHallPageHost {
                        GameplayHallView(
                            onExit = { closePage(LauncherPage.GameplayHall) },
                            onOpenPage = { navigateTo(it) },
                            loadGames = {
                                loads++
                                pendingGate?.let { return@GameplayHallView it.await() }
                                if (failLoad) error("directory down")
                                listOf(published)
                            },
                        )
                    }
                }
            }
        }

        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        waitUntil(timeoutMillis = 5_000) { loads == 1 }
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()

        runOnIdle { closePage(LauncherPage.GameplayHall) }
        waitForIdle()
        onNodeWithTag("gameplay-hall").assertDoesNotExist()

        val refreshGate = CompletableDeferred<List<OnlineGame>>()
        pendingGate = refreshGate
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        waitUntil(timeoutMillis = 5_000) { loads == 2 }
        // 新刷新仍在挂起时，二次进大厅已立即可见上次目录快照，不会中途闪烁空态。
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-directory-status").assertTextContains("开放平台", substring = true)

        refreshGate.complete(listOf(published, offline))
        waitForIdle()
        onNodeWithTag("hall-card-game-$otherId").assertIsDisplayed()

        runOnIdle { closePage(LauncherPage.GameplayHall) }
        waitForIdle()
        pendingGate = null
        failLoad = true
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        waitUntil(timeoutMillis = 5_000) { loads == 3 }
        waitForIdle()
        onNodeWithTag("hall-directory-status").assertTextContains("暂时无法获取网游目录", substring = true)
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()

        // 失败后缓存必须同步清空，再次进入时不得回放过期网游。
        runOnIdle { closePage(LauncherPage.GameplayHall) }
        waitForIdle()
        val postFailureGate = CompletableDeferred<List<OnlineGame>>()
        pendingGate = postFailureGate
        failLoad = false
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        waitUntil(timeoutMillis = 5_000) { loads == 4 }
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()
        postFailureGate.complete(emptyList())
        waitForIdle()
    }

    private fun language(code: String) {
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$code.toml").readText())
    }

    private fun ComposeUiTest.show(
        onExit: () -> Unit = {}, onOpen: (LauncherPage) -> Unit = {},
        load: suspend () -> List<OnlineGame> = { listOf(published, offline) },
        refresh: suspend (String) -> OnlineGame? = { published },
        loggedIn: Boolean = true,
        onLogin: () -> Unit = {},
    ) {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    GameplayHallView(onExit, onOpen, loadGames = load, refreshGame = refresh, loggedIn = loggedIn, onLogin = onLogin)
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String, tag: String? = null) {
        val file = File("build/reports/gameplay-hall/$name").apply { parentFile.mkdirs() }
        val target = tag?.let { onNodeWithTag(it) } ?: onRoot()
        ImageIO.write(target.captureToImage().toAwtImage(), "png", file)
    }
}
