/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.rwpp.AppContext
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.PaymentController
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.ui.PaymentDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PaymentUiTest {
    private val id = "11111111-2222-3333-4444-555555555555"
    private lateinit var server: MockWebServer
    private var oldPlatformUrl: String? = null
    private val captured = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var passwordSet = false
    @Volatile private var paid = false
    @Volatile private var holdOrder = false
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        logger = LoggerFactory.getLogger("PaymentUiTest")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = request.body.readUtf8()
                captured += path to body
                val result = when {
                    path == "/api/v1/users/payment-password" ->
                        """{"has_password":$passwordSet,"email_bound":true,"email_masked":"p***@example.test","locked_until":0}"""
                    path.endsWith("/payment-password/send-code") -> """{"ok":true}"""
                    path.endsWith("/payment-password/setup") -> { passwordSet = true; """{"ok":true}""" }
                    path == "/api/v1/payment-authorizations" ->
                        """{"ticket":"${"a".repeat(64)}","expires_at":${System.currentTimeMillis() / 1000 + 60}}"""
                    path.startsWith("/api/v1/client/payments/") -> {
                        if (holdOrder) { entered.countDown(); release.await(8, TimeUnit.SECONDS) }
                        if (path.endsWith("/confirm")) paid = true
                        """{"code":"ok","data":{"id":"$id","app_id":"22222222-2222-2222-2222-222222222222","app_name":"测试收款应用","subject":"测试道具","payer_account_id":"123","point_type_id":1,"points":100,"status":"${if (paid) "succeeded" else "pending"}","expires_at":1900000000}}"""
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(result)
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        oldPlatformUrl = System.getProperty("rwjs.openPlatform.url")
        System.setProperty("rwjs.openPlatform.url", base)
        val http = OkHttpClient()
        appKoin = startKoin { modules(module {
            single { Settings(enableAnimations = false) }
            single { AccountPreferences(apiUrl = base, appKey = "payment-test") }
            single<ConfigIO> { proxy(ConfigIO::class.java) { null } }
            single<Net> { proxy(Net::class.java) { if (it == "getClient") http else null } }
            single<AppContext> { proxy(AppContext::class.java) { when (it) { "isDesktop" -> true; "isAndroid" -> false; else -> null } } }
        }) }.koin
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        AccountSession.resetForTests()
        AccountSession.httpOverride = http
        AccountSession.applyPreview(AccountUser(42, "payment-test", email = "payer@example.test", nickname = "付款用户", accountId = "123"), "test-token")
        AccountSession.networkEnabled = true
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest fun cleanup() {
        release.countDown()
        PaymentController.close()
        AccountSession.resetForTests()
        server.close()
        if (oldPlatformUrl == null) System.clearProperty("rwjs.openPlatform.url") else System.setProperty("rwjs.openPlatform.url", oldPlatformUrl!!)
        Dispatchers.resetMain()
        runCatching { stopKoin() }
        reloadI18n()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, value: (String) -> Any?): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args?.get(0)
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "PaymentFixture"
            else -> value(method.name)
        }
    } as T

    @Test fun settingPinReturnsToOrderAndRequiresAnotherExplicitConfirmation() = runDesktopComposeUiTest(width = 760, height = 900) {
        setContent { KoinContext(appKoin) { MaterialTheme { PaymentDialog {} } } }
        runOnIdle { PaymentController.open(id) }
        waitUntil(timeoutMillis = 10000) { onAllNodesWithText("测试道具").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("支付 100 积分").assertExists()
        onNodeWithText("设置支付密码").performClick()
        waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("邮箱验证码").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("邮箱验证码").performTextInput("654321")
        onNodeWithContentDescription("新的六位数字支付密码").performTextInput("001234")
        onNodeWithContentDescription("再次输入支付密码").performTextInput("001234")
        onNodeWithText("验证邮箱并保存密码").performClick()
        waitUntil(timeoutMillis = 10000) { onAllNodesWithText("支付密码已保存。请再次核对订单并确认支付。").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(paid)
        assertTrue(captured.none { it.first.endsWith("/confirm") || it.first == "/api/v1/payment-authorizations" })
        onNodeWithContentDescription("六位数字支付密码").performTextInput("001234")
        onNodeWithText("确认支付").performClick()
        waitUntil(timeoutMillis = 10000) { onAllNodesWithText("支付成功，积分已扣除。").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, captured.count { it.first.endsWith("/confirm") })
        assertTrue(captured.first { it.first.endsWith("/confirm") }.second.contains("ticket"))
        assertFalse(captured.first { it.first.endsWith("/confirm") }.second.contains("001234"))
    }

    @Test fun logoutCancelsAnInflightOrderAndLateResponsesCannotRestoreIt() = runDesktopComposeUiTest(width = 500, height = 800) {
        holdOrder = true
        setContent { KoinContext(appKoin) { MaterialTheme { PaymentDialog {} } } }
        runOnIdle { PaymentController.open(id) }
        waitUntil(timeoutMillis = 10000) { entered.count == 0L }
        runOnIdle { AccountSession.applyLoggedOutPreview() }
        release.countDown()
        waitUntil(timeoutMillis = 5000) { !PaymentController.state.value.visible }
        onNodeWithText("测试道具").assertDoesNotExist()
        assertNull(PaymentController.state.value.order)
        assertTrue(captured.none { it.first.endsWith("/confirm") })
    }

    @Test fun reopeningWithinOneFrameAlwaysDestroysThePreviousPin() = runDesktopComposeUiTest(width = 500, height = 800) {
        passwordSet = true
        setContent { KoinContext(appKoin) { MaterialTheme { PaymentDialog {} } } }
        runOnIdle { PaymentController.open(id) }
        waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("六位数字支付密码").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("六位数字支付密码").performTextInput("001234")
        runOnIdle { PaymentController.close(); PaymentController.open(id) }
        waitUntil(timeoutMillis = 10000) { onAllNodesWithContentDescription("六位数字支付密码").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", onNodeWithContentDescription("六位数字支付密码").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText("确认支付").assertIsNotEnabled()
        assertTrue(captured.none { it.first.endsWith("/confirm") || it.first == "/api/v1/payment-authorizations" })
    }
}
