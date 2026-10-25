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
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class FeedbackUiTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        logger = LoggerFactory.getLogger("FeedbackUiTest")
        server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/feedback") { exchange ->
            requests += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val result = if (requests.size==1) "{}" else """{"id":"saved-feedback","received_at":"2026-10-25T12:00:00Z"}"""
            val bytes = result.toByteArray()
            exchange.sendResponseHeaders(if(requests.size==1) 503 else 201,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        appKoin = startKoin { modules(module {
            single { Settings(enableAnimations=false) }
            single { FeedbackPreferences("http://127.0.0.1:${server.address.port}") }
            single<Net> { Proxy.newProxyInstance(Net::class.java.classLoader,arrayOf(Net::class.java)) { _,_,_ -> null } as Net }
            single<AppContext> { Proxy.newProxyInstance(AppContext::class.java.classLoader,arrayOf(AppContext::class.java)) { _,method,_ ->
                when(method.name) { "isAndroid" -> false; "isDesktop" -> true; else -> null }
            } as AppContext }
        }) }.koin
        Dispatchers.setMain(object: CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext,block:Runnable) = SwingUtilities.invokeLater(block)
        })
        reloadI18n()
        i18nTable=Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        RuntimeLogs.initialize(File("build/tmp/feedback-ui-logs")).write("feedback regression log\n".toByteArray())
    }
    @AfterTest fun cleanup() { server.stop(0); Dispatchers.resetMain(); stopKoin(); reloadI18n() }

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
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("反馈提交失败",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("进入房间黑屏").assertExists()
        submit.performScrollTo().performClick()
        waitUntil(timeoutMillis=10000) { onAllNodes(hasText("反馈已收到",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2,requests.size)
        val id = Regex("\"request_id\":\"([^\"]+)\"")
        assertEquals(id.find(requests[0])!!.groupValues[1],id.find(requests[1])!!.groupValues[1])
        assertTrue(requests[1].contains("filename=\"logs.zip\""))
    }
}
