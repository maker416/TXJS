/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountErrorCode
import io.github.rwpp.net.account.EmailCodePurpose
import io.github.rwpp.net.account.FriendRequestBox
import io.github.rwpp.net.account.RegisterRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AccountApiClient

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        client = AccountApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            appKey = "ak_test",
            http = OkHttpClient.Builder().build(),
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginSendsAppKeyAndUsernamePassword() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"jwt","user":{"id":1,"username":"alice","nickname":"Alice","status":1,"created_at":"t","has_avatar":false}}""",
            ),
        )
        val resp = client.login("alice", "passw0rd")
        assertEquals("jwt", resp.token)
        assertEquals("alice", resp.user.username)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/users/login", recorded.path)
        assertEquals("ak_test", recorded.getHeader("X-App-Key"))
        assertTrue(recorded.body.readUtf8().contains("\"username\""))
        assertEquals(null, recorded.getHeader("X-App-Secret"))
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test
    fun registerUsesDocumentedPathAnd201() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"user":{"id":2,"username":"bob","nickname":"Bob","status":1,"created_at":"t","has_avatar":false}}""",
            ),
        )
        val user = client.register(
            RegisterRequest("bob", "passw0rd12", "Bob", "bob@example.com", "123456"),
        )
        assertEquals(2, user.id)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/users/register", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"email\""))
        assertTrue(body.contains("\"code\""))
    }

    @Test
    fun sendEmailCodeUsesRegisterPurpose() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(client.sendEmailCode("a@b.com", EmailCodePurpose.REGISTER).ok)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/email/send-code", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"purpose\":\"register\""))
    }

    @Test
    fun authedGetMeSendsBearer() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"user":{"id":1,"username":"alice","nickname":"Alice","status":1,"created_at":"t","has_avatar":false}}""",
            ),
        )
        client.me("tok-1")
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/users/me", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        assertEquals("ak_test", recorded.getHeader("X-App-Key"))
    }

    @Test
    fun friendRequestsUseBoxQuery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"requests":[]}"""))
        client.listFriendRequests("tok", FriendRequestBox.INCOMING)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/friends/requests?box=incoming", recorded.path)
    }

    @Test
    fun sendMessageUsesUserIdAndBody() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"message":{"id":20,"conversation_id":5,"sender_id":1,"body":"hello","created_at":"t"}}""",
            ),
        )
        val msg = client.sendMessage("tok", 2, "hello")
        assertEquals(20, msg.id)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/chats/messages", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"user_id\""))
        assertTrue(body.contains("\"body\""))
    }

    @Test
    fun listMessagesUsesAfterId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"messages":[],"page_size":20}"""))
        client.listMessages("tok", 5, afterId = 20)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/chats/5/messages?after_id=20", recorded.path)
    }

    @Test
    fun errorBodyMapsDocumentedCode() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"username_taken","message":"request is invalid"}""",
            ),
        )
        val ex = assertFailsWith<AccountApiException> {
            client.register(RegisterRequest("alice", "passw0rd12", null, "a@b.com", "123456"))
        }
        assertEquals(AccountErrorCode.USERNAME_TAKEN, ex.code)
        assertEquals(409, ex.statusCode)
    }

    @Test
    fun unauthorizedWithoutJsonStillFails() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"unauthorized","message":"authentication failed"}""",
            ),
        )
        val ex = assertFailsWith<AccountApiException> { client.login("x", "yyyyyyyy") }
        assertEquals(AccountErrorCode.UNAUTHORIZED, ex.code)
    }

    @Test
    fun getPresenceUsesDocumentedPath() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"presence":{"user_id":2,"online":true,"last_active_at":"2026-09-23T10:00:00+08:00"}}""",
            ),
        )
        val presence = client.getPresence("tok", 2)
        assertTrue(presence.online)
        assertEquals(2, presence.userId)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/users/2/presence", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    }

    @Test
    fun updatePresenceSettingsOmitsUnspecifiedFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"settings":{"hide_from_strangers":true,"hide_from_friends":false}}""",
            ),
        )
        val settings = client.updatePresenceSettings("tok", hideFromStrangers = true)
        assertTrue(settings.hideFromStrangers)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/users/me/presence-settings", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"hide_from_strangers\":true"), body)
        assertTrue(!body.contains("hide_from_friends"), body)
    }

    @Test
    fun getPresenceSettingsUsesDocumentedPath() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"settings":{"hide_from_strangers":false,"hide_from_friends":true}}""",
            ),
        )
        val settings = client.getPresenceSettings("tok")
        assertTrue(settings.hideFromFriends)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/users/me/presence-settings", recorded.path)
    }

    @Test
    fun changeEmailFlowUsesDocumentedPaths() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.sendChangeEmailCode("tok", "new@example.com")
        val codeReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/users/change-email/send-code", codeReq.path)
        assertTrue(codeReq.body.readUtf8().contains("new@example.com"))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"user":{"id":1,"username":"alice","email":"new@example.com","nickname":"Alice","status":1,"created_at":"t","has_avatar":false}}""",
            ),
        )
        val user = client.changeEmail("tok", "new@example.com", "123456")
        assertEquals("new@example.com", user.email)
        val confirmReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/users/change-email", confirmReq.path)
        val body = confirmReq.body.readUtf8()
        assertTrue(body.contains("\"code\":\"123456\""), body)
    }

    @Test
    fun listFriendsParsesPresenceFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"friends":[{"user":{"id":2,"username":"bob","nickname":"Bob","status":1},"since":"t","online":true,"last_active_at":"2026-09-23T10:00:00+08:00"}]}""",
            ),
        )
        val friends = client.listFriends("tok")
        assertEquals(1, friends.size)
        assertTrue(friends[0].online)
        assertEquals("2026-09-23T10:00:00+08:00", friends[0].lastActiveAt)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/friends", recorded.path)
    }

    @Test
    fun listPointLedgersUsesQueryParams() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ledgers":[],"page":2,"page_size":50,"total":0,"total_pages":1}""",
            ),
        )
        val resp = client.listPointLedgers("tok", page = 2, pageSize = 50, pointTypeId = 3)
        assertEquals(2, resp.page)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/points/ledgers?page=2&page_size=50&point_type_id=3", recorded.path)
    }

    @Test
    fun listPointsUsesDocumentedPath() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"points":[{"id":1,"code":"gold","name":"金币","balance":10,"status":1}]}""",
            ),
        )
        val points = client.listPoints("tok")
        assertEquals(10, points.single().balance)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/points", recorded.path)
    }

    @Test
    fun blockAndUnblockUseDocumentedPaths() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.block("tok", 2)
        val blockReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", blockReq.method)
        assertEquals("/api/v1/blocks", blockReq.path)
        assertTrue(blockReq.body.readUtf8().contains("\"user_id\":2"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.unblock("tok", 2)
        val unblockReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", unblockReq.method)
        assertEquals("/api/v1/blocks/2", unblockReq.path)
    }

    @Test
    fun uploadAvatarSendsMultipartFilePart() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"has_avatar":true}"""))
        val hasAvatar = client.uploadAvatar("tok", byteArrayOf(1, 2, 3), "a.jpg", "image/jpeg")
        assertTrue(hasAvatar)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/users/me/avatar", recorded.path)
        val contentType = recorded.getHeader("Content-Type").orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data"), contentType)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"file\""), body)
        assertTrue(body.contains("a.jpg"), body)
    }

    @Test
    fun deleteAvatarParsesHasAvatar() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"has_avatar":false}"""))
        assertTrue(!client.deleteAvatar("tok"))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/users/me/avatar", recorded.path)
    }

    @Test
    fun getAvatarReturnsBytes() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setBody("jpeg-bytes"),
        )
        val bytes = client.getAvatar("tok", 2)
        assertEquals("jpeg-bytes", bytes?.decodeToString())
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/users/2/avatar", recorded.path)
    }

    @Test
    fun getAvatarReturnsNullOn404() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"code":"not_found","message":"not found"}""",
            ),
        )
        assertNull(client.getAvatar("tok", 2))
    }
}
