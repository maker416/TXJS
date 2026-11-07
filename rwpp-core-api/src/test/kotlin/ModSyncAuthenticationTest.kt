/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
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
    @BeforeTest fun setup() {
        io.github.rwpp.logger = org.slf4j.LoggerFactory.getLogger(ModSyncAuthenticationTest::class.java)
    }

    private val caps = """{"protocol_version":2,"upload_auth_required":true,"upload_enabled":true}"""
    private val credentials = ModSyncCredentials("app-key", "login-token")
    private fun client(vararg servers: MockWebServer, current: () -> Boolean = { true }) =
        ModSyncClient(servers.map { it.url("/").toString() }, OkHttpClient(), credentials, current)
    private fun ok(body: String = "{}") = MockResponse().setBody(body)

    @Test fun preflightChecksCapabilitiesWithoutWritingAndUploadUsesTheSameNode() = runBlocking {
        MockWebServer().use { old -> MockWebServer().use { selected ->
            old.enqueue(MockResponse().setResponseCode(404))
            selected.enqueue(ok(caps))
            val c = client(old, selected)
            c.prepareHostUpload()
            assertEquals(1, old.requestCount)
            assertEquals(1, selected.requestCount)
            listOf(old, selected).forEach { server ->
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/api/v1/capabilities", request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("X-App-Key"))
            }
            selected.enqueue(ok())
            c.register(RoomRegisterRequest("room", "secret", "1.15", mods = emptyList()))
            assertEquals(1, old.requestCount)
            assertEquals(2, selected.requestCount)
            val upload = selected.takeRequest()
            assertEquals("/api/v1/rooms", upload.path)
            assertEquals("Bearer login-token", upload.getHeader("Authorization"))
        } }
    }

    @Test fun preflightRejectsInsecureDisabledAndUnavailableServicesWithoutRegistering() = runBlocking {
        val insecure = ModSyncClient(listOf("http://example.invalid"), OkHttpClient(), credentials)
        assertEquals("authenticated_upload_requires_https", assertFailsWith<ModSyncException> { insecure.prepareHostUpload() }.message)
        MockWebServer().use { server ->
            server.enqueue(ok("""{"protocol_version":2,"upload_auth_required":true,"upload_enabled":false}"""))
            assertEquals("authenticated_upload_unavailable", assertFailsWith<ModSyncException> { client(server).prepareHostUpload() }.message)
            assertEquals(1, server.requestCount)
            assertEquals("/api/v1/capabilities", server.takeRequest().path)
        }
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            server.shutdown()
            val unavailable = ModSyncClient(listOf(url), OkHttpClient(), credentials)
            assertFailsWith<java.net.ConnectException> { unavailable.prepareHostUpload() }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellingPreflightDoesNotTryAnotherMirrorOrProceedToUpload() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = launch(Dispatchers.IO) { client(server, other).prepareHostUpload() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, server.requestCount)
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun accountInvalidatedDuringPreflightCannotProceedToRegistration() = runBlocking {
        MockWebServer().use { server ->
            val current = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    current.set(false)
                    return ok(caps)
                }
            }
            val c = client(server) { current.get() }
            assertEquals(401, assertFailsWith<ModSyncException> { c.prepareHostUpload() }.statusCode)
            assertEquals(401, assertFailsWith<ModSyncException> {
                c.register(RoomRegisterRequest("room", "secret", "1.15", mods = emptyList()))
            }.statusCode)
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun everyHostOperationCarriesCredentialsAndRoomContext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(caps))
            repeat(11) { server.enqueue(ok(if (it == 1) """{"missing":[]}""" else if (it == 8) """{"peers":[]}""" else "{}")) }
            val c = client(server)
            c.register(RoomRegisterRequest("code:Q1", "room-secret", "1.15", mods = emptyList()))
            val hash = "a".repeat(64)
            c.checkFiles("code:Q1", "room-secret", listOf(hash, hash))
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
                if (index == 1) assertEquals(FilesCheckRequest(listOf(hash)), Json.decodeFromString<FilesCheckRequest>(request.body.readUtf8()))
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

    @Test fun guestDiscoversTheHostsMirrorAndKeepsAllRoomTrafficThere() = runBlocking {
        MockWebServer().use { old ->
            MockWebServer().use { selected ->
                val manifest = """{"status":"ready","mods":[]}"""
                old.enqueue(MockResponse().setResponseCode(404))
                selected.enqueue(ok(manifest))
                selected.enqueue(ok("""{"peer_secret":"issued"}""").setResponseCode(201))
                selected.enqueue(ok("""{"peers":[]}"""))
                selected.enqueue(ok(manifest))
                val data = byteArrayOf(8)
                val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                selected.enqueue(MockResponse().setBody(Buffer().write(data)))

                val room = assertNotNull(client(old, selected).locateManifest("room"))
                assertTrue(room.manifest.isReady)
                val name = "模".repeat(84) + "ab"
                val progress = SyncPeerUpsertRequest("guest", SyncPeerPhase.DOWNLOADING, name + "🚀extra")
                assertEquals("issued", room.client.upsertPeer("room", "p1", progress))
                assertTrue(room.client.listPeersAsPeer("room", "issued").isEmpty())
                assertEquals(room.manifest, room.client.fetchManifest("room"))
                val file = File.createTempFile("mod-pinned", ".tmp")
                try {
                    room.client.downloadFileTo(sha, data.size.toLong(), file)
                    assertContentEquals(data, file.readBytes())
                } finally {
                    file.delete()
                }

                assertEquals(1, old.requestCount)
                assertEquals("/api/v1/rooms/room", old.takeRequest().path)
                assertEquals(5, selected.requestCount)
                val requests = List(5) { assertNotNull(selected.takeRequest(2, TimeUnit.SECONDS)) }
                assertEquals(listOf(
                    "/api/v1/rooms/room",
                    "/api/v1/rooms/room/peers/p1",
                    "/api/v1/rooms/room/peers",
                    "/api/v1/rooms/room",
                    "/api/v1/files/$sha",
                ), requests.map { it.path })
                assertEquals("issued", requests[2].getHeader("X-Peer-Secret"))
                val sent = Json.decodeFromString<SyncPeerUpsertRequest>(requests[1].body.readUtf8())
                assertEquals(name, sent.currentModName)
                requests.forEach {
                    assertNull(it.getHeader("Authorization"))
                    assertNull(it.getHeader("X-App-Key"))
                }
            }
        }
    }

    @Test fun guestDiscoveryPreservesAnUnavailableMirrorError() = runBlocking {
        MockWebServer().use { old ->
            MockWebServer().use { unavailable ->
                old.enqueue(MockResponse().setResponseCode(404))
                unavailable.enqueue(MockResponse().setResponseCode(503))
                val error = assertFailsWith<ModSyncException> { client(old, unavailable).locateManifest("room") }
                assertEquals(503, error.statusCode)
                assertEquals(1, old.requestCount)
                assertEquals(1, unavailable.requestCount)
            }
        }
    }

}
