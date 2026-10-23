/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.ui.AccountPrimaryButton
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.platform.BackHandler
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import javax.swing.SwingUtilities
import java.lang.reflect.Proxy
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AccountDialogInteractionTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin
    }

    @AfterTest
    fun cleanup() { stopKoin() }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        KoinContext(appKoin) { MaterialTheme(content = content) }
    }

    private fun sendEsc() {
        runBlocking { KeyboardEvent(0x1B).broadcast() }
        SwingUtilities.invokeAndWait { }
    }

    @Test
    fun ordinaryNestedDialogDoesNotInheritCardConstraints() = runDesktopComposeUiTest(width = 640, height = 480) {
        setContent {
            TestTheme {
                Box(Modifier.size(80.dp)) {
                    AnimatedAlertDialog(true, {}) {
                        Box(Modifier.size(240.dp).testTag("dialog"))
                    }
                }
            }
        }
        onNodeWithTag("dialog").assertWidthIsEqualTo(240.dp).assertHeightIsEqualTo(240.dp)
    }

    @Test
    fun busyInlineDialogBlocksOutsidePointerAndBackAndRetainsKeyboardFocus() = runDesktopComposeUiTest {
        var enabled by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var pageBacks = 0
        var clicks = 0
        var dismissed = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler(true) { pageBacks++ }
                    Text("page", Modifier.testTag("page").clickable { clicks++ })
                    AnimatedAlertDialog(shown, { dismissed++; shown = false }, enabled, inlineDesktop = true) {
                        Box(Modifier.size(180.dp).testTag("dialog"))
                    }
                }
            }
        }
        onNodeWithTag("page").performTouchInput { click(Offset(2f, 2f)) }
        sendEsc()
        runOnIdle {
            assertEquals(0, clicks)
            assertEquals(0, pageBacks)
            assertEquals(0, dismissed)
            enabled = true
        }
        // 弹层自己的焦点必须能接住实际键盘事件，不能依赖底层页面仍然获焦。
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        waitUntil { dismissed == 1 }
        sendEsc()
        runOnIdle { assertEquals(1, pageBacks) }
    }

    @Test
    fun inlineDialogOutsideTapDismissesButInsideTapDoesNot() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var dismissed = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    AnimatedAlertDialog(shown, { dismissed++; shown = false }, inlineDesktop = true) {
                        Box(Modifier.size(180.dp).testTag("dialog"))
                    }
                }
            }
        }
        onNodeWithTag("dialog").performTouchInput { click() }
        runOnIdle { assertEquals(0, dismissed) }
        onRoot().performTouchInput { click(Offset(2f, 2f)) }
        waitForIdle()
        waitUntil { dismissed == 1 }
    }

    @Test
    fun repeatedEscapeDuringExitDoesNotCloseUnderlyingPage() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var pageBacks = 0
        var dismissed = 0
        var actionClicks = 0
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler(true) { pageBacks++ }
                    AnimatedAlertDialog(shown, { dismissed++; shown = false }, inlineDesktop = true) {
                        Text("action", Modifier.size(180.dp).clickable { actionClicks++ })
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        sendEsc()
        mainClock.advanceTimeByFrame()
        sendEsc()
        onRoot().performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(0, pageBacks)
            assertEquals(0, actionClicks)
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        runOnIdle { assertEquals(1, dismissed) }
        sendEsc()
        runOnIdle { assertEquals(1, pageBacks) }
    }

    @Test
    fun nativeDialogCannotActivateFocusedButtonDuringExit() = runDesktopComposeUiTest {
        var dismiss: () -> Unit = {}
        var actionClicks = 0
        var shown by mutableStateOf(true)
        setContent {
            TestTheme {
                AnimatedAlertDialog(shown, { shown = false }) { close ->
                    val focus = remember { FocusRequester() }
                    SideEffect { dismiss = close }
                    Text("action", Modifier.focusRequester(focus).clickable { actionClicks++ })
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        onNodeWithText("action").assertIsFocused()
        mainClock.autoAdvance = false
        runOnIdle { dismiss() }
        mainClock.advanceTimeByFrame()
        onNodeWithText("action").performKeyInput { pressKey(Key.Enter); pressKey(Key.Tab); pressKey(Key.Enter) }
        runOnIdle { assertEquals(0, actionClicks) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("action").assertDoesNotExist()
    }

    @Test
    fun accountTextFieldPreservesSelectionAndResynchronizesExternalValue() = runDesktopComposeUiTest {
        var value by mutableStateOf("abcd")
        setContent {
            TestTheme {
                CompositionLocalProvider(LocalAccountAuthDialogEscape provides true) {
                    RWSingleOutlinedTextField("account", value, onValueChange = { value = it })
                }
            }
        }
        val field = onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange(1, 3))
        field.performTextInput("X")
        runOnIdle { assertEquals("aXd", value) }
        field.assertTextContains("aXd")
        runOnIdle { value = "external" }
        field.assertTextContains("external")
        field.performTextInput("!")
        runOnIdle { assertEquals("external!", value) }
    }

    @Test
    fun desktopPrimaryButtonSupportsKeyboardActivationAndDisabledSemantics() = runDesktopComposeUiTest {
        appKoin.loadModules(listOf(module {
            single<AppContext> {
                Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, _ ->
                    when (method.name) { "isDesktop" -> true; "isAndroid" -> false; else -> null }
                } as AppContext
            }
        }))
        var clicks = 0
        var enabled by mutableStateOf(true)
        setContent {
            TestTheme {
                val focus = remember { FocusRequester() }
                AccountPrimaryButton("submit", { clicks++ }, Modifier.focusRequester(focus).testTag("submit"), enabled)
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        onNodeWithTag("submit").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, clicks); enabled = false }
        onNodeWithTag("submit").assertIsNotEnabled()
    }

    @Test
    fun unfilteredTextFieldEditPreservesImeCompositionAndReversedSelection() {
        val composing = TextFieldValue("中文拼音", TextRange(4, 2), TextRange(2, 4))
        assertSame(composing, accountTextFieldEdit(composing, composing.text))
        val filtered = accountTextFieldEdit(composing, "中文")
        assertEquals("中文", filtered.text)
        assertEquals(TextRange(2), filtered.selection)
        assertNull(filtered.composition)
    }
}
