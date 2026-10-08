/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.WindowManager
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MultiplayerTopBarUiTest {
    private var desktop = true

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module {
                single { Settings(enableAnimations = false) }
                single<AppContext> {
                    Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) {
                            _, method, _ ->
                        when (method.name) {
                            "isDesktop" -> desktop
                            "isAndroid" -> !desktop
                            "getKoin" -> appKoin
                            else -> error("Unexpected AppContext access: ${method.name}")
                        }
                    } as AppContext
                }
            })
        }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun splitWindowKeepsNicknameJoinAndCloseUsable() = checkNarrowWindow(400)

    @Test
    fun smallWindowKeepsNicknameJoinAndCloseUsable() = checkNarrowWindow(320)

    private fun checkNarrowWindow(width: Int) = runDesktopComposeUiTest(width = width, height = 300) {
        desktop = false
        var closes = 0
        var joins = 0
        setContent { TestToolbar(onClose = { closes++ }, onJoin = { joins++ }) }
        assertControlsWithinViewport()
        onNode(hasSetTextAction() and hasText("Guest")).performTextReplacement("Player")
        onNode(hasSetTextAction() and hasText("Player")).assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("R1234")).performTextReplacement("R5678")
        onNode(hasSetTextAction() and hasText("R5678")).assertIsDisplayed()
        onNodeWithContentDescription("加入服务器", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(1, joins)
        save("topbar-$width.png")
        onNodeWithContentDescription("关闭").performTouchInput { click() }
        assertEquals(1, closes)
    }

    @Test
    fun resizingWideToolbarKeepsCloseAndNicknameReachable() = runDesktopComposeUiTest(width = 1200, height = 300) {
        var viewport by mutableStateOf(1200.dp)
        var closes = 0
        setContent { Box(Modifier.width(viewport)) { TestToolbar(onClose = { closes++ }) } }
        assertControlsWithinViewport()
        val wideName = onNode(hasSetTextAction() and hasText("Guest")).fetchSemanticsNode().boundsInRoot
        val wideJoin = onNode(hasSetTextAction() and hasText("R1234")).fetchSemanticsNode().boundsInRoot
        assertTrue(wideName.top < wideJoin.bottom && wideJoin.top < wideName.bottom)
        save("topbar-wide.png")
        runOnIdle { viewport = 380.dp }
        waitForIdle()
        assertControlsWithinViewport()
        val narrowName = onNode(hasSetTextAction() and hasText("Guest")).fetchSemanticsNode().boundsInRoot
        val narrowJoin = onNode(hasSetTextAction() and hasText("R1234")).fetchSemanticsNode().boundsInRoot
        assertTrue(narrowJoin.top >= narrowName.bottom)
        onNodeWithContentDescription("关闭").performTouchInput { click() }
        assertEquals(1, closes)
    }

    @Test
    fun desktopSpaceRefreshesImmediatelyButTypingDoesNot() = runDesktopComposeUiTest(width = 1200, height = 300) {
        var refreshes = 0
        setContent { TestToolbar(onRefresh = { refreshes++ }) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, refreshes)
        onNode(hasSetTextAction() and hasText("Guest")).performTouchInput { click() }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, refreshes)
    }

    @Test
    fun androidToolbarDoesNotRequestInputFocus() = runDesktopComposeUiTest(width = 400, height = 300) {
        desktop = false
        setContent { TestToolbar() }
        waitForIdle()
        onAllNodes(isFocused()).assertCountEquals(0)
    }

    @Composable
    private fun TestToolbar(
        onClose: () -> Unit = {},
        onJoin: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        var name by remember { mutableStateOf("Guest") }
        var address by remember { mutableStateOf("R1234") }
        KoinContext(appKoin) {
            CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                MaterialTheme {
                    MultiplayerTopBar(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        userName = name, accountNameLocked = false, onUserNameChange = { name = it },
                        joinServerAddress = address, onJoinServerAddressChange = { address = it },
                        onJoinServer = onJoin, onFilter = {}, onRefresh = onRefresh, isRefreshing = false,
                        joinHistory = emptyList(), onSelectJoinHistory = {}, onClose = onClose,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.assertControlsWithinViewport() {
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val buttons = onAllNodes(hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(buttons.size >= 5)
        for (node in buttons) {
            val bounds = node.boundsInRoot
            assertTrue(bounds.width >= 28f && bounds.height >= 28f, "button collapsed: $bounds")
            assertTrue(bounds.left >= root.left && bounds.right <= root.right, "button outside viewport: $bounds")
        }
        onNode(hasSetTextAction() and hasText("Guest")).assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("R1234")).assertIsDisplayed()
        onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/multiplayer-topbar-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
