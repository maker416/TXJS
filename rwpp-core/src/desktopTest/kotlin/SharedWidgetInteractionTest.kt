/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.koinInit
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.v2.bounceClick
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SharedWidgetInteractionTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module { single { Settings(enableAnimations = true) } })
        }.koin
        koinInit = true
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        koinInit = false
        reloadI18n()
        setI18nOverride(null)
    }

    @Test
    fun closeButtonReceivesPointerAboveLaterFullSizeContent() = runDesktopComposeUiTest {
        var closes = 0
        var backgroundClicks = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(240.dp)) {
                    ExitButton { closes++ }
                    // 模拟页面后绘制且覆盖关闭区域的正文。
                    Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                }
            }
        }
        onNodeWithContentDescription("关闭")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(Offset(8f, 8f)) }
        runOnIdle {
            assertEquals(1, closes)
            assertEquals(0, backgroundClicks)
        }
    }

    @Test
    fun animatedButtonRecoversAfterCancelledGestureAndUsesLatestCallback() = runDesktopComposeUiTest {
        var version by mutableStateOf(1)
        var called = 0
        setContent {
            KoinContext {
                val capturedVersion = version
                Box(Modifier.size(100.dp).testTag("press").bounceClick { called = capturedVersion })
            }
        }
        onNodeWithTag("press").performTouchInput {
            down(center)
            moveTo(Offset(width * 3f, height * 3f))
            up()
        }
        runOnIdle { assertEquals(0, called); version = 2 }
        onNodeWithTag("press").performTouchInput { click() }
        runOnIdle { assertEquals(2, called) }
        onNodeWithTag("press").performTouchInput { click() }
        runOnIdle { assertEquals(2, called) }
    }

    @Test
    fun weightedTextFieldCanFocusAndEditWithoutChangingRowWidth() = runDesktopComposeUiTest(width = 500, height = 200) {
        var value by mutableStateOf("")
        setContent {
            MaterialTheme {
                Row(Modifier.fillMaxWidth()) {
                    RWSingleOutlinedTextField(
                        label = "搜索",
                        value = value,
                        modifier = Modifier.weight(1f),
                        onValueChange = { value = it },
                    )
                    Box(Modifier.size(48.dp).testTag("action"))
                }
            }
        }
        val field = onNode(hasSetTextAction())
        val initialBounds = field.fetchSemanticsNode().boundsInRoot
        field.performTouchInput { click() }
        field.assertIsFocused().performTextInput("地图名称很长时也应该稳定且可以输入")
        runOnIdle { assertEquals("地图名称很长时也应该稳定且可以输入", value) }
        val editedBounds = field.fetchSemanticsNode().boundsInRoot
        assertEquals(initialBounds.left, editedBounds.left)
        assertEquals(initialBounds.right, editedBounds.right)
        onNodeWithTag("action").assertIsDisplayed()
    }
}
