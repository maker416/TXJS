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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
        val list = onNodeWithTag("hall-list").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val details = onNodeWithTag("hall-details").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(list.right <= details.left)
        assertTrue(details.width > list.width)
        onNodeWithTag("hall-description").assertTextEquals(published.description)
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
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        runOnIdle { assertNull(destination) }

        onNodeWithTag("hall-search").performTextReplacement("nothing-matches")
        onNodeWithText("没有找到匹配的玩法，试试其他关键词或筛选。").assertIsDisplayed()
        onNodeWithTag("hall-details").assertDoesNotExist()
        onNodeWithTag("hall-reset").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()

        onNodeWithTag("hall-filter-status").performClick()
        onNode(hasText("可进入") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
    }

    @Test
    fun phoneLandscapeShowsListAndReadableDetailsTogether() = runDesktopComposeUiTest(width = 960, height = 432) {
        var closed = 0
        var destination: LauncherPage? = null
        show(onExit = { closed++ }, onOpen = { destination = it }, load = { listOf(published, offline.copy(available = true)) })
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-description").assertIsDisplayed()
        onNodeWithTag("hall-open").assertIsDisplayed()
        save("landscape.png")
        onNodeWithTag("hall-card-game-$otherId").performClick()
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        runOnIdle { assertNull(destination) }
        onNodeWithTag("hall-open").performClick()
        runOnIdle { assertEquals(LauncherPage.GamePortal(otherId), destination) }
        onNodeWithTag("hall-server-closed").assertDoesNotExist()
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun smallLandscapeKeepsBothPanesAndTheGameEntryReachable() = runDesktopComposeUiTest(width = 568, height = 320) {
        show()
        val list = onNodeWithTag("hall-list").assertIsDisplayed()
        val details = onNodeWithTag("hall-details").assertIsDisplayed()
        assertTrue(list.fetchSemanticsNode().boundsInRoot.right <= details.fetchSemanticsNode().boundsInRoot.left)
        onNodeWithTag("hall-open").assertIsDisplayed().assertIsEnabled()
        list.performScrollToNode(hasTestTag("hall-card-game-$otherId"))
        onNodeWithTag("hall-card-game-$otherId").performClick()
        onNodeWithTag("hall-description").performScrollTo().assertIsDisplayed().assertTextEquals(offline.description)
    }

    @Test
    fun portraitReturnsFromFullWidthDetailsToTheSameListPosition() = runDesktopComposeUiTest(width = 412, height = 844) {
        language("en")
        var exits = 0
        val games = (1..35).map { number ->
            published.copy(appId = "11111111-1111-1111-1111-${number.toString().padStart(12, '0')}", name = "Game $number")
        }
        show(onExit = { exits++ }, load = { games })
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-search").assertIsDisplayed()
        onNodeWithTag("hall-details").assertDoesNotExist()
        save("portrait-en.png")
        onNodeWithTag("hall-search").performTextInput("Game")
        val tag = "hall-card-game-${games.last().appId}"
        onNodeWithTag("hall-list").performScrollToNode(hasTestTag(tag))
        val row = onNodeWithTag(tag)
        val rowTop = row.fetchSemanticsNode().boundsInRoot.top
        row.assertIsDisplayed().performClick()
        onNodeWithTag("hall-list").assertDoesNotExist()
        onNodeWithTag("hall-description").assertIsDisplayed().assertTextEquals(published.description)
        save("portrait-detail-en.png")
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(0, exits) }
        onNodeWithTag("hall-search").assertTextContains("Game")
        assertEquals(rowTop, row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 2f)
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun hallNavigationRestoresSelectionAndRespectsLiveAnimationToggle() = runDesktopComposeUiTest(width = 960, height = 640) {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    if (launcherPage == LauncherPage.MainMenu) Text("home")
                    GameplayHallPageHost {
                        GameplayHallView(
                            onExit = { closePage(LauncherPage.GameplayHall) },
                            onOpenPage = { navigateTo(it) },
                            loadGames = { listOf(published, offline.copy(available = true)) },
                            loggedIn = true,
                        )
                    }
                }
            }
        }
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        onNodeWithTag("hall-card-game-$otherId").performClick()
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        onNodeWithTag("hall-open").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle { assertEquals(LauncherPage.GamePortal(otherId), launcherPage) }
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
        runOnIdle { navigateBack() }
        onNodeWithTag("hall-card-game-$otherId").assertIsSelected()
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        onNodeWithTag("hall-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("home").assertIsDisplayed()
        mainClock.autoAdvance = false
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
    fun portalRequiresLoginAndResumesIntoTheWebsiteAfterAuthentication() = runDesktopComposeUiTest(width = 960, height = 640) {
        var destination: LauncherPage? = null
        var loggedIn by mutableStateOf(false)
        var login = 0
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                GameplayHallView(
                    onExit = {},
                    onOpenPage = { destination = it },
                    loadGames = { listOf(published, offline.copy(available = true)) },
                    loggedIn = loggedIn,
                    onLogin = {
                        login++
                        UI.showAccountView = true
                    },
                )
            }
        }
        onNodeWithTag("hall-card-game-$otherId").performClick()
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        runOnIdle {
            assertEquals(0, login)
            assertNull(destination)
        }
        onNodeWithTag("hall-open").performClick()
        runOnIdle {
            assertEquals(1, login)
            assertNull(destination)
            loggedIn = true
        }
        waitUntil { destination != null }
        runOnIdle {
            assertEquals(LauncherPage.GamePortal(otherId), destination)
            assertFalse(UI.showAccountView)
        }
        onNodeWithTag("hall-server-main").assertDoesNotExist()
    }

    @Test
    fun directoryFailureAndEmptyDirectoryNeverInventOnlineOrClassicGames() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var fail by mutableStateOf(true)
        show(load = { if (fail) error("unavailable") else emptyList() })
        onNodeWithTag("hall-directory-status").assertTextContains("暂时无法获取网游目录", substring = true)
        onNodeWithTag("hall-details").assertDoesNotExist()
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
        onNodeWithTag("hall-details").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        save("empty-directory-desktop.png")
    }

    @Test
    fun missingPortalKeepsPublicCardButCannotOpenNativeServerSelection() = runDesktopComposeUiTest(width = 960, height = 640) {
        var destination: LauncherPage? = null
        show(load = { listOf(published, offline.copy(portalUrl = "", portalRevision = 0)) }, onOpen = { destination = it })
        onNodeWithTag("hall-card-game-$otherId").assertIsDisplayed().assertIsEnabled().performClick()
        onNodeWithTag("hall-description").assertTextEquals(offline.description)
        onNodeWithTag("hall-open").assertIsNotEnabled()
        runOnIdle { assertNull(destination) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun hallTransitionAnimatesWithoutPerFrameRecompositionAndStopsCleanlyWhenDisabled() {
        var secondGamePos = Offset.Zero
        var favoriteGamePos = Offset.Zero
        var favoritesCategoryPos = Offset.Zero
        runDesktopComposeUiTest(width = 960, height = 720) {
            setContent {
                UiMotionTheme(darkColorScheme(), Typography()) {
                    GameplayHallView({}, {}, loadGames = { listOf(published, offline) })
                }
            }
            waitForIdle()
            val secondGame = onNodeWithTag("hall-card-game-$otherId")
            secondGamePos = secondGame.fetchSemanticsNode().boundsInRoot.center
            secondGame.performClick()
            favoriteGamePos = onNodeWithTag("hall-favorite-game-$otherId").fetchSemanticsNode().boundsInRoot.center
            favoritesCategoryPos = onNodeWithTag("hall-category-favorites").fetchSemanticsNode().boundsInRoot.center
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

            clickAt(secondGamePos, 850L)
            assertFalse(scene.hasInvalidations(), "关闭动画时切换游戏不得请求动画帧")
            clickAt(favoriteGamePos, 950L)
            assertFalse(scene.hasInvalidations(), "关闭动画时点击收藏不得请求动画帧")
            clickAt(favoritesCategoryPos, 1050L)
            assertFalse(scene.hasInvalidations(), "关闭动画时切换分类不得请求动画帧")
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

    @Test
    fun opensDirectlyWithoutUnderDevelopmentOverlay() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var loads = 0
        show(
            load = {
                loads++
                listOf(published, offline)
            },
        )
        waitUntil(timeoutMillis = 3_000) { loads >= 1 }
        waitForIdle()
        onNodeWithTag("hall-dev-overlay").assertDoesNotExist()
        onNodeWithTag("hall-dev-notice").assertDoesNotExist()
        onNodeWithTag("hall-dev-badge").assertDoesNotExist()
        onNodeWithTag("hall-dev-back").assertDoesNotExist()
        onNodeWithTag("hall-details").assertIsDisplayed()
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed()
    }

    @Test
    fun singleGameAutomaticallyShowsFullDetailsAndCanBeFavorited() = runDesktopComposeUiTest(width = 1280, height = 720) {
        show(load = { listOf(published) })
        onNodeWithTag("hall-card-game-$gameId").assertIsDisplayed().assertIsSelected()
        onNodeWithTag("hall-description").assertIsDisplayed().assertTextEquals(published.description)
        onNodeWithTag("hall-open").assertIsDisplayed().assertIsEnabled()
        save("single-game-desktop.png")

        onNodeWithTag("hall-favorite-game-$gameId").performClick()
        onNodeWithTag("hall-category-favorites").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertIsSelected()
        onNodeWithTag("hall-favorite-game-$gameId").performClick()
        onNodeWithTag("hall-card-game-$gameId").assertDoesNotExist()
        onNodeWithTag("hall-details").assertDoesNotExist()
    }

    @Test
    fun longDescriptionCanScrollToItsLastParagraphWithoutMovingTheList() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val paragraphs = listOf(
            "边境的最后一座中继站刚刚恢复通信。你和队友将从一辆工程车起步，在群山之间找到矿脉、铺设第一条补给线，让失去联络的据点重新亮起灯光。",
            "峡谷里有两条通往港口的路。沿河谷推进可以很快拿下船坞，但车队会暴露在高地火力下。也可以让侦察机先探明山路，再用运输机把工程车送过河，在敌军身后建立维修站。",
            "每周轮换的地图会改变起始资源和撤离点，给同一支小队留下不同的解法。",
        ) + (4..60).map { number -> "第 ${number} 段。占领前哨后，运输车队会沿已修复的道路补给下一处据点。" }
        val description = paragraphs.joinToString("\n\n")
        val firstDescription = List(40) { published.description }.joinToString("\n\n")
        show(load = { listOf(published.copy(description = firstDescription), offline.copy(description = description)) })
        val firstRow = onNodeWithTag("hall-card-game-$gameId")
        val rowTop = firstRow.fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("hall-card-game-$otherId").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        val body = onNodeWithTag("hall-description").assertTextEquals(description)
        save("desktop-long-description.png")
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val lastLine = layout.lineCount - 1
        val lastOffset = layout.getLineEnd(lastLine, visibleEnd = true)
        val measurements = "lines=${layout.lineCount}, size=${layout.size}, " +
            "paragraph=${layout.multiParagraph.width}×${layout.multiParagraph.height}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
            "lastOffset=$lastOffset/${description.length}"
        assertEquals(description.length, lastOffset, measurements)
        assertFalse(layout.isLineEllipsized(lastLine), measurements)
        val details = onNodeWithTag("hall-details")
        details.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, layout.size.height.toFloat() + 2000f) }
        val scroll = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.maxValue() > 0f)
        assertEquals(scroll.maxValue(), scroll.value(), 1f)
        val finalLineY = body.fetchSemanticsNode().positionInRoot.y + layout.getLineBottom(lastLine)
        val viewport = details.fetchSemanticsNode().boundsInRoot
        assertTrue(finalLineY in viewport.top..(viewport.bottom + 1f), "$measurements, lastLineY=$finalLineY, viewport=$viewport")
        assertEquals(rowTop, firstRow.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top)
        firstRow.performClick()
        val nextScroll = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(nextScroll.maxValue() > 0f)
        assertEquals(0f, nextScroll.value())
        onNodeWithTag("hall-description").assertTextEquals(firstDescription)
    }

    @Test
    fun refreshKeepsSelectedGameByAppIdAndFallsBackWhenRemoved() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val updated = offline.copy(description = "目录刷新后的游戏介绍")
        val third = published.copy(appId = "33333333-3333-3333-3333-333333333333", name = "深空要塞")
        var directory = listOf(published, offline, third)
        var fail = false
        show(load = { if (fail) error("directory down") else directory })
        onNodeWithTag("hall-card-game-$otherId").performClick()
        runOnIdle { directory = listOf(third, published, updated) }
        onNodeWithTag("hall-refresh").performClick()
        onNodeWithTag("hall-description").assertTextEquals(updated.description)
        onNodeWithTag("hall-card-game-$otherId").assertIsSelected()

        runOnIdle { fail = true }
        onNodeWithTag("hall-refresh").performClick()
        onNodeWithTag("hall-details").assertDoesNotExist()
        onNodeWithTag("hall-directory-status").assertTextContains("暂时无法获取网游目录", substring = true)
        runOnIdle { fail = false }
        onNodeWithTag("hall-refresh").performClick()
        onNodeWithTag("hall-card-game-$otherId").assertIsSelected()
        onNodeWithTag("hall-description").assertTextEquals(updated.description)

        runOnIdle { directory = listOf(published) }
        onNodeWithTag("hall-refresh").performClick()
        onNodeWithTag("hall-card-game-$otherId").assertDoesNotExist()
        onNodeWithTag("hall-card-game-$gameId").assertIsSelected()
        onNodeWithTag("hall-description").assertTextEquals(published.description)
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
