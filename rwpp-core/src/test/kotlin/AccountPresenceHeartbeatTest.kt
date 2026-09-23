/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 账号在线心跳（文档 6.23）的会话层验证：登录即开始按间隔上报、登出停止、
 * 鉴权失败停止、预览模式不上报。
 */
class AccountPresenceHeartbeatTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        // 心跳循环会写日志；单测环境没有初始化全局 logger
        logger = LoggerFactory.getLogger("AccountPresenceHeartbeatTest")
        AccountSession.resetForTests()
        AccountSession.bindClient(
            AccountApiClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                appKey = "ak_test",
                http = OkHttpClient.Builder().build(),
            ),
        )
        AccountSession.heartbeatIntervalMs = 50
    }

    @AfterTest
    fun tearDown() {
        AccountSession.resetForTests()
        server.shutdown()
    }

    @Test
    fun beatsWhileLoggedInAndStopsOnLogout() = runBlocking {
        enqueueLogin()
        repeat(30) { enqueueOk() }

        AccountSession.login("alice", "passw0rd")
        assertEquals("/api/v1/users/login", server.takeRequest(2, TimeUnit.SECONDS)!!.path)

        repeat(3) {
            val beat = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", beat.method)
            assertEquals("/api/v1/presence/heartbeat", beat.path)
            assertEquals("Bearer tok-1", beat.getHeader("Authorization"))
        }

        AccountSession.logout()

        // 登出后循环已停止：等心跳间隔数倍时间后请求数不再增长
        delay(350)
        val settled = server.requestCount
        delay(250)
        assertEquals(settled, server.requestCount)
    }

    @Test
    fun unauthorizedHeartbeatStopsLoop() = runBlocking {
        enqueueLogin()
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"unauthorized","message":"token version mismatch"}""",
            ),
        )

        AccountSession.login("alice", "passw0rd")
        assertEquals("/api/v1/users/login", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals("/api/v1/presence/heartbeat", server.takeRequest(2, TimeUnit.SECONDS)!!.path)

        delay(350)
        val settled = server.requestCount
        delay(250)
        assertEquals(settled, server.requestCount)
    }

    @Test
    fun previewLoginDoesNotHeartbeat() = runBlocking {
        AccountSession.applyPreview(
            AccountUser(id = 1, username = "alice", nickname = "Alice", createdAt = "t"),
        )
        assertNull(server.takeRequest(400, TimeUnit.MILLISECONDS))
    }

    private fun enqueueLogin() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"tok-1","user":{"id":1,"username":"alice","nickname":"Alice","status":1,"created_at":"t","has_avatar":false}}""",
            ),
        )
    }

    private fun enqueueOk() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
    }
}
