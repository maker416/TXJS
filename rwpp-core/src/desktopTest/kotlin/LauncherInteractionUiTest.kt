/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.ExitButton
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 真实触摸命中关闭按钮，并在退出动画保留旧页时验证页面/原生弹窗层级。 */
@OptIn(ExperimentalTestApi::class)
class LauncherInteractionUiTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable("[common]\nclose = \"关闭\"")
        SwingUtilities.invokeAndWait {
            UI.showFriendsView = false
            UI.showAccountView = false
            resetNavigation(LauncherPage.SinglePlayer)
        }
    }

    @AfterTest
    fun cleanup() {
        SwingUtilities.invokeAndWait {
            UI.showFriendsView = false
            UI.showAccountView = false
            resetNavigation(LauncherPage.MainMenu)
        }
        stopKoin()
        reloadI18n()
        setI18nOverride(null)
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        KoinContext(appKoin) { MaterialTheme(content = content) }
    }

    private fun ComposeUiTest.touchCloseIn(page: String) {
        onNode(hasContentDescription("关闭") and hasAnyAncestor(hasTestTag(page)))
            .performTouchInput { click() }
    }

    @Test
    fun pageInsideScaffoldCanCloseWhileOuterPageIsStillExiting() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var oldPageCloses = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    // 与 App 相同：任务页位于 Scaffold 内，单人入口页在它的外层且后绘制。
                    Scaffold(containerColor = Color.Transparent) {
                        LauncherPageHost(LauncherPage.Mission, EnterTransition.None, fadeOut(tween(1000))) {
                            Box(Modifier.fillMaxSize().testTag("mission")) {
                                ExitButton {
                                    if (SwingUtilities.isEventDispatchThread()) closePage(LauncherPage.Mission)
                                    else SwingUtilities.invokeAndWait { closePage(LauncherPage.Mission) }
                                }
                            }
                        }
                    }
                    LauncherPageHost(LauncherPage.SinglePlayer, EnterTransition.None, fadeOut(tween(1000))) {
                        Box(Modifier.fillMaxSize().testTag("single")) {
                            ExitButton { oldPageCloses++ }
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { navigateTo(LauncherPage.Mission) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("single").assertExists()
        touchCloseIn("mission")
        runOnIdle {
            assertEquals(LauncherPage.SinglePlayer, launcherPage)
            assertEquals(0, oldPageCloses)
        }
    }

    @Test
    fun closingAccountLetsFriendsCloseBeforeAccountExitFinishes() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var friendsOpen by mutableStateOf(true)
        var accountOpen by mutableStateOf(true)
        var accountCloses = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    LauncherOverlayHost(friendsOpen, { friendsOpen && !accountOpen }, EnterTransition.None, fadeOut(tween(1000))) {
                        Box(Modifier.fillMaxSize().testTag("friends")) {
                            ExitButton { friendsOpen = false }
                        }
                    }
                    LauncherOverlayHost(accountOpen, { accountOpen }, EnterTransition.None, fadeOut(tween(1000))) {
                        Box(Modifier.fillMaxSize().testTag("account")) {
                            ExitButton { accountCloses++; accountOpen = false }
                        }
                    }
                }
            }
        }
        waitForIdle()
        touchCloseIn("account")
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("account").assertExists()
        touchCloseIn("friends")
        runOnIdle {
            assertFalse(friendsOpen)
            assertEquals(1, accountCloses)
        }
    }

    @Test
    fun leavingPageRemovesItsNativeDialogImmediately() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var oldPageOpen by mutableStateOf(true)
        var incomingClicks = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    LauncherOverlayHost(!oldPageOpen, { !oldPageOpen }, EnterTransition.None, fadeOut(tween(1000))) {
                        Box(Modifier.size(120.dp).testTag("incoming").clickable { incomingClicks++ })
                    }
                    LauncherOverlayHost(oldPageOpen, { oldPageOpen }, EnterTransition.None, fadeOut(tween(1000))) {
                        Box(Modifier.fillMaxSize().testTag("old")) {
                            AnimatedAlertDialog(true, {}) { Text("old page dialog") }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(250)
        waitForIdle()
        onNodeWithText("old page dialog").assertExists()
        runOnIdle { oldPageOpen = false }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("old").assertExists()
        onNodeWithText("old page dialog").assertDoesNotExist()
        onAllNodes(isRoot()).assertCountEquals(1)
        onNodeWithTag("incoming").performTouchInput { click() }
        assertEquals(1, incomingClicks)
    }

    @Test
    fun completedDismissCannotLeaveInvisibleNativeWindow() = runDesktopComposeUiTest {
        var dismissals = 0
        var backgroundClicks = 0
        setContent {
            TestTheme {
                Box(Modifier.size(120.dp).testTag("background").clickable { backgroundClicks++ })
                // 故意保留调用方的 visible：共享弹窗也必须移除已结束的原生窗口。
                AnimatedAlertDialog(true, { dismissals++ }) { close ->
                    Box(Modifier.size(120.dp).testTag("dialog")) { ExitButton(close) }
                }
            }
        }
        waitForIdle()
        touchCloseIn("dialog")
        waitForIdle()
        onNodeWithTag("dialog").assertDoesNotExist()
        onAllNodes(isRoot()).assertCountEquals(1)
        assertEquals(1, dismissals)
        onNodeWithTag("background").performTouchInput { click() }
        assertEquals(1, backgroundClicks)
    }
}
