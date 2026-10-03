/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.ResourceBrowserOrientation
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.koinInit
import io.github.rwpp.platform.ResourceBrowserLayout
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ResourceBrowserOrientationUiTest {
    private lateinit var settings: Settings
    private lateinit var configIO: ConfigIO
    private val savedChoices = mutableListOf<ResourceBrowserOrientation?>()

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        configIO = Proxy.newProxyInstance(ConfigIO::class.java.classLoader, arrayOf(ConfigIO::class.java)) { _, method, args ->
            if (method.name == "saveConfig") savedChoices += (args!![0] as Settings).resourceBrowserOrientation
            null
        } as ConfigIO
        appKoin = startKoin { modules(module { single { settings }; single { configIO } }) }.koin
        koinInit = true
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        koinInit = false
    }

    @Test
    fun landscapePromptAnimatesBeforeApplyingSelection() = runDesktopComposeUiTest(width = 720, height = 360) {
        var calls = 0
        var selected: ResourceBrowserOrientation? = null
        var visible by mutableStateOf(true)
        setContent {
            TestTheme {
                if (visible) ResourceBrowserOrientationDialog(
                    onSelected = { selected = it; calls++; visible = false },
                    onDismissRequest = { visible = false },
                )
            }
        }
        waitForIdle()
        onNodeWithText("选择资源页面的显示方向").assertIsDisplayed()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        saveScreenshot("orientation_prompt_landscape.png", onAllNodes(isRoot())[roots.lastIndex].captureToImage().toAwtImage())
        mainClock.autoAdvance = false
        onNodeWithText("竖屏").performClick()
        runOnIdle { assertEquals(0, calls) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        runOnIdle {
            assertEquals(ResourceBrowserOrientation.Portrait, selected)
            assertEquals(1, calls)
        }
    }

    @Test
    fun portraitPromptAndSettingFitSmallScreens() = runDesktopComposeUiTest(width = 360, height = 720) {
        var showDialog by mutableStateOf(true)
        setContent {
            TestTheme {
                ResourceBrowserOrientationSetting(settings, configIO)
                if (showDialog) ResourceBrowserOrientationDialog(
                    onSelected = { showDialog = false },
                    onDismissRequest = { showDialog = false },
                )
            }
        }
        waitForIdle()
        onNodeWithText("横屏").assertIsDisplayed()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        saveScreenshot("orientation_prompt_portrait.png", onAllNodes(isRoot())[roots.lastIndex].captureToImage().toAwtImage())
        onNodeWithText("竖屏").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("browser-orientation-setting").performClick()
        onNodeWithText("竖屏").performClick()
        runOnIdle {
            assertEquals(ResourceBrowserOrientation.Portrait, settings.resourceBrowserOrientation)
            assertEquals(listOf<ResourceBrowserOrientation?>(ResourceBrowserOrientation.Portrait), savedChoices)
        }
        onNodeWithTag("browser-orientation-setting").performClick()
        onNodeWithText("首次进入时询问").performClick()
        runOnIdle {
            assertNull(settings.resourceBrowserOrientation)
            assertEquals(listOf(ResourceBrowserOrientation.Portrait, null), savedChoices)
        }
    }

    @Test
    fun desktopPortraitViewportAndFloatingExitRemainUsable() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var closed = false
        setContent {
            TestTheme {
                ResourceBrowserLayout(ResourceBrowserOrientation.Portrait, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("viewport")) {
                        ResourceBrowserFloatingExit { closed = true }
                    }
                }
            }
        }
        waitForIdle()
        val bounds = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(bounds.width / bounds.height - 9f / 16f) < 0.01f)
        assertTrue(bounds.left > 0f)
        onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun floatingExitDragsToBothEdgesWithoutClosing() = runDesktopComposeUiTest(width = 720, height = 360) {
        var closed = false
        setContent { TestTheme { ResourceBrowserFloatingExit { closed = true } } }
        val ball = onNodeWithTag("browser-floating-exit")
        waitForIdle()
        val initial = ball.fetchSemanticsNode().boundsInRoot
        ball.performTouchInput { swipe(center, center + Offset(-620f, 90f), durationMillis = 500) }
        waitForIdle()
        val left = ball.fetchSemanticsNode().boundsInRoot
        runOnIdle { assertTrue(!closed, "Dragging must not trigger exit") }
        assertTrue(left.left < initial.left / 2, "Release should dock on the left")
        assertTrue(left.top > initial.top, "Vertical dragging should move the ball")

        ball.performTouchInput { swipe(center, center + Offset(620f, -90f), durationMillis = 500) }
        waitForIdle()
        val right = ball.fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(right.left - initial.left) < 2f, "Release should dock on the right")
        ball.performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun floatingExitStaysInsideViewportAfterResize() = runDesktopComposeUiTest(width = 720, height = 720) {
        var portrait by mutableStateOf(false)
        setContent {
            TestTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(if (portrait) 360.dp else 720.dp, if (portrait) 720.dp else 360.dp).testTag("viewport")) {
                        ResourceBrowserFloatingExit {}
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("browser-floating-exit").performTouchInput {
            swipe(center, center + Offset(0f, 500f), durationMillis = 500)
        }
        runOnIdle { portrait = true }
        waitForIdle()
        val viewport = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val ball = onNodeWithTag("browser-floating-exit").fetchSemanticsNode().boundsInRoot
        assertTrue(viewport.width < viewport.height, "Viewport should resize to portrait")
        assertTrue(ball.left >= viewport.left && ball.right <= viewport.right)
        assertTrue(ball.top >= viewport.top && ball.bottom <= viewport.bottom)
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        KoinContext(appKoin) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun saveScreenshot(name: String, image: java.awt.image.BufferedImage) {
        val target = File("build/reports/browser-orientation/$name")
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
    }
}
