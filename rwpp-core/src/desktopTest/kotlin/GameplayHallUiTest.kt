/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
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
import io.github.rwpp.widget.launcherKeyboardEvents
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
    private val published = OnlineGame(
        gameId,
        "星际远征",
        "已审核的持续运营网游，跨星系基地建设与实时舰队战略对抗。",
        available = true,
        servers = listOf(
            OnlineGameServer("main", "主区服", "127.0.0.1:5123", available = true, online = true, currentPlayers = 2, maxPlayers = 10),
            OnlineGameServer("offline", "维护区服", "127.0.0.1:5124", closedMessage = "正在更新"),
        ),
        artBinding = OnlineGameArtBinding("33333333-3333-3333-3333-333333333333", "1.0", "a".repeat(64)),
        portalUrl = "https://game.example/portal",
        portalRevision = 1,
    )
    private val offline = OnlineGame(
        otherId,
        "边境探索",
        "暂停开放的区服仍可进入主页查看最新公告与版本动态。",
        servers = listOf(OnlineGameServer("closed", "维护区服", "127.0.0.1:6000")),
        portalUrl = "https://offline.example/",
        portalRevision = 2,
    )

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
    fun desktopCategoriesSearchFavoritesAndNoClassicModes() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var destination: LauncherPage? = null
        show(onOpen = { destination = it })
        onNodeWithTag("hall-featured").assertIsDisplayed()
        // 不再显示原版玩法入口与原版分类
        onNodeWithTag("hall-category-original").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        onNodeWithTag("hall-card-missions").assertDoesNotExist()
        onNodeWithTag("hall-card-survival").assertDoesNotExist()
        save("desktop.png")

        onNodeWithTag("hall-category-all").performClick()
        onNodeWithTag("hall-favorite-game-$gameId").performClick()
        onNodeWithTag("hall-category-favorites").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()

        onNodeWithTag("hall-category-all").performClick()
        onNodeWithTag("hall-search").performTextInput("边境探索")
        onNodeWithTag("hall-card-game-$otherId").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(LauncherPage.GamePortal(otherId), destination) }

        onNodeWithTag("hall-search").performTextReplacement("nothing-matches")
        onNodeWithText("没有找到匹配的玩法，试试其他关键词或筛选。").assertIsDisplayed()
        onNodeWithTag("hall-reset").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()

        onNodeWithTag("hall-filter-status").performClick()
        onNode(hasText("可进入") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
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
        onNodeWithTag("hall-search").performTextInput("边境探索")
        onNodeWithTag("hall-content").performScrollToNode(hasTestTag("hall-card-game-$otherId"))
        onNodeWithTag("hall-card-game-$otherId").assertIsDisplayed()
    }

    @Test
    fun hallNavigationRestoresFiltersAndRespectsLiveAnimationToggle() = runDesktopComposeUiTest(width = 960, height = 640) {
        mainClock.autoAdvance = false
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    if (launcherPage == LauncherPage.MainMenu) Text("home")
                    GameplayHallPageHost {
                        GameplayHallView(
                            onExit = { closePage(LauncherPage.GameplayHall) },
                            onOpenPage = { navigateTo(it) },
                            loadGames = { listOf(published, offline) },
                            loggedIn = true,
                            initialDevUnlocked = true,
                        )
                    }
                }
            }
        }
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("gameplay-hall").assertIsDisplayed()
        onNodeWithTag("hall-category-all").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("hall-card-game-$gameId").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeBy(48)
        runOnIdle { assertEquals(LauncherPage.GamePortal(gameId), launcherPage) }
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
        runOnIdle { navigateBack() }
        mainClock.advanceTimeBy(48)
        onNodeWithTag("hall-featured").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
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
                GameplayHallView(
                    onExit = {},
                    onOpenPage = { destination = it },
                    loadGames = { listOf(published) },
                    loggedIn = loggedIn,
                    onLogin = {
                        login++
                        UI.showAccountView = true
                    },
                    initialDevUnlocked = true,
                )
            }
        }
        onNodeWithTag("hall-featured-open").performClick()
        runOnIdle {
            assertEquals(1, login)
            assertNull(destination)
            loggedIn = true
        }
        waitUntil { destination != null }
        runOnIdle {
            assertEquals(LauncherPage.GamePortal(gameId), destination)
            assertFalse(UI.showAccountView)
        }
        onNodeWithTag("hall-detail").assertDoesNotExist()
    }

    @Test
    fun directoryFailureAndEmptyDirectoryNeverInventOnlineOrClassicGames() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var fail by mutableStateOf(true)
        show(load = { if (fail) error("unavailable") else emptyList() })
        onNodeWithTag("hall-directory-status").assertTextContains("暂时无法获取网游目录", substring = true)
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()
        onNodeWithTag("hall-card-royale").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        save("directory-failure-desktop.png")

        runOnIdle { fail = false }
        onNodeWithTag("hall-refresh").performClick()
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithText("暂无已上架网游").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("hall-directory-status").assertTextContains("暂无已上架网游", substring = true)
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        save("empty-directory-desktop.png")
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
        var categoryAllPos = Offset.Zero
        var favoriteGamePos = Offset.Zero
        runDesktopComposeUiTest(width = 960, height = 720) {
            setContent {
                UiMotionTheme(darkColorScheme(), Typography()) {
                    GameplayHallView({}, {}, loadGames = { listOf(published, offline) }, initialDevUnlocked = true)
                }
            }
            waitForIdle()
            featuredDotPos = onNodeWithTag("hall-featured-1").fetchSemanticsNode().boundsInRoot.center
            categoryAllPos = onNodeWithTag("hall-category-all").fetchSemanticsNode().boundsInRoot.center
            onNodeWithTag("hall-category-all").performClick()
            waitForIdle()
            favoriteGamePos = onNodeWithTag("hall-favorite-game-$gameId").fetchSemanticsNode().boundsInRoot.center
        }

        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        settings.setUiAnimationsEnabled(true)
        var hallCompositions = 0
        ImageComposeScene(width = 960, height = 720) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    GameplayHallPageHost {
                        SideEffect { hallCompositions++ }
                        GameplayHallView(
                            onExit = { closePage(LauncherPage.GameplayHall) },
                            onOpenPage = { navigateTo(it) },
                            loadGames = { listOf(published, offline) },
                            initialDevUnlocked = true,
                        )
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
            clickAt(categoryAllPos, 950L)
            assertFalse(scene.hasInvalidations(), "关闭动画时切换分类不得请求动画帧")
            clickAt(favoriteGamePos, 1050L)
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
                            initialDevUnlocked = true,
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

    @Test
    fun underDevelopmentFrostedOverlayBlocksContentAndUnlocksAfterFiveQPresses() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var loads = 0
        show(
            load = {
                loads++
                listOf(published, offline)
            },
            devUnlocked = false,
        )
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()
        onNodeWithTag("hall-dev-notice").assertIsDisplayed()
        onNodeWithTag("hall-dev-badge").assertIsDisplayed()
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-dev-back").assertIsDisplayed()
        onNodeWithText("正在开发中，敬请期待").assertIsDisplayed()

        // 不可由普通玩家点击关闭，也不暴露开发者解锁按键说明。
        onNodeWithTag("hall-dev-notice-dismiss").assertDoesNotExist()
        assertTrue(onAllNodesWithText("Q", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("5", substring = true).fetchSemanticsNodes().isEmpty())

        // 底层第二主界面完全不可见且不发起目录轮询。
        onNodeWithTag("hall-featured").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()
        onNodeWithTag("hall-search").assertDoesNotExist()
        onNodeWithTag("hall-category-all").assertDoesNotExist()
        onNodeWithTag("hall-directory-status").assertDoesNotExist()
        runOnIdle { assertEquals(0, loads) }
        save("dev-frosted-desktop.png")

        // 点击遮罩或徽章不会关闭提示。
        onNodeWithTag("hall-dev-overlay").performClick()
        onNodeWithTag("hall-dev-badge").performClick()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 按 4 下 Q 仍保持锁定。
        repeat(4) {
            onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()
        onNodeWithTag("hall-featured").assertDoesNotExist()

        // 插入其他按键会重置计数，再按 4 下 Q 仍不解锁。
        onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.A) }
        repeat(4) {
            onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 第 5 下 Q 关闭毛玻璃遮罩，显示真实第二主界面供开发者测试。
        onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        waitUntil(timeoutMillis = 3_000) { loads >= 1 }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertDoesNotExist()
        onNodeWithTag("hall-featured").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()

        // 开发者点击顶栏「开发中」徽章可重新切回毛玻璃遮罩。
        onNodeWithTag("hall-dev-badge").performClick()
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()
        onNodeWithTag("hall-featured").assertDoesNotExist()
    }

    @Test
    fun underDevelopmentOverlayIgnoresHeldQRepeatAndTimeoutWindowAndSupportsExit() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var nowMs = 1_000L
        var exits = 0
        show(
            onExit = { exits++ },
            devUnlocked = false,
            devUnlockClockMillis = { nowMs },
        )
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 长按 Q（仅一次按下与抬起）不会触发 5 次解锁。
        onNodeWithTag("hall-dev-overlay").performKeyInput {
            keyDown(Key.Q)
            advanceEventTime(600L)
            keyUp(Key.Q)
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // Ctrl+Q 组合键不计入解锁。
        repeat(5) {
            onNodeWithTag("hall-dev-overlay").performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.Q) }
            }
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 超过时间窗后计数归零重新起算。
        repeat(3) {
            nowMs += 200L
            onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        }
        nowMs += HALL_DEV_UNLOCK_WINDOW_MS + 100L
        repeat(4) {
            nowMs += 200L
            onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 返回按钮正常退出第二主界面。
        onNodeWithTag("hall-dev-back").performClick()
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(2, exits) }

        // 窗口内补齐第 5 次 Q 即可关闭。
        nowMs += 200L
        onNodeWithTag("hall-dev-overlay").performKeyInput { pressKey(Key.Q) }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertDoesNotExist()
        onNodeWithTag("hall-featured").assertIsDisplayed()
    }

    @Test
    fun underDevelopmentOverlayUnlocksViaFallbackKeyboardEventWhenFocusIsOutsideOverlay() = runDesktopComposeUiTest(width = 1280, height = 720) {
        setContent {
            val scope = rememberCoroutineScope()
            val outerFocus = remember { FocusRequester() }
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(outerFocus)
                        .launcherKeyboardEvents(scope)
                        .focusable()
                        .testTag("outer-host"),
                ) {
                    GameplayHallView(
                        onExit = {},
                        onOpenPage = {},
                        loadGames = { listOf(published) },
                        initialDevUnlocked = false,
                    )
                }
            }
            LaunchedEffect(Unit) {
                outerFocus.requestFocus()
            }
        }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        // 即使焦点停留在外层 App 根节点，桌面全局 KeyboardEvent 兜底也能准确计数 5 次解锁。
        repeat(4) {
            onNodeWithTag("outer-host").performKeyInput { pressKey(Key.Q) }
            waitForIdle()
        }
        onNodeWithTag("hall-dev-overlay").assertIsDisplayed()

        onNodeWithTag("outer-host").performKeyInput { pressKey(Key.Q) }
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag("hall-dev-overlay").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag("hall-featured").assertIsDisplayed()
    }

    @Test
    fun underDevelopmentFrostedOverlayLayoutsCleanlyOnLandscapeAndPortraitAndEnglish() {
        runDesktopComposeUiTest(width = 960, height = 432) {
            language("zh")
            show(devUnlocked = false)
            waitForIdle()
            onNodeWithTag("hall-dev-overlay").assertIsDisplayed()
            onNodeWithTag("hall-back").assertIsDisplayed()
            onNodeWithTag("hall-dev-back").assertIsDisplayed()
            onNodeWithText("正在开发中，敬请期待").assertIsDisplayed()
            onNodeWithTag("hall-featured").assertDoesNotExist()
            save("dev-frosted-landscape.png")
        }
        runDesktopComposeUiTest(width = 412, height = 844) {
            language("en")
            show(devUnlocked = false)
            waitForIdle()
            onNodeWithTag("hall-dev-overlay").assertIsDisplayed()
            onNodeWithTag("hall-back").assertIsDisplayed()
            onNodeWithTag("hall-dev-back").assertIsDisplayed()
            onNodeWithText("In Development · Stay Tuned").assertIsDisplayed()
            onNodeWithTag("hall-featured").assertDoesNotExist()
            save("dev-frosted-portrait-en.png")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun underDevelopmentFrostedOverlayTransitionHasZeroPerFrameRecompositionAndIdleInvalidations() {
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        settings.setUiAnimationsEnabled(true)
        var hallCompositions = 0
        ImageComposeScene(width = 960, height = 720) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    GameplayHallPageHost {
                        SideEffect { hallCompositions++ }
                        GameplayHallView(
                            onExit = { closePage(LauncherPage.GameplayHall) },
                            onOpenPage = { navigateTo(it) },
                            loadGames = { listOf(published, offline) },
                            initialDevUnlocked = false,
                        )
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
            assertFalse(earlyPng.contentEquals(midPng), "开启动画时毛玻璃锁定页入场应随时间推进")
            assertEquals(startCompositions, hallCompositions, "毛玻璃锁定页转场不能逐帧触发重组")

            for (frame in 16..45) {
                scene.render(frame * 16_000_000L).close()
            }
            assertFalse(scene.hasInvalidations(), "毛玻璃锁定页静置后不得残留持续动画调度")
        }
    }

    @Test
    fun hallDevUnlockTrackerUnitRules() {
        var now = 10_000L
        val tracker = HallDevUnlockTracker(requiredCount = 5, timeoutMillis = 4_000L, nowMillis = { now })

        // 连续按住只记 1 次，抬起后恢复计数。
        assertFalse(tracker.onQKeyDown())
        assertFalse(tracker.onQKeyDown())
        tracker.onQKeyUp()

        // 恰好在时间窗边界内累计到 5 次触发解锁。
        now += 4_000L
        assertFalse(tracker.onQKeyDown()); tracker.onQKeyUp()
        now += 100L
        assertFalse(tracker.onQKeyDown()); tracker.onQKeyUp()
        now += 100L
        assertFalse(tracker.onQKeyDown()); tracker.onQKeyUp()
        now += 100L
        assertTrue(tracker.onQKeyDown())
        tracker.onQKeyUp()

        // 解锁后自动重置，再次按 4 次不会触发。
        repeat(4) {
            now += 100L
            assertFalse(tracker.onQKeyDown())
            tracker.onQKeyUp()
        }
        // 超过时间窗 1ms 即重新从 1 开始计数。
        now += 4_001L
        assertFalse(tracker.onQKeyDown())
        tracker.onQKeyUp()

        // 失焦解除 keyHeld 但不要求立即再次抬起。
        assertFalse(tracker.onQKeyDown())
        tracker.onFocusLost()
        now += 100L
        assertFalse(tracker.onQKeyDown())
        tracker.onQKeyUp()

        // 非 Q 按键清空序列。
        tracker.onOtherKeyDown()
        repeat(4) {
            now += 100L
            assertFalse(tracker.onQKeyDown())
            tracker.onQKeyUp()
        }
        now += 100L
        assertTrue(tracker.onQKeyDown())
    }

    @Test
    fun singleOnlineGamePremiereAndAllTabSpotlightLayout() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var destination: LauncherPage? = null
        show(load = { listOf(published) }, onOpen = { destination = it })
        // 仅 1 个上架网游：推荐页呈现沉浸式旗舰首发主舞台 + 三格真实情报 Bento 卡，不显示原版模式。
        onNodeWithTag("hall-featured").assertIsDisplayed()
        onNodeWithTag("hall-featured-0").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        onNodeWithText("区服态势").assertIsDisplayed()
        onNodeWithText("玩法特性").assertIsDisplayed()
        onNodeWithText("持续上新").assertIsDisplayed()
        save("single-game-desktop.png")

        // 旗舰主舞台直接支持收藏，切到「全部玩法」展示全宽横版展卡与生态预告尾注。
        onNodeWithTag("hall-favorite-game-$gameId").performClick()
        onNodeWithTag("hall-category-all").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithText("更多创意网游正在路上").assertIsDisplayed()
        save("single-game-all.png")

        onNodeWithTag("hall-category-favorites").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(LauncherPage.GamePortal(gameId), destination) }
    }

    @Test
    fun singleOnlineGamePortraitPremiereStaysClean() = runDesktopComposeUiTest(width = 412, height = 844) {
        show(load = { listOf(published) })
        onNodeWithTag("hall-featured").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
        onNodeWithTag("hall-featured-open").assertIsDisplayed()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        save("single-game-portrait.png")
    }

    @Test
    fun multiOnlineGamesAdaptiveGridScalesCleanly() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val g3 = OnlineGame(
            "33333333-3333-3333-3333-333333333331",
            "深空要塞",
            "跨星系基地建设与实时舰队攻防对抗",
            available = true,
            servers = listOf(OnlineGameServer("s3", "一号星区", "127.0.0.1:5130", available = true, online = true, currentPlayers = 18, maxPlayers = 40)),
            portalUrl = "https://g3.example/portal",
            portalRevision = 1,
        )
        val g4 = OnlineGame(
            "44444444-4444-4444-4444-444444444441",
            "钢铁前线",
            "重装机甲阵地拉锯战，团队协作争夺核心矿脉",
            available = true,
            servers = listOf(OnlineGameServer("s4", "前线服", "127.0.0.1:5131", available = true, online = true, currentPlayers = 12, maxPlayers = 24)),
            portalUrl = "https://g4.example/portal",
            portalRevision = 1,
        )
        val g5 = OnlineGame(
            "55555555-5555-5555-5555-555555555551",
            "裂谷突围",
            "快节奏小队撤离与高风险资源争夺战",
            available = true,
            portalUrl = "https://g5.example/portal",
            portalRevision = 1,
        )
        val g6 = OnlineGame(
            "66666666-6666-6666-6666-666666666661",
            "群岛霸权",
            "海陆空立体登陆作战与制海权争夺",
            available = true,
            portalUrl = "https://g6.example/portal",
            portalRevision = 1,
        )
        var currentGames by mutableStateOf(listOf(published, offline, g3, g4))
        show(load = { currentGames })
        onNodeWithTag("hall-featured-0").assertIsDisplayed()
        onNodeWithTag("hall-featured-3").assertIsDisplayed()
        onNodeWithTag("hall-category-all").performClick()
        // 4 个网游走 2x2 对称双列网格，避免 3+1 孤卡。
        val b1 = onNodeWithTag("hall-card-game-$gameId").fetchSemanticsNode().boundsInRoot
        val b2 = onNodeWithTag("hall-card-game-$otherId").fetchSemanticsNode().boundsInRoot
        val b3 = onNodeWithTag("hall-card-game-${g3.appId}").fetchSemanticsNode().boundsInRoot
        assertEquals(b1.top, b2.top, 2f)
        assertTrue(b3.top > b1.bottom)
        save("multi-4-games-desktop.png")

        // 切到 6 个网游后自动升级为三列画廊网格。
        runOnIdle { currentGames = listOf(published, offline, g3, g4, g5, g6) }
        onNodeWithTag("hall-refresh").performClick()
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag("hall-card-game-${g6.appId}").fetchSemanticsNodes().isNotEmpty()
        }
        val m1 = onNodeWithTag("hall-card-game-$gameId").fetchSemanticsNode().boundsInRoot
        val m2 = onNodeWithTag("hall-card-game-$otherId").fetchSemanticsNode().boundsInRoot
        val m3 = onNodeWithTag("hall-card-game-${g3.appId}").fetchSemanticsNode().boundsInRoot
        assertEquals(m1.top, m2.top, 2f)
        assertEquals(m2.top, m3.top, 2f)
        save("multi-6-games-desktop.png")
    }

    private fun language(code: String) {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$code.toml").readText())
    }

    private fun ComposeUiTest.show(
        onExit: () -> Unit = {},
        onOpen: (LauncherPage) -> Unit = {},
        load: suspend () -> List<OnlineGame> = { listOf(published, offline) },
        refresh: suspend (String) -> OnlineGame? = { published },
        loggedIn: Boolean = true,
        onLogin: () -> Unit = {},
        devUnlocked: Boolean = true,
        devUnlockClockMillis: () -> Long = { System.currentTimeMillis() },
    ) {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    GameplayHallView(
                        onExit = onExit,
                        onOpenPage = onOpen,
                        loadGames = load,
                        refreshGame = refresh,
                        loggedIn = loggedIn,
                        onLogin = onLogin,
                        initialDevUnlocked = devUnlocked,
                        devUnlockClockMillis = devUnlockClockMillis,
                    )
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
