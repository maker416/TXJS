/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.Net
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.setUiAnimationsEnabled
import io.github.rwpp.widget.uiAnimationsEnabled
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.bounceClick
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class LauncherTransitionMotionTest {
    private lateinit var settings: Settings

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(autoCheckUpdate = false, enableAnimations = true, language = "zh")
        appKoin = startKoin {
            modules(module {
                single { settings }
                single<Net> { stub(Net::class.java) }
                single<AppContext> { stub(AppContext::class.java) }
                single<GameI18nResolver> { stub(GameI18nResolver::class.java) }
            })
        }.koin
        koinInit = true
        logger = LoggerFactory.getLogger("LauncherTransitionMotionTest")
        AccountSession.resetForTests()
        AccountSession.networkEnabled = false
        FriendsSession.clear()
        UI.latestVersionProfile = LatestVersionProfile("0.0.0", "", false)
        ArtThemeController.apply(null)
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        SwingUtilities.invokeAndWait {
            UI.showAccountView = false
            UI.showFriendsView = false
            resetNavigation(LauncherPage.MainMenu)
        }
    }

    @AfterTest
    fun teardown() {
        SwingUtilities.invokeAndWait {
            UI.showAccountView = false
            UI.showFriendsView = false
            resetNavigation(LauncherPage.MainMenu)
        }
        ArtThemeController.apply(null)
        UI.latestVersionProfile = null
        FriendsSession.clear()
        AccountSession.resetForTests()
        stopKoin()
        koinInit = false
        reloadI18n()
    }

    @Composable
    private fun MainMenuWithSinglePlayerHost(
        onMenuComposition: () -> Unit = {},
        onPanelComposition: () -> Unit = {},
        missionContent: @Composable () -> Unit = {},
    ) {
        KoinContext(appKoin) {
            RWPPTheme(default = true) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    val animated = uiAnimationsEnabled()
                    val showMainMenu = launcherPage == LauncherPage.MainMenu
                    val mainMenuTransition = remember { MainMenuTransitionMemory() }
                    val enterStyle =
                        if (showMainMenu && !mainMenuTransition.lastShowMainMenu) MainMenuTransitionStyle.DepthZoom
                        else mainMenuTransition.enterStyle
                    val exitStyle =
                        if (!showMainMenu && mainMenuTransition.lastShowMainMenu) MainMenuTransitionStyle.DepthZoom
                        else mainMenuTransition.exitStyle
                    SideEffect {
                        mainMenuTransition.lastShowMainMenu = showMainMenu
                        mainMenuTransition.lastPage = launcherPage
                        mainMenuTransition.enterStyle = enterStyle
                        mainMenuTransition.exitStyle = exitStyle
                    }
                    Box(Modifier.fillMaxSize().background(Color(24, 30, 27))) {
                        val latestOnMenuComposition = rememberUpdatedState(onMenuComposition)
                        val mainMenuInteractive = remember { { launcherPage == LauncherPage.MainMenu } }
                        val mainMenuContent = remember<@Composable () -> Unit> {
                            {
                                SideEffect { latestOnMenuComposition.value() }
                                LauncherMainMenu(
                                    multiplayer = { onEdt { navigateTo(LauncherPage.Multiplayer) } },
                                    singlePlayer = { onEdt { navigateTo(LauncherPage.SinglePlayer) } },
                                    settings = { onEdt { navigateTo(LauncherPage.Settings) } },
                                    mods = { onEdt { navigateTo(LauncherPage.Mods) } },
                                    resources = { onEdt { navigateTo(LauncherPage.ResourceCenter) } },
                                    gameplayHall = { onEdt { navigateTo(LauncherPage.GameplayHall) } },
                                    extension = { onEdt { navigateTo(LauncherPage.Extensions) } },
                                    resourceBrowser = { onEdt { navigateTo(LauncherPage.ResourceBrowser) } },
                                    esports = { onEdt { navigateTo(LauncherPage.Esports) } },
                                    openSourceInfo = { onEdt { navigateTo(LauncherPage.OpenSourceInfo) } },
                                    account = { UI.showAccountView = true },
                                    friends = { UI.showFriendsView = true },
                                )
                            }
                        }
                        MainMenuOverlayHost(
                            visible = showMainMenu,
                            isInteractive = mainMenuInteractive,
                            enterStyle = enterStyle,
                            exitStyle = exitStyle,
                            content = mainMenuContent,
                        )
                        TacticalPanelPageHost(LauncherPage.SinglePlayer) {
                            SideEffect { onPanelComposition() }
                            Box(Modifier.fillMaxSize().testTag("single-player-host")) {
                                SinglePlayerView(
                                    onExit = { onEdt { closePage(LauncherPage.SinglePlayer) } },
                                    onMission = { onEdt { navigateTo(LauncherPage.Mission) } },
                                    onSurvival = { onEdt { navigateTo(LauncherPage.Survival) } },
                                    onSkirmish = {},
                                    onSandbox = {},
                                    onSavedGames = {},
                                )
                            }
                        }
                        missionContent()
                    }
                }
            }
        }
    }

    @Test
    fun mainMenuDepthTransitionAndTacticalPanelAnimateWithoutPerFrameRecomposition() {
        var menuCompositions = 0
        var panelCompositions = 0
        ImageComposeScene(width = 1280, height = 720) {
            MainMenuWithSinglePlayerHost(
                onMenuComposition = { menuCompositions++ },
                onPanelComposition = { panelCompositions++ },
            )
        }.use { scene ->
            fun settle(startMs: Long) {
                repeat(5) { step ->
                    Snapshot.sendApplyNotifications()
                    scene.render((startMs + step * 16L) * 1_000_000L).close()
                }
            }

            settle(0L)
            assertFalse(scene.hasInvalidations())
            val initialMenuCompositions = menuCompositions

            SwingUtilities.invokeAndWait { navigateTo(LauncherPage.SinglePlayer) }
            Snapshot.sendApplyNotifications()
            scene.render(100_000_000L).close()
            scene.render(116_000_000L).close()
            val earlyEnter = scene.render(132_000_000L).use { it.encodeToData()!!.bytes }
            val startMenuCompositions = menuCompositions
            val startPanelCompositions = panelCompositions
            for (frame in 1..10) {
                scene.render((132L + frame * 16L) * 1_000_000L).close()
            }
            val midEnter = scene.render(300_000_000L).use { it.encodeToData()!!.bytes }
            assertFalse(earlyEnter.contentEquals(midEnter), "DepthZoom 与战术面板入场应产生平滑动画帧")
            assertEquals(startMenuCompositions, menuCompositions, "主菜单退场动画不能逐帧触发重组")
            assertEquals(startPanelCompositions, panelCompositions, "战术面板入场动画不能逐帧触发重组")

            for (frame in 1..25) {
                scene.render((316L + frame * 16L) * 1_000_000L).close()
            }
            assertFalse(scene.hasInvalidations(), "进场完成后不得残留持续动画调度")

            SwingUtilities.invokeAndWait { navigateBack() }
            Snapshot.sendApplyNotifications()
            scene.render(750_000_000L).close()
            scene.render(766_000_000L).close()
            val earlyReturn = scene.render(782_000_000L).use { it.encodeToData()!!.bytes }
            val returnMenuStart = menuCompositions
            val returnPanelStart = panelCompositions
            for (frame in 1..9) {
                scene.render((782L + frame * 16L) * 1_000_000L).close()
            }
            val midReturn = scene.render(930_000_000L).use { it.encodeToData()!!.bytes }
            assertFalse(earlyReturn.contentEquals(midReturn), "返回主菜单时景深推回与面板收束应产生平滑动画帧")
            assertEquals(returnMenuStart, menuCompositions, "主菜单推回动画不能逐帧触发重组")
            assertEquals(returnPanelStart, panelCompositions, "战术面板收束动画不能逐帧触发重组")

            for (frame in 1..25) {
                scene.render((946L + frame * 16L) * 1_000_000L).close()
            }
            assertFalse(scene.hasInvalidations())
            assertEquals(initialMenuCompositions, menuCompositions, "保活主菜单往返切页不得重建或重跑初始组合")

            settings.setUiAnimationsEnabled(false)
            settle(1400L)
            SwingUtilities.invokeAndWait { navigateTo(LauncherPage.SinglePlayer) }
            settle(1500L)
            assertFalse(scene.hasInvalidations(), "关闭动画后进入面板不得请求动画帧")
        }
    }

    @Test
    fun resourceCenterTransitionAnimatesWithoutPerFrameRecompositionAndStopsWhenDisabled() {
        var resourceCompositions = 0
        ImageComposeScene(width = 1280, height = 720) {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    Box(Modifier.fillMaxSize().background(Color(24, 30, 27))) {
                        ResourceCenterPageHost {
                            SideEffect { resourceCompositions++ }
                            ResourceCenterView(
                                onExit = { closePage(LauncherPage.ResourceCenter) },
                                onForum = {},
                                onEsports = {},
                                onExtensions = {},
                            )
                        }
                    }
                }
            }
        }.use { scene ->
            fun settle(startMs: Long) {
                repeat(5) { step ->
                    Snapshot.sendApplyNotifications()
                    scene.render((startMs + step * 16L) * 1_000_000L).close()
                }
            }

            scene.render(0L).close()
            SwingUtilities.invokeAndWait { navigateTo(LauncherPage.ResourceCenter) }
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000L).close()
            scene.render(32_000_000L).close()
            val earlyPng = scene.render(48_000_000L).use { it.encodeToData()!!.bytes }
            val startCompositions = resourceCompositions
            for (frame in 4..14) {
                scene.render(frame * 16_000_000L).close()
            }
            val midPng = scene.render(240_000_000L).use { it.encodeToData()!!.bytes }
            assertFalse(earlyPng.contentEquals(midPng), "资源中心前景两级入场应随时间推进")
            assertEquals(startCompositions, resourceCompositions, "资源中心转场动画数值应只在 graphicsLayer 读取")

            for (frame in 16..45) {
                scene.render(frame * 16_000_000L).close()
            }
            assertFalse(scene.hasInvalidations(), "资源中心入场完成后不得残留调度")

            settings.setUiAnimationsEnabled(false)
            settle(800L)
            SwingUtilities.invokeAndWait { closePage(LauncherPage.ResourceCenter) }
            settle(900L)
            SwingUtilities.invokeAndWait { navigateTo(LauncherPage.ResourceCenter) }
            settle(1000L)
            assertFalse(scene.hasInvalidations(), "关闭动画后进出资源中心不得调度动画帧")
        }
    }

    @Test
    fun tacticalPanelForwardAndBackwardInteractionStaysResponsive() = runDesktopComposeUiTest(width = 1280, height = 720) {
        mainClock.autoAdvance = false
        setContent {
            MainMenuWithSinglePlayerHost {
                TacticalPanelPageHost(LauncherPage.Mission) {
                    Box(Modifier.fillMaxSize().testTag("mission-stub")) {
                        ExitButton { onEdt { closePage(LauncherPage.Mission) } }
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher-card-singlePlayer").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeByFrame()
        runOnIdle { assertEquals(LauncherPage.SinglePlayer, launcherPage) }

        // 主菜单仍在退场动画期间，单人子面板立即可点击进入战役页。
        onNode(hasText(readI18n("menu.singlePlayer.mission"))).performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeByFrame()
        runOnIdle { assertEquals(LauncherPage.Mission, launcherPage) }

        // 战役页关闭回到单人面板；单人面板在退场中绝不能吞掉战役关闭操作。
        onNode(hasContentDescription(readI18n("common.close")) and hasAnyAncestor(hasTestTag("mission-stub")))
            .performTouchInput { click() }
        mainClock.advanceTimeByFrame()
        runOnIdle { assertEquals(LauncherPage.SinglePlayer, launcherPage) }

        // 从单人面板返回主菜单，并等待退场动画彻底卸载子节点。
        onNode(hasContentDescription(readI18n("common.close")) and hasAnyAncestor(hasTestTag("single-player-host")))
            .performTouchInput { click() }
        mainClock.advanceTimeBy(600)
        runOnIdle { assertEquals(LauncherPage.MainMenu, launcherPage) }
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        onNodeWithTag("mission-stub").assertDoesNotExist()
    }

    @Test
    fun bounceClickAndLazyColumnScrollbarDoNotRecomposePerFrameOrRunPhantomOpenAnimation() {
        var buttonParentCompositions = 0
        var buttonContentCompositions = 0
        var clicks = 0
        ImageComposeScene(width = 320, height = 160) {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    SideEffect { buttonParentCompositions++ }
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .size(180.dp, 64.dp)
                                .bounceClick { clicks++ },
                        ) {
                            SideEffect { buttonContentCompositions++ }
                            Text("Action")
                        }
                    }
                }
            }
        }.use { scene ->
            repeat(5) { step ->
                Snapshot.sendApplyNotifications()
                scene.render(step * 16_000_000L).close()
            }
            assertFalse(scene.hasInvalidations())
            val startParent = buttonParentCompositions
            val startContent = buttonContentCompositions

            scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 30f))
            Snapshot.sendApplyNotifications()
            scene.render(100_000_000L).close()
            for (frame in 1..10) {
                scene.render((100L + frame * 16L) * 1_000_000L).close()
            }
            assertEquals(startParent, buttonParentCompositions, "bounceClick 按压期间不得触发父组件逐帧重组")
            assertEquals(startContent, buttonContentCompositions, "bounceClick 按压期间不得触发按钮内容逐帧重组")

            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 30f))
            Snapshot.sendApplyNotifications()
            for (frame in 0..15) {
                scene.render((280L + frame * 16L) * 1_000_000L).close()
            }
            assertEquals(1, clicks)
            assertEquals(startParent, buttonParentCompositions, "bounceClick 释放回弹期间不得触发父组件重组")
            assertEquals(startContent, buttonContentCompositions, "bounceClick 释放回弹期间不得触发按钮内容重组")
            assertFalse(scene.hasInvalidations())
        }

        var listContentCompositions = 0
        ImageComposeScene(width = 400, height = 400) {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                        val listState = rememberLazyListState()
                        LazyColumnScrollbar(listState = listState, alwaysShowScrollBar = true) {
                            SideEffect { listContentCompositions++ }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                            ) {
                                items(3) { index ->
                                    Box(Modifier.fillMaxWidth().height(36.dp)) { Text("Item $index") }
                                }
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            repeat(6) { step ->
                Snapshot.sendApplyNotifications()
                scene.render(step * 16_000_000L).close()
            }
            // 短列表首次测量前不得因 totalItemsCount == 0 启动 1000ms 幽灵指示器动画。
            assertFalse(scene.hasInvalidations(), "可完整容纳的列表打开后不应触发 1000ms 幽灵指示器动画")
            val settledCompositions = listContentCompositions
            scene.render(200_000_000L).close()
            assertEquals(settledCompositions, listContentCompositions)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        when (method.name) {
            "getKoin" -> appKoin
            "isDesktop" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                String::class.java -> ""
                else -> null
            }
        }
    } as T
}

private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block()
    else SwingUtilities.invokeAndWait(block)
}
