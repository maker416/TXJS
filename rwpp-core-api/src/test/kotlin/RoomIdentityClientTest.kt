/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomIdentityClientTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    @BeforeTest
    fun setup() {
        // 全局 logger 是 lateinit（正常运行时由入口初始化），单测里补上避免命中 warn 路径时崩
        runCatching { io.github.rwpp.logger }.onFailure {
            io.github.rwpp.logger = org.slf4j.LoggerFactory.getLogger("RoomIdentityClientTest")
        }
        serverA = MockWebServer()
        serverA.start()
        serverB = MockWebServer()
        serverB.start()
    }

    @AfterTest
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
    }

    private fun url(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun newClient(vararg servers: MockWebServer): RoomIdentityClient =
        RoomIdentityClient(servers.map { url(it) }, OkHttpClient.Builder().build())

    private fun MockWebServer.enqueueVerified() {
        enqueue(MockResponse().setBody("""{"room_id_membership_verified":true}"""))
    }

    private fun MockWebServer.takeAnonymousProbe() {
        val probe = takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/capabilities", probe.path)
        assertEquals("GET", probe.method)
        assertNull(probe.getHeader("Authorization"))
        assertNull(probe.getHeader("X-App-Key"))
    }

    @Test
    fun publishSendsHeadersAndBody() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        newClient(serverA).publish(
            roomKeys = listOf("code:Q77182", "addr:1.2.3.4:5123"),
            playerName = "萌新",
            appKey = "ak_test",
            token = "tok-1",
        )
        serverA.takeAnonymousProbe()
        val recorded = serverA.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/roomid/publish", recorded.path)
        assertEquals("ak_test", recorded.getHeader("X-App-Key"))
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"room_keys\""))
        assertTrue(body.contains("\"player_name\""))
        assertTrue(body.contains("code:Q77182"))
    }

    @Test
    fun lookupParsesEmptyUsers() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("""{"users":[]}"""))
        val users = newClient(serverA).lookup(listOf("code:Q77182"), "萌新", "ak_test", "tok-1")
        assertTrue(users.isEmpty())
        serverA.takeAnonymousProbe()
        val recorded = serverA.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        // room_key 为重复 query 参数，player_name 需 URL 编码
        assertEquals(
            "/api/v1/roomid/lookup?room_key=code%3AQ77182&player_name=%E8%90%8C%E6%96%B0",
            recorded.path,
        )
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        assertEquals("ak_test", recorded.getHeader("X-App-Key"))
    }

    @Test
    fun lookupParsesEntries() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"users":[{"user_id":123,"username":"abc","nickname":"萌新"}]}"""
            )
        )
        val users = newClient(serverA).lookup(
            listOf("code:Q77182", "addr:1.2.3.4:5123"),
            "萌新",
            "ak_test",
            "tok-1",
        )
        assertEquals(listOf(RoomIdEntry(123L, "abc", "萌新")), users)
        serverA.takeAnonymousProbe()
        val recorded = serverA.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(recorded.path!!.contains("room_key=code%3AQ77182&room_key=addr%3A1.2.3.4%3A5123"))
    }

    @Test
    fun notFoundFallsOverToNextMirror() = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(404))
        serverB.enqueueVerified()
        serverB.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"users":[{"user_id":1,"username":"abc","nickname":"n"}]}"""
            )
        )
        val users = newClient(serverA, serverB).lookup(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        assertEquals(1, users.size)
        // 两台都收到过请求：A 404 后 failover 到 B
        assertEquals(1, serverA.requestCount)
        serverA.takeAnonymousProbe()
        serverB.takeAnonymousProbe()
        assertEquals(2, serverB.requestCount)
    }

    @Test
    fun serverErrorFallsOverToNextMirror() = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(500))
        serverB.enqueueVerified()
        serverB.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        newClient(serverA, serverB).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        assertEquals(1, serverA.requestCount)
        serverA.takeAnonymousProbe()
        serverB.takeAnonymousProbe()
        assertEquals(2, serverB.requestCount)
    }

    @Test
    fun allMirrorsNotFoundThrowsFeatureUnavailable() = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(404))
        serverB.enqueue(MockResponse().setResponseCode(404))
        val error = assertFailsWith<RoomIdFeatureUnavailableException> {
            newClient(serverA, serverB).lookup(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        assertTrue(error.message.orEmpty().isNotBlank())
        Unit
    }

    @Test
    fun allMirrorsUnavailableThrowsFeatureUnavailable() = runBlocking {
        // 503 = 服务端未配置该功能，与 404 同按「功能不可用」降级
        serverA.enqueue(MockResponse().setResponseCode(503))
        serverB.enqueue(MockResponse().setResponseCode(503))
        assertFailsWith<RoomIdFeatureUnavailableException> {
            newClient(serverA, serverB).lookup(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        Unit
    }

    @Test
    fun unauthorizedThrowsWithoutFailover() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))
        serverB.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertFailsWith<RoomIdUnauthorizedException> {
            newClient(serverA, serverB).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        // 401 是确定性失败：B 不应收到请求
        assertEquals(2, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
        Unit
    }

    @Test
    fun deleteSendsDeleteMethod() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        newClient(serverA).delete("ak_test", "tok-1")
        serverA.takeAnonymousProbe()
        val recorded = serverA.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/roomid/publish", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
    }

    @Test
    fun missingOrFalseMembershipCapabilityNeverSendsCredentials() = runBlocking {
        serverA.enqueue(MockResponse().setBody("""{"protocol_version":2,"upload_auth_required":true}"""))
        serverB.enqueue(MockResponse().setBody("""{"room_id_membership_verified":false}"""))
        assertFailsWith<RoomIdFeatureUnavailableException> {
            newClient(serverA, serverB).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        serverA.takeAnonymousProbe()
        serverB.takeAnonymousProbe()
        assertEquals(1, serverA.requestCount)
        assertEquals(1, serverB.requestCount)
    }

    @Test
    fun explicitMembershipDenialDoesNotFallBack() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"room_id_membership_unavailable"}"""))
        assertFailsWith<RoomIdFeatureUnavailableException> {
            newClient(serverA, serverB).lookup(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        assertEquals(2, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun publicHttpIsSkippedEvenIfDnsResolvesToLoopback() = runBlocking {
        val public = serverA.url("/").newBuilder().host("relay.example").build()
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
        }).build()
        assertFailsWith<RoomIdFeatureUnavailableException> {
            RoomIdentityClient(listOf(public.toString()), http).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        assertEquals(0, serverA.requestCount)
    }

    @Test
    fun localhostHttpDoesNotSendRequestsThroughProxy() = runBlocking {
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), serverB.port)
        val http = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, address)).build()
        assertFailsWith<java.io.IOException> {
            RoomIdentityClient(listOf(url(serverA)), http).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        assertEquals(0, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun authenticatedRequestDoesNotFollowRedirect() = runBlocking {
        serverA.enqueueVerified()
        serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", serverB.url("/target")))
        val error = assertFailsWith<RoomIdException> {
            newClient(serverA).publish(listOf("code:Q77182"), "n", "ak_test", "tok-1")
        }
        assertEquals(307, error.statusCode)
        assertEquals(2, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }
}
