/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.Config
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.roomid.RoomIdentityClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用延迟的真实 HTTP 响应重现恢复/切号竞态和房间身份隐私门控。 */
class AccountSessionIsolationTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: AccountPreferences
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val gates = mutableListOf<Gate>()
    private var response: (RecordedRequest) -> MockResponse = { defaultResponse(it) }

    @BeforeTest
    fun setup() = runBlocking {
        logger = LoggerFactory.getLogger("AccountSessionIsolationTest")
        RoomIdentityController.resetForTests()
        AccountSession.resetForTests()
        prefs = AccountPreferences(token = "token-A")
        val configIO = object : ConfigIO {
            override fun saveConfig(config: Config) = Unit
            override fun <T : Config> readConfig(clazz: KClass<T>): T? = null
            override fun <T : Config> deleteConfig(clazz: KClass<T>) = Unit
            override fun saveSingleConfig(group: String, key: String, value: Any?) = Unit
            override fun readSingleConfig(group: String, key: String): String? = null
            override fun <T> getGameConfig(name: String): T {
                @Suppress("UNCHECKED_CAST")
                return "Alice" as T
            }
            override fun setGameConfig(name: String, value: Any?) = Unit
        }
        appKoin = startKoin { modules(module { single { prefs }; single<ConfigIO> { configIO } }) }.koin
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return response(request)
            }
        }
        server.start()
        val http = OkHttpClient()
        AccountSession.bindClient(AccountApiClient(server.url("/").toString(), "ak_test", http))
        RoomIdentityController.clientOverride = RoomIdentityClient(listOf(server.url("/").toString()), http)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        gates.forEach { it.release.countDown() }
        RoomIdentityController.resetForTests()
        AccountSession.resetForTests()
        server.shutdown()
        stopKoin()
    }

    @Test
    fun delayedRestoreCannotReplaceManualLogin() = runBlocking {
        val gate = block("/api/v1/users/me", userResponse("alice"))
        val pending = async { AccountSession.restoreIfNeeded() }
        gate.await()
        AccountSession.login("bob", "passw0rd")
        gate.release.countDown()
        pending.await()
        assertBob()
        assertEquals("token-B", prefs.token)
        assertFalse(AccountSession.restoring)
    }

    @Test
    fun delayedRestoreUnauthorizedCannotClearManualLogin() = runBlocking {
        val gate = block("/api/v1/users/me", json("""{"code":"unauthorized"}""", 401))
        val pending = async { AccountSession.restoreIfNeeded() }
        gate.await()
        AccountSession.login("bob", "passw0rd")
        gate.release.countDown()
        pending.await()
        assertBob()
        assertEquals("token-B", prefs.token)
    }

    @Test
    fun duplicateRestoreDoesNotStartAnotherRequest() = runBlocking {
        val gate = block("/api/v1/users/me", userResponse("alice"))
        val pending = async { AccountSession.restoreIfNeeded() }
        gate.await()
        AccountSession.restoreIfNeeded()
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/api/v1/users/me" })
        gate.release.countDown()
        pending.await()
        assertEquals("alice", AccountSession.username)
    }

    @Test
    fun delayedRestoreCannotUndoLogout() = runBlocking {
        val gate = block("/api/v1/users/me", userResponse("alice"))
        val pending = async { AccountSession.restoreIfNeeded() }
        gate.await()
        AccountSession.logout()
        gate.release.countDown()
        pending.await()
        assertFalse(AccountSession.loggedIn)
        assertEquals("", prefs.token)
    }

    @Test
    fun delayedProfileCannotPairOldUserWithNewToken() = runBlocking {
        loginAlice()
        val gate = block("/api/v1/users/me", userResponse("alice"))
        val pending = async { AccountSession.refreshProfile() }
        gate.await()
        AccountSession.login("bob", "passw0rd")
        gate.release.countDown()
        pending.await()
        assertBob()
    }

    @Test
    fun delayedLogoutCannotClearNewLogin() = runBlocking {
        loginAlice()
        val gate = block("/api/v1/users/logout", json("""{"ok":true}"""))
        val pending = async { AccountSession.logout() }
        gate.await()
        AccountSession.login("bob", "passw0rd")
        gate.release.countDown()
        pending.await()
        assertBob()
    }

    @Test
    fun delayedPrivacyResponseCannotBecomeNewAccountsSetting() = runBlocking {
        loginAlice()
        val gate = block("/api/v1/users/me/presence-settings", settings(false))
        val pending = async { AccountSession.refreshPresenceSettings() }
        gate.await()
        AccountSession.login("bob", "passw0rd")
        gate.release.countDown()
        pending.await()
        assertBob()
        assertNull(AccountSession.presenceSettings)
    }

    @Test
    fun restoredHiddenAccountDoesNotPublishBeforeOpeningAccountPage() = runBlocking {
        response = {
            if (it.requestUrl!!.encodedPath == "/api/v1/users/me/presence-settings") settings(true)
            else defaultResponse(it)
        }
        AccountSession.restoreIfNeeded()
        assertNull(AccountSession.presenceSettings)
        RoomIdentityController.onJoiningRoom("Q12345", null)
        RoomIdentityController.publishOnce()
        assertTrue(AccountSession.presenceSettings!!.hideFromStrangers)
        assertFalse(requests.any { it.method == "PUT" })
    }

    @Test
    fun failedPrivacyLoadKeepsIdentityPrivateAndLaterRetries() = runBlocking {
        loginAlice()
        var fail = true
        response = {
            if (it.requestUrl!!.encodedPath == "/api/v1/users/me/presence-settings") {
                if (fail) json("""{"code":"internal_error"}""", 500) else settings(false)
            } else defaultResponse(it)
        }
        RoomIdentityController.onJoiningRoom("Q12345", null)
        RoomIdentityController.publishOnce()
        assertNull(AccountSession.presenceSettings)
        assertFalse(requests.any { it.method == "PUT" })
        fail = false
        RoomIdentityController.publishOnce()
        assertEquals(1, requests.count { it.method == "PUT" })
        val privacyIndex = requests.indexOfLast { it.requestUrl!!.encodedPath == "/api/v1/users/me/presence-settings" }
        assertTrue(privacyIndex < requests.indexOfFirst { it.method == "PUT" })
    }

    @Test
    fun enablingHiddenModeRevokesEvenAnAlreadyRunningPublish() = runBlocking {
        loginAlice()
        AccountSession.refreshPresenceSettings()
        RoomIdentityController.onJoiningRoom("Q12345", null)
        val gate = block("/api/v1/roomid/publish", json("""{"ok":true}"""), method = "PUT")
        val baseResponse = response
        response = {
            if (it.requestUrl!!.encodedPath == "/api/v1/users/me/presence-settings" && it.method == "POST") settings(true)
            else baseResponse(it)
        }
        val publishing = async { RoomIdentityController.publishOnce() }
        gate.await()
        val hiding = async { AccountSession.updatePresenceSettings(true, null) }
        withTimeout(3_000) {
            while (AccountSession.presenceSettings?.hideFromStrangers != true) delay(10)
        }
        assertFalse(hiding.isCompleted)
        gate.release.countDown()
        publishing.await()
        hiding.await()
        RoomIdentityController.publishOnce()
        assertEquals(1, requests.count { it.method == "PUT" })
        assertEquals(1, requests.count { it.method == "DELETE" })
        assertTrue(requests.indexOfFirst { it.method == "PUT" } < requests.indexOfFirst { it.method == "DELETE" })
    }

    @Test
    fun expiredOldPublicationTokenCannotStopNewAccountPublishing() = runBlocking {
        loginAlice()
        AccountSession.refreshPresenceSettings()
        RoomIdentityController.onJoiningRoom("Q12345", null)
        assertTrue(RoomIdentityController.publishOnce())
        AccountSession.login("bob", "passw0rd")
        response = {
            if (it.requestUrl!!.encodedPath == "/api/v1/roomid/publish" &&
                it.method == "DELETE" && it.getHeader("Authorization") == "Bearer token-A"
            ) json("""{"error":"invalid token"}""", 401)
            else defaultResponse(it)
        }

        assertTrue(RoomIdentityController.publishOnce())
        assertBob()
        assertEquals(1, requests.count { it.method == "DELETE" && it.getHeader("Authorization") == "Bearer token-A" })
        assertEquals(1, requests.count { it.method == "PUT" && it.getHeader("Authorization") == "Bearer token-B" })
        assertTrue(RoomIdentityController.publishOnce())
        assertEquals(1, requests.count { it.method == "DELETE" && it.getHeader("Authorization") == "Bearer token-A" },
            "失效的旧撤销凭据不得在每个周期反复阻塞新公示")
    }

    @Test
    fun unauthorizedCurrentPublicationStillStopsPublishingLoop() = runBlocking {
        loginAlice()
        AccountSession.refreshPresenceSettings()
        RoomIdentityController.onJoiningRoom("Q12345", null)
        response = {
            if (it.requestUrl!!.encodedPath == "/api/v1/roomid/publish" && it.method == "PUT") {
                json("""{"error":"invalid token"}""", 401)
            } else defaultResponse(it)
        }
        assertFalse(RoomIdentityController.publishOnce())
    }

    private fun loginAlice() {
        AccountSession.applyPreview(AccountUser(1, "alice", nickname = "Alice"), "token-A")
        AccountSession.networkEnabled = true
    }

    private fun assertBob() {
        assertTrue(AccountSession.loggedIn)
        assertEquals("bob", AccountSession.username)
        assertEquals("token-B", AccountSession.token)
    }

    private fun block(path: String, reply: MockResponse, method: String? = null): Gate {
        val gate = Gate().also { gates.add(it) }
        val previous = response
        response = {
            if (it.requestUrl!!.encodedPath == path && (method == null || it.method == method)) {
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "没有释放模拟响应" }
                reply
            } else previous(it)
        }
        return gate
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        suspend fun await() = withContext(Dispatchers.IO) {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "未收到预期请求")
        }
    }

    private fun defaultResponse(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
        "/api/v1/users/me" -> userResponse("alice")
        "/api/v1/users/login" -> json("""{"token":"token-B","user":{"id":2,"username":"bob","nickname":"Bob"}}""")
        "/api/v1/users/me/presence-settings" -> settings(false)
        "/api/v1/presence/heartbeat", "/api/v1/users/logout", "/api/v1/roomid/publish" -> json("""{"ok":true}""")
        else -> json("""{"code":"not_found"}""", 404)
    }

    private fun userResponse(username: String) = json("""{"user":{"id":1,"username":"$username","nickname":"Alice"}}""")
    private fun settings(hidden: Boolean) = json("""{"settings":{"hide_from_strangers":$hidden,"hide_from_friends":false}}""")
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)
}