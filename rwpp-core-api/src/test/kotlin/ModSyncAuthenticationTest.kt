/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import kotlinx.coroutines.*
import java.security.MessageDigest
import okio.Buffer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModSyncAuthenticationTest {
    private val caps = """{"protocol_version":2,"upload_auth_required":true,"upload_enabled":true}"""
    private val credentials = ModSyncCredentials("app-key", "login-token")
    private fun client(vararg servers: MockWebServer, current: () -> Boolean = { true }) =
        ModSyncClient(servers.map { it.url("/").toString() }, OkHttpClient(), credentials, current)
    private fun ok(body: String = "{}") = MockResponse().setBody(body)

    @Test fun everyHostOperationCarriesCredentialsAndRoomContext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(caps))
            repeat(11) { server.enqueue(ok(if (it == 1) """{"missing":[]}""" else if (it == 8) """{"peers":[]}""" else "{}")) }
            val c = client(server)
            c.register(RoomRegisterRequest("code:Q1", "room-secret", "1.15", mods = emptyList()))
            c.checkFiles("code:Q1", "room-secret", emptyList())
            c.uploadFile("code:Q1", "a".repeat(64), "room-secret", byteArrayOf(1,2))
            val file = File.createTempFile("mod-auth", ".tmp")
            try { file.writeBytes(byteArrayOf(3,4)); c.uploadFile("code:Q1", "b".repeat(64), "room-secret", file) }
            finally { file.delete() }
            c.markReady("code:Q1", "room-secret")
            c.addAlias("code:Q1", "room-secret", "alias")
            c.heartbeat("code:Q1", "room-secret")
            c.clearPeers("code:Q1", "room-secret")
            c.listPeers("code:Q1", "room-secret")
            c.deletePeer("code:Q1", "peer", roomSecret = "room-secret")
            c.unregister("code:Q1", "room-secret")
            val capability = server.takeRequest(2,TimeUnit.SECONDS)!!
            assertNull(capability.getHeader("Authorization"))
            assertNull(capability.getHeader("X-App-Key"))
            repeat(11) { index ->
                val request = server.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals("Bearer login-token", request.getHeader("Authorization"))
                assertEquals("app-key", request.getHeader("X-App-Key"))
                if (index in 1..3) assertEquals("code:Q1", request.requestUrl!!.queryParameter("key"))
            }
        }
    }

    @Test fun oldMirrorReceivesNoCredentialsAndHostRequestsStayOnSelectedMirror() = runBlocking {
        MockWebServer().use { old -> MockWebServer().use { modern ->
            old.enqueue(MockResponse().setResponseCode(404))
            modern.enqueue(ok(caps)); modern.enqueue(ok()); modern.enqueue(MockResponse().setResponseCode(503))
            val c = client(old,modern)
            c.register(RoomRegisterRequest("room","s","1.15",mods=emptyList()))
            assertEquals(503, assertFailsWith<ModSyncException> { c.heartbeat("room","s") }.statusCode)
            assertEquals(1,old.requestCount)
            assertNull(old.takeRequest().getHeader("Authorization"))
            assertEquals(3,modern.requestCount)
        } }
    }

    @Test fun unauthorizedForbiddenAndRateLimitsNeverFailOver() = runBlocking {
        for (status in listOf(401,403,429)) {
            MockWebServer().use { a -> MockWebServer().use { b ->
                a.enqueue(ok(caps)); a.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After","4").setBody("""{"error":"denied"}"""))
                val error = assertFailsWith<ModSyncException> { client(a,b).register(RoomRegisterRequest("room","s","1.15",mods=emptyList())) }
                assertEquals(status,error.statusCode)
                assertEquals(4L,error.retryAfterSeconds)
                assertEquals(0,b.requestCount)
            } }
        }
    }

    @Test fun noLoginAndInvalidatedSessionCannotUploadButOldCredentialsCanOnlyCleanUp() = runBlocking {
        MockWebServer().use { server ->
            val anonymous = ModSyncClient(listOf(server.url("/").toString()),OkHttpClient())
            assertEquals(401,assertFailsWith<ModSyncException> { anonymous.uploadFile("room","a".repeat(64),"s",byteArrayOf(1)) }.statusCode)
            assertEquals(0,server.requestCount)
            var current = true
            val c = client(server) { current }
            server.enqueue(ok(caps)); server.enqueue(ok())
            c.register(RoomRegisterRequest("room","s","1.15",mods=emptyList()))
            current = false
            assertEquals(401,assertFailsWith<ModSyncException> { c.heartbeat("room","s") }.statusCode)
            assertEquals(2,server.requestCount)
            server.enqueue(ok()); c.unregister("room","s")
            assertEquals(3,server.requestCount)
        }
    }

    @Test fun manifestsAndDownloadsNeverSendAccountCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"status":"ready","game_version":"1.15","mods":[]}"""))
            val c = client(server)
            assertNotNull(c.fetchManifest("room"))
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("X-App-Key"))
            assertEquals("/api/v1/rooms/room",request.path)
            val bytes = "download".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val file = File.createTempFile("mod-anonymous", ".tmp")
            try { c.downloadFileTo(hash,bytes.size.toLong(),file); assertContentEquals(bytes,file.readBytes()) }
            finally { file.delete() }
            val download = server.takeRequest()
            assertNull(download.getHeader("Authorization")); assertNull(download.getHeader("X-App-Key"))
        }
    }

    @Test fun authenticatedRedirectIsNotFollowed() = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.enqueue(ok(caps)); a.enqueue(MockResponse().setResponseCode(307).setHeader("Location",b.url("/steal")))
            assertEquals(307,assertFailsWith<ModSyncException> { client(a).register(RoomRegisterRequest("room","s","1.15",mods=emptyList())) }.statusCode)
            assertEquals(0,b.requestCount)
        } }
    }
    @Test fun insecurePublicUploadAndDisabledCapabilitiesFailClosed() = runBlocking {
        val insecure = ModSyncClient(listOf("http://example.invalid"),OkHttpClient(),credentials)
        assertEquals(503,assertFailsWith<ModSyncException> { insecure.register(RoomRegisterRequest("room","s","1.15",mods=emptyList())) }.statusCode)
        MockWebServer().use { server ->
            server.enqueue(ok("""{"protocol_version":2,"upload_auth_required":true,"upload_enabled":false}"""))
            assertEquals(503,assertFailsWith<ModSyncException> { client(server).register(RoomRegisterRequest("room","s","1.15",mods=emptyList())) }.statusCode)
            assertEquals(1,server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun cancellingHostRequestClosesCallWithoutTryingAnotherMirror() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(ok(caps)); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val c = client(server,other)
            val task = launch(Dispatchers.IO) { c.register(RoomRegisterRequest("room","s","1.15",mods=emptyList())) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(0,other.requestCount)
        } }
    }

}
