/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.ForumSsoSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.*
import io.github.rwpp.logger
import io.github.rwpp.net.account.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.*

class ForumSsoSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: AccountPreferences
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var prepareGate: CountDownLatch? = null
    private var entered: CountDownLatch? = null
    private var revokeFails = false
    private var ticketDenied = false

    @BeforeTest fun setup() {
        logger = LoggerFactory.getLogger("ForumSsoSessionTest")
        AccountSession.resetForTests()
        prefs = AccountPreferences()
        val configIO = object : ConfigIO {
            override fun saveConfig(config: Config) = Unit
            override fun <T : Config> readConfig(clazz: KClass<T>): T? = null
            override fun <T : Config> deleteConfig(clazz: KClass<T>) = Unit
            override fun saveSingleConfig(group: String, key: String, value: Any?) = Unit
            override fun readSingleConfig(group: String, key: String): String? = null
            override fun <T> getGameConfig(name: String): T = error("unused")
            override fun setGameConfig(name: String, value: Any?) = Unit
        }
        appKoin = startKoin { modules(module { single { prefs }; single<ConfigIO> { configIO } }) }.koin
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/sso/client/config" -> MockResponse().setBody("""{"protocol":1,"target_app_code":"forum"}""")
                    "/sso/client" -> MockResponse().setHeader("X-CSRF-Token", "csrf").setBody("bootstrap")
                    "/api/v1/sso/forum/tickets" -> if (ticketDenied) MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","message":"authentication failed"}""") else MockResponse().setBody("""{"ticket":"${"a".repeat(64)}","expires_in":60}""")
                    "/sso/client/prepare" -> {
                        entered?.countDown()
                        prepareGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
                        MockResponse().setBody("""{"handoff":"${"b".repeat(64)}","logout_key":"${"c".repeat(64)}","expires_in":60}""")
                    }
                    "/sso/client/revoke" -> MockResponse().setResponseCode(if (revokeFails) 503 else 200).setBody("""{"ok":true}""")
                    else -> MockResponse().setBody("""{"ok":true}""")
                }
            }
        }
        server.start()
        val http = OkHttpClient()
        AccountSession.bindClient(AccountApiClient(server.url("/").toString(), "source-key", http), http)
        AccountSession.applyPreview(AccountUser(1, "alice", nickname = "Alice"), "source-token")
        AccountSession.networkEnabled = true
    }

    @AfterTest fun cleanup() = runBlocking {
        prepareGate?.countDown()
        prefs.forumRevocations = emptyList()
        AccountSession.resetForTests()
        delay(50)
        stopKoin()
        server.shutdown()
    }

    @Test fun logoutRevokesForumSessionEvenWithoutBrowser() = runBlocking {
        val prepared = ForumSsoSession.prepare(server.url("/").toString(), AccountSession.sessionSnapshotOrNull())!!
        assertEquals(prepared.logoutKey, prefs.forumRevocations.single().key)
        AccountSession.logout()
        withTimeout(3000) { while (prefs.forumRevocations.isNotEmpty()) delay(10) }
        assertTrue(requests.any { it.path == "/sso/client/revoke" })
        assertFalse(AccountSession.loggedIn)
    }

    @Test fun ticketDenialKeepsFailedStepAndStatusWithoutSubmittingToForum() = runBlocking {
        ticketDenied = true
        val failure = assertFailsWith<ForumSsoStepException> { ForumSsoSession.prepare(server.url("/").toString(), AccountSession.sessionSnapshotOrNull()) }
        assertEquals(ForumSsoStep.TICKET, failure.step)
        assertEquals(401, failure.statusCode)
        assertTrue(requests.none { it.path == "/sso/client/prepare" })
        assertTrue(prefs.forumRevocations.isEmpty())
    }

    @Test fun lateNativePrepareAfterLogoutIsRevokedAndCannotReachBrowser() = runBlocking {
        prepareGate = CountDownLatch(1); entered = CountDownLatch(1)
        val session = AccountSession.sessionSnapshotOrNull()
        val pending = async { runCatching { ForumSsoSession.prepare(server.url("/").toString(), session) } }
        withContext(Dispatchers.IO) { assertTrue(entered!!.await(3, TimeUnit.SECONDS)) }
        AccountSession.logout()
        prepareGate!!.countDown()
        assertTrue(pending.await().isFailure)
        assertTrue(prefs.forumRevocations.isEmpty())
        assertTrue(requests.any { it.path == "/sso/client/revoke" })
    }

    @Test fun offlineRevocationIsRetainedAndBlocksNewSessionUntilRetrySucceeds() = runBlocking {
        ForumSsoSession.prepare(server.url("/").toString(), AccountSession.sessionSnapshotOrNull())
        revokeFails = true
        AccountSession.logout()
        withTimeout(3000) { while (requests.none { it.path == "/sso/client/revoke" }) delay(10) }
        assertEquals(1, prefs.forumRevocations.size)
        assertFailsWith<IllegalStateException> { ForumSsoSession.prepare(server.url("/").toString(), null) }
        revokeFails = false
        assertNull(ForumSsoSession.prepare(server.url("/").toString(), null))
        assertTrue(prefs.forumRevocations.isEmpty())
    }
}
