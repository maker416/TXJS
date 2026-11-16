/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.FeedbackPreferences
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.account.AccountSession
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.config.Settings
import io.github.rwpp.diagnostics.RuntimeLogs
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.ui.OpenSourceInfoView
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class FeedbackUiTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val historyId = "12345678-1234-4321-8321-123456789abc"
    @Volatile private var queryFailure = false
    @Volatile private var holdHistory = false
    private val historyEntered = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        logger = LoggerFactory.getLogger("FeedbackUiTest")
        server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/feedback") { exchange ->
            if (exchange.requestMethod == "GET") {
                if (queryFailure) {
                    exchange.sendResponseHeaders(429, -1)
                    exchange.close()
                    return@createContext
                }
                if (holdHistory && exchange.requestURI.path.endsWith("/mine")) {
                    historyEntered.countDown()
                    releaseHistory.await(10, TimeUnit.SECONDS)
                }
                val body = if (exchange.requestURI.path.endsWith("/mine")) {
                    """{"items":[{"id":"$historyId","title":"当前账号的反馈","description":"账号自己的描述","received_at":"2026-11-15T12:00:00Z","status":"investigating","public_reply":"正在排查账号问题"}],"total":1,"next_offset":1}"""
                } else """{"id":"$historyId","received_at":"2026-11-15T12:00:00Z","updated_at":"2026-11-15T13:00:00Z","status":"resolved","public_reply":"问题已修复，请更新版本"}"""
                val data = body.toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
                return@createContext
            }
            requests += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val submittedId = Regex("\"request_id\":\"([^\"]+)\"").find(requests.last())!!.groupValues[1]
            val result = if (requests.size==1) "{}" else """{"id":"$submittedId","received_at":"2026-10-25T12:00:00Z"}"""
            val bytes = result.toByteArray()
            exchange.sendResponseHeaders(if(requests.size==1) 503 else 201,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        appKoin = startKoin { modules(module {
            single { Settings(enableAnimations=false) }
            single { FeedbackPreferences("http://127.0.0.1:${server.address.port}") }
            single { AccountPreferences(appKey = "test-feedback-app") }
            single<ConfigIO> { Proxy.newProxyInstance(ConfigIO::class.java.classLoader, arrayOf(ConfigIO::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "FeedbackConfigIOFixture"
                    else -> null
                }
            } as ConfigIO }
            single<Net> { Proxy.newProxyInstance(Net::class.java.classLoader,arrayOf(Net::class.java)) { _,_,_ -> null } as Net }
            single<AppContext> { Proxy.newProxyInstance(AppContext::class.java.classLoader,arrayOf(AppContext::class.java)) { _,method,_ ->
                when(method.name) { "isAndroid" -> false; "isDesktop" -> true; else -> null }
            } as AppContext }
        }) }.koin
        AccountSession.resetForTests()
        Dispatchers.setMain(object: CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext,block:Runnable) = SwingUtilities.invokeLater(block)
        })
        reloadI18n()
        i18nTable=Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        RuntimeLogs.initialize(File("build/tmp/feedback-ui-logs")).write("feedback regression log\n".toByteArray())
    }
    @AfterTest fun cleanup() { releaseHistory.countDown(); AccountSession.resetForTests(); server.stop(0); Dispatchers.resetMain(); stopKoin(); reloadI18n() }

    @Test fun narrowFormRequiresConsentAndRetainsDraftAfterFailureForRetry() = runDesktopComposeUiTest(width=380,height=900) {
        setContent { KoinContext(appKoin) { MaterialTheme { OpenSourceInfoView {} } } }
        onNodeWithText("提交问题反馈").performClick()
        onNodeWithText("问题标题").performTextInput("进入房间黑屏")
        onNodeWithText("问题描述与复现步骤").performTextInput("点击加入后黑屏，房主尚未开始游戏。")
        val submit=onNodeWithText("提交反馈并上传日志")
        submit.performScrollTo().assertIsNotEnabled()
        onNode(isToggleable()).performScrollTo().performClick()
        submit.performScrollTo().assertIsEnabled()
        File("build/reports/feedback-ui").mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File("build/reports/feedback-ui/narrow.png"))
        submit.performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("反馈请求失败",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("进入房间黑屏").assertExists()
        submit.performScrollTo().performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("反馈已收到",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2,requests.size)
        val id = Regex("\"request_id\":\"([^\"]+)\"")
        assertEquals(id.find(requests[0])!!.groupValues[1],id.find(requests[1])!!.groupValues[1])
        assertTrue(requests[1].contains("filename=\"logs.zip\""))
        assertEquals(1, appKoin.get<FeedbackPreferences>().history.size)
    }

    @Test fun anonymousHistoryShowsOnlyStatusAndPublicReply() = runDesktopComposeUiTest(width=520,height=900) {
        appKoin.get<FeedbackPreferences>().history = listOf(io.github.rwpp.config.FeedbackHistoryEntry(
            "http://127.0.0.1:${server.address.port}", "", historyId))
        setContent { KoinContext(appKoin) { MaterialTheme { io.github.rwpp.ui.FeedbackView {} } } }
        onNodeWithText("反馈记录").performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("开发者回复：问题已修复，请更新版本")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("已解决").assertExists()
        onNodeWithText("账号自己的描述").assertDoesNotExist()
        onNodeWithText("登录查看账号历史").assertExists()
        File("build/reports/feedback-ui").mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/reports/feedback-ui/anonymous-history.png"))
        queryFailure = true
        onNodeWithText("刷新反馈记录").performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("请求过于频繁", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("已解决").assertExists()
        onNodeWithText("开发者回复：问题已修复，请更新版本").assertExists()
    }

    @Test fun accountHistoryDisappearsImmediatelyAfterLogout() = runDesktopComposeUiTest(width=700,height=900) {
        AccountSession.applyPreview(AccountUser(42, "feedback-user", nickname = "反馈用户"))
        AccountSession.networkEnabled = true
        setContent { KoinContext(appKoin) { MaterialTheme { io.github.rwpp.ui.FeedbackView {} } } }
        onNodeWithText("反馈记录").performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("当前账号的反馈")).fetchSemanticsNodes().isNotEmpty() }
        File("build/reports/feedback-ui").mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/reports/feedback-ui/account-history.png"))
        runOnIdle { AccountSession.applyLoggedOutPreview() }
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("登录查看账号历史")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("当前账号的反馈").assertDoesNotExist()
        onNodeWithText("正在排查账号问题", substring = true).assertDoesNotExist()
    }

    @Test fun lateAccountHistoryCannotReappearAfterLogout() = runDesktopComposeUiTest(width=700,height=900) {
        holdHistory = true
        AccountSession.applyPreview(AccountUser(42, "feedback-user", nickname = "反馈用户"))
        AccountSession.networkEnabled = true
        setContent { KoinContext(appKoin) { MaterialTheme { io.github.rwpp.ui.FeedbackView(initialHistory = true) {} } } }
        waitUntil(timeoutMillis=10000) { historyEntered.count == 0L }
        runOnIdle { AccountSession.applyLoggedOutPreview() }
        releaseHistory.countDown()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("登录查看账号历史")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("当前账号的反馈").assertDoesNotExist()
    }
}
