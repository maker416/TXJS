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
}
