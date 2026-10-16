/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 检查真实权限卡片的未授权、渠道关闭、全授权与窄屏操作，避免颜色与实际状态脱节。 */
@OptIn(ExperimentalTestApi::class)
class BackgroundConnectionPermissionsUiTest {
    @BeforeTest
    fun setup() = loadLanguage("zh")

    @AfterTest
    fun tearDown() {
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun settingsRowShowsCurrentCountAndOpensAsAWholeRow() = runDesktopComposeUiTest(width = 792, height = 160) {
        val permissions = mutableStateOf(BackgroundConnectionPermissionState(false, true, false))
        var opened = 0
        setContent {
            TestTheme { BackgroundConnectionSettingsRow(permissions.value, onClick = { opened++ }) }
        }
        onNodeWithText("0/2 项已开启").assertIsDisplayed()
        onNodeWithText("后台联机保活").performClick()
        runOnIdle {
            assertEquals(1, opened)
            permissions.value = BackgroundConnectionPermissionState(true, true, true)
        }
        onNodeWithText("2/2 项已开启").assertIsDisplayed()
        onNodeWithText("0/2 项已开启").assertDoesNotExist()
        save("permissions-settings-row.png")
    }

    @Test
    fun missingPermissionsHaveExplicitLabelsAndSeparateActions() = runDesktopComposeUiTest(width = 792, height = 360) {
        var notifications = 0
        var battery = 0
        setContent {
            TestTheme {
                BackgroundConnectionPermissionPanel(
                    BackgroundConnectionPermissionState(false, true, false), false,
                    { notifications++ }, { battery++ }, {}, {},
                )
            }
        }
        onNodeWithText("0/2 项已开启").assertIsDisplayed()
        onAllNodesWithText("未开启").assertCountEquals(2)
        onAllNodesWithText("去开启")[0].assertIsDisplayed().performClick()
        onAllNodesWithText("去开启")[1].assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, notifications); assertEquals(1, battery) }
        save("permissions-pending.png")
    }

    @Test
    fun disabledNotificationChannelStillCountsAsMissing() = runDesktopComposeUiTest(width = 792, height = 360) {
        var notificationAction = 0
        var batteryAction = 0
        setContent {
            TestTheme {
                BackgroundConnectionPermissionPanel(
                    BackgroundConnectionPermissionState(true, false, true), false,
                    { notificationAction++ }, { batteryAction++ }, {}, {},
                )
            }
        }
        onNodeWithText("1/2 项已开启").assertIsDisplayed()
        onNodeWithText("联机保活通知类别已关闭。").assertIsDisplayed()
        onNodeWithText("未开启").assertIsDisplayed()
        onNodeWithText("已开启").assertIsDisplayed()
        onNodeWithText("去开启").performClick()
        onNodeWithText("管理").performClick()
        runOnIdle { assertEquals(1, notificationAction); assertEquals(1, batteryAction) }
        save("permissions-partial.png")
    }

    @Test
    fun grantedPermissionsRemainManageableAndVendorSettingsStayManual() = runDesktopComposeUiTest(width = 792, height = 360) {
        var manualSettings = 0
        setContent {
            TestTheme {
                BackgroundConnectionPermissionPanel(
                    BackgroundConnectionPermissionState(true, true, true), false,
                    {}, {}, { manualSettings++ }, {},
                )
            }
        }
        onNodeWithText("2/2 项已开启").assertIsDisplayed()
        onAllNodesWithText("已开启").assertCountEquals(2)
        onAllNodesWithText("管理").assertCountEquals(2)
        onNodeWithText("去开启").assertDoesNotExist()
        onNodeWithText("需手动检查").assertIsDisplayed()
        onNodeWithText("更多后台设置").performClick()
        runOnIdle { assertEquals(1, manualSettings) }
        save("permissions-granted.png")
    }

    @Test
    fun narrowEnglishPanelKeepsBatteryActionReachableAndCloseVisible() = runDesktopComposeUiTest(width = 360, height = 640) {
        loadLanguage("en")
        var battery = 0
        var closed = 0
        setContent {
            TestTheme {
                BackgroundConnectionPermissionPanel(
                    BackgroundConnectionPermissionState(false, true, false), false,
                    {}, { battery++ }, {}, { closed++ },
                )
            }
        }
        onNodeWithText("0/2 enabled").assertIsDisplayed()
        onAllNodesWithText("Enable")[1].performScrollTo().assertIsDisplayed().performClick()
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, battery); assertEquals(1, closed) }
        save("permissions-narrow-en.png")
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = defaultRWPPColorScheme) {
            Box(Modifier.fillMaxSize().background(defaultRWPPColorScheme.background)) { content() }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/background-permissions-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        val roots = onAllNodes(isRoot())
        check(ImageIO.write(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(), "png", target))
    }
}
