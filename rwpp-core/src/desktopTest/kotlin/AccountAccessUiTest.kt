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
import androidx.compose.ui.test.*
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.ui.AccountLoginDialog
import io.github.rwpp.ui.AccountRegisterDialog
import io.github.rwpp.ui.AccountForgotDialog
import io.github.rwpp.ui.AccountWelcomeView
import io.github.rwpp.ui.AccountView
import io.github.rwpp.account.AccountSession
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.*

/** 使用生产认证 UI 验证手机横屏无滚动主路径、注册草稿和错误反馈。 */
@OptIn(ExperimentalTestApi::class)
class AccountAccessUiTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        io.github.rwpp.i18n.reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
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
    }

    @AfterTest
    fun cleanup() { stopKoin() }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KoinContext(appKoin) {
            MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight)) {
                        content()
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val folder = File("build/reports/account-access").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, name))
    }

    @Test
    fun welcomeLandscapeKeepsAllEntrancesVisible() = runDesktopComposeUiTest(width = 873, height = 393) {
        var login = 0
        var register = 0
        var exits = 0
        setContent { Host { AccountWelcomeView({ login++ }, { register++ }, { exits++ }) } }
        onNodeWithText("登录").assertIsDisplayed().performClick()
        onNodeWithText("创建新账号").assertIsDisplayed().performClick()
        onNodeWithText("先去游戏").assertIsDisplayed().performClick()
        onNodeWithContentDescription("取消").assertIsDisplayed().performClick()
        screenshot("welcome-landscape.png")
        runOnIdle { assertEquals(1, login); assertEquals(1, register); assertEquals(2, exits) }
    }

    @Test
    fun welcomePortraitKeepsActionsVisible() = runDesktopComposeUiTest(width = 393, height = 800) {
        setContent { Host { AccountWelcomeView({}, {}, {}) } }
        onNodeWithText("登录").assertIsDisplayed()
        onNodeWithText("创建新账号").assertIsDisplayed()
        onNodeWithText("先去游戏").assertIsDisplayed()
        screenshot("welcome-portrait.png")
    }

    @Test
    fun actualAccountPageUsesWelcomeWithoutAnExtraPageHeader() = runDesktopComposeUiTest(width = 873, height = 393) {
        val networkEnabled = AccountSession.networkEnabled
        AccountSession.networkEnabled = false
        try {
            setContent { Host { AccountView({}) } }
            onNodeWithText("用户").assertDoesNotExist()
            onNodeWithText("极速账号").assertIsDisplayed()
            onNodeWithText("先去游戏").assertIsDisplayed()
            screenshot("welcome-full-page.png")
            onNodeWithText("登录").performClick()
            onNodeWithContentDescription("账号或邮箱").assertIsDisplayed()
            onNodeWithContentDescription("密码").assertIsDisplayed()
        } finally {
            AccountSession.networkEnabled = networkEnabled
        }
    }

    @Test
    fun landscapeLoginKeepsBothMethodsAndActionsVisible() = runDesktopComposeUiTest(width = 873, height = 393) {
        var username by mutableStateOf("")
        var registrations = 0
        setContent { Host { AccountLoginDialog(true, username, { username = it }, {}, true, { registrations++ }, {}) } }
        screenshot("login-landscape.png")
        onNodeWithText("密码登录").assertIsDisplayed()
        onNodeWithContentDescription("账号或邮箱").assertIsDisplayed().performTextInput("player_one")
        onNodeWithContentDescription("密码").assertIsDisplayed().performTextInput("secret123")
        onNodeWithText("显示").performClick()
        onNodeWithContentDescription("密码").assertTextContains("secret123")
        onNodeWithText("登录", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("验证码登录").performClick()
        onNodeWithContentDescription("邮箱").assertIsDisplayed()
        onNodeWithContentDescription("验证码").assertIsDisplayed()
        onNodeWithText("发送验证码").assertIsDisplayed()
        screenshot("code-landscape.png")
        onNodeWithText("密码登录").performClick()
        onNodeWithContentDescription("账号或邮箱").assertTextContains("player_one")
        onNodeWithText("没有账号？注册").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, registrations) }
    }

    @Test
    fun landscapeRegistrationValidatesEachStepAndPreservesDrafts() = runDesktopComposeUiTest(width = 873, height = 393) {
        var username by mutableStateOf("")
        setContent { Host { AccountRegisterDialog(true, username, { username = it }, {}, true, {}) } }
        screenshot("register-account-landscape.png")
        onNodeWithText("下一步").assertIsDisplayed().performClick()
        onNodeWithText("账号须为 3–32 位字母、数字或下划线").assertIsDisplayed()
        onNodeWithContentDescription("账号").performTextInput("player_one")
        onNodeWithContentDescription("昵称（可选）").performTextInput("新指挥官")
        onNodeWithText("下一步").performClick()
        onNodeWithContentDescription("邮箱").assertIsDisplayed().performTextInput("player@example.com")
        onNodeWithContentDescription("验证码").performTextInput("123456")
        screenshot("register-email-landscape.png")
        onNodeWithText("下一步").assertIsDisplayed().performClick()
        onNodeWithContentDescription("密码").assertIsDisplayed().performTextInput("secret123")
        onNodeWithContentDescription("确认密码").assertIsDisplayed().performTextInput("different")
        onNodeWithText("注册", useUnmergedTree = true).assertIsDisplayed().performClick()
        onNodeWithText("两次输入的密码不一致").assertIsDisplayed()
        screenshot("register-password-landscape.png")
        onNodeWithText("上一步").performClick()
        onNodeWithContentDescription("邮箱").assertTextContains("player@example.com")
        onNodeWithContentDescription("验证码").assertTextContains("123456")
        onNodeWithText("上一步").performClick()
        onNodeWithContentDescription("账号").assertTextContains("player_one")
        onNodeWithContentDescription("昵称（可选）").assertTextContains("新指挥官")
    }

    @Test
    fun portraitLoginIsCompactAndReadable() = runDesktopComposeUiTest(width = 393, height = 800) {
        setContent { Host { AccountLoginDialog(true, "", {}, {}, true, {}, {}) } }
        onNodeWithContentDescription("账号或邮箱").assertIsDisplayed()
        onNodeWithContentDescription("密码").assertIsDisplayed()
        onNodeWithText("登录", useUnmergedTree = true).assertIsDisplayed()
        screenshot("login-portrait.png")
    }

    @Test
    fun smallerLandscapeKeepsLoginAndCloseAccessible() = runDesktopComposeUiTest(width = 640, height = 360) {
        var closed = false
        setContent { Host { AccountLoginDialog(true, "", {}, { closed = true }, true, {}, {}) } }
        onNodeWithContentDescription("账号或邮箱").assertIsDisplayed()
        onNodeWithContentDescription("密码").assertIsDisplayed()
        onNodeWithText("登录", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("没有账号？注册").assertIsDisplayed()
        screenshot("login-small-landscape.png")
        onNodeWithContentDescription("取消").performClick()
        waitUntil { closed }
    }

    @Test
    fun passwordRecoveryPreservesEmailWhenGoingBack() = runDesktopComposeUiTest(width = 873, height = 393) {
        setContent { Host { AccountForgotDialog(true, {}, {}, inlineDesktop = true) } }
        onNodeWithContentDescription("邮箱").performTextInput("player@example.com")
        onNodeWithContentDescription("验证码").performTextInput("123456")
        onNodeWithText("下一步").assertIsDisplayed().performClick()
        onNodeWithContentDescription("新密码").assertIsDisplayed()
        onNodeWithContentDescription("确认密码").assertIsDisplayed()
        onNodeWithText("重置密码", useUnmergedTree = true).assertIsDisplayed()
        screenshot("reset-password-landscape.png")
        onNodeWithText("上一步").performClick()
        onNodeWithContentDescription("邮箱").assertTextContains("player@example.com")
        onNodeWithContentDescription("验证码").assertTextContains("123456")
    }
}
