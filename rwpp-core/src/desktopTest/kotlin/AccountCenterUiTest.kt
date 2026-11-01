/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.ui.AccountView
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.*

/** 验证生产账号页只显示当前分类，横竖屏导航及关闭入口不随正文滚走。 */
@OptIn(ExperimentalTestApi::class)
class AccountCenterUiTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        logger = LoggerFactory.getLogger("AccountCenterUiTest")
        appKoin = startKoin {
            modules(module {
                single { Settings(enableAnimations = false) }
                single<AppContext> {
                    Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, _ ->
                        when (method.name) { "isDesktop" -> true; "isAndroid" -> false; else -> null }
                    } as AppContext
                }
            })
        }.koin
        AccountSession.applyPreview(AccountUser(42, "commander", "player@example.com", "指挥官"))
    }

    @AfterTest
    fun cleanup() {
        AccountSession.resetForTests()
        stopKoin()
        reloadI18n()
    }

    @Composable
    private fun Host(onExit: () -> Unit = {}) {
        KoinContext(appKoin) {
            MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight)) {
                        AccountView(onExit)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.select(label: String) =
        onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsDisplayed().performClick().assertIsSelected()

    private fun ComposeUiTest.screenshot(name: String) {
        val dir = File("build/reports/account-center").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }

    @Test
    fun landscapeShowsOnlySelectedFunctionAndKeepsNavigationVisible() = runDesktopComposeUiTest(width = 873, height = 393) {
        setContent { Host() }
        screenshot("profile-landscape.png")
        onNodeWithText("player@example.com").assertExists()
        onNodeWithText("对好友隐藏我的在线状态").assertDoesNotExist()
        onNodeWithText("查看流水").assertDoesNotExist()
        onNodeWithText("退出登录").assertDoesNotExist()
        select("积分")
        onNodeWithText("查看流水").assertIsDisplayed()
        onNodeWithText("player@example.com").assertDoesNotExist()
        screenshot("points-landscape.png")
        select("隐私")
        onNodeWithText("对好友隐藏我的在线状态").assertIsDisplayed()
        onNodeWithText("查看流水").assertDoesNotExist()
        screenshot("privacy-landscape.png")
        select("账号安全")
        onNodeWithText("更换邮箱").assertIsDisplayed()
        onNodeWithText("退出登录").assertIsDisplayed()
        screenshot("security-landscape.png")
        select("账号资料")
        onNodeWithText("player@example.com").assertExists()
    }

    @Test
    fun portraitKeepsAllFunctionsAndCloseAccessibleAfterScrolling() = runDesktopComposeUiTest(width = 393, height = 800) {
        var exits = 0
        setContent { Host { exits++ } }
        screenshot("profile-portrait.png")
        onNodeWithContentDescription("修改昵称").assertIsDisplayed()
        select("积分")
        onNodeWithText("查看流水").assertIsDisplayed()
        select("隐私")
        screenshot("privacy-portrait.png")
        select("账号安全")
        onNodeWithText("退出登录").assertIsDisplayed()
        onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun smallLandscapeKeepsFunctionSwitchingIndependentOfContentScroll() = runDesktopComposeUiTest(width = 640, height = 360) {
        setContent { Host() }
        onNodeWithTag("account-center-content", useUnmergedTree = true).performTouchInput { swipeUp() }
        select("隐私")
        onNodeWithText("对陌生人隐藏我的在线状态").assertIsDisplayed()
        select("积分")
        onNodeWithText("查看流水").assertIsDisplayed()
        screenshot("points-small-landscape.png")
        select("账号资料")
        onNodeWithText("头像").assertIsDisplayed()
        onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    @Test
    fun desktopRendersAccountCenterAndOpensExistingNicknameDialog() = runDesktopComposeUiTest(width = 1258, height = 616) {
        setContent { Host() }
        screenshot("profile-desktop.png")
        onNodeWithContentDescription("修改昵称").assertIsDisplayed().performClick()
        onNode(hasSetTextAction()).assertIsDisplayed().assertTextContains("指挥官")
    }
}
