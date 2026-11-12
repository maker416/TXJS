/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.Config
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.roomid.RoomIdentityClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实身份/账号客户端经离线 HTTP 拦截器验证头像归属、成员更替及迟到响应。 */
class RoomIdentityPlayerAccountsTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val gates = mutableListOf<Gate>()
    @Volatile private var response: (Request) -> Reply = { defaultResponse(it) }
    @Volatile private var members: List<Player> = emptyList()
    private lateinit var self: Member
    private lateinit var remote: Member
    private lateinit var room: GameRoom
    private var now = 0L

    @BeforeTest
    fun setup() = runBlocking {
        logger = LoggerFactory.getLogger("RoomIdentityPlayerAccountsTest")
        val config = object : ConfigIO {
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
        appKoin = startKoin { modules(module {
            single { Settings(autoCheckUpdate = false, enableAnimations = false) }
            single { AccountPreferences(appKey = "ak_test") }
            single<ConfigIO> { config }
        }) }.koin
        // AccountSession.resetForTests 会经 FriendsSession.clear 触碰 UI 单例；先准备其 Settings。
        RoomIdentityController.resetForTests()
        AccountSession.resetForTests()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val reply = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code)
                .message("test").body(reply.body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        AccountSession.bindClient(AccountApiClient("http://127.0.0.1:19001", "ak_test", http))
        RoomIdentityController.clientOverride = RoomIdentityClient(listOf("http://127.0.0.1:19001"), http)
        AccountSession.applyPreview(AccountUser(1, "alice", nickname = "Alice", hasAvatar = true), "token-A")
        AccountSession.networkEnabled = true
        self = Member("Alice", "connection-self")
        remote = Member("Guest", "connection-guest")
        members = listOf(self.player, remote.player)
        room = stub(GameRoom::class.java) { method -> when (method) {
            "getPlayers" -> members
            "getLocalPlayer" -> self.player
            else -> error("Unexpected room access: $method")
        } }
        RoomIdentityController.playerAccountClock = { now }
        RoomIdentityController.onJoiningRoom("Q12345", null)
    }

    @AfterTest
    fun cleanup() = runBlocking {
        gates.forEach { it.release.countDown() }
        RoomIdentityController.resetForTests()
        AccountSession.resetForTests()
        stopKoin()
    }

    @Test
    fun uniqueVerifiedMemberUsesPublishedUsernameAndCachesPublicProfile() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        val user = assertNotNull(RoomIdentityController.playerAccountFor(remote.player, room))
        assertEquals(2L, user.id)
        assertEquals("bob", user.username)
        assertEquals("Different account nickname", user.nickname)
        assertTrue(user.hasAvatar)
        assertTrue(RoomIdentityController.playerAvatarVersionFor(remote.player, room) > 0)
        assertNull(RoomIdentityController.playerAccountFor(self.player, room), "自己头像直接由账号会话提供")
        assertEquals(true, RoomIdentityController.cardAvailability["Guest"])
        val profileRequest = requests.single { it.url.encodedPath == "/api/v1/users/lookup" }
        assertEquals("bob", profileRequest.url.queryParameter("username"))
        assertEquals("Bearer token-A", profileRequest.header("Authorization"))

        repeat(3) { RoomIdentityController.prefetchPlayerCards(room) }
        assertEquals(4, count("/api/v1/roomid/lookup"), "成员核验继续每轮进行")
        assertEquals(1, count("/api/v1/users/lookup"), "公开资料不应每秒重复查询")
        assertEquals(0, count("/api/v1/presence"))
    }

    @Test
    fun profileTtlRefreshesHasAvatarAndOnlyThenAdvancesImageVersion() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        val version = RoomIdentityController.playerAvatarVersionFor(remote.player, room)
        now = 29_999
        RoomIdentityController.prefetchPlayerCards(room)
        assertEquals(version, RoomIdentityController.playerAvatarVersionFor(remote.player, room))
        assertEquals(1, count("/api/v1/users/lookup"))

        response = { if (it.url.encodedPath == "/api/v1/users/lookup") publicUser(hasAvatar = false) else defaultResponse(it) }
        now = 30_000
        RoomIdentityController.prefetchPlayerCards(room)
        assertFalse(assertNotNull(RoomIdentityController.playerAccountFor(remote.player, room)).hasAvatar)
        val refreshedVersion = RoomIdentityController.playerAvatarVersionFor(remote.player, room)
        assertTrue(refreshedVersion > version)
        assertEquals(2, count("/api/v1/users/lookup"))

        RoomIdentityController.onJoiningRoom("Q54321", null)
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAvatarVersionFor(remote.player, room) > refreshedVersion,
            "换房不能重用旧图片缓存版本")
    }

    @Test
    fun duplicateLocalMemberNamesIncludingSelfAreNeverAssignedAnAccount() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccounts["Guest"])
        val duplicate = Member(" Guest ", "connection-second")
        members = members + duplicate.player
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertEquals(true, RoomIdentityController.cardAvailability["Guest"], "同名成员仍可查看既有名片查询结果")
        assertEquals(2, count("/api/v1/roomid/lookup"))

        remote.name = "Alice"
        members = listOf(self.player, remote.player)
        RoomIdentityController.prefetchPlayerCards(room)
        assertNull(RoomIdentityController.playerAccountFor(remote.player, room))
        assertEquals(3, count("/api/v1/roomid/lookup"), "名片查询仍保留同自己重名的其他成员")
        assertEquals(true, RoomIdentityController.cardAvailability["Alice"])
        assertEquals(1, count("/api/v1/users/lookup"), "重名不能触发头像账号猜测或缓存")
    }

    @Test
    fun replacementPlayerAndChangedConnectionCannotReuseThePreviousAvatar() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        val replacement = Member("Guest", "connection-guest")
        members = listOf(self.player, replacement.player)
        assertNull(RoomIdentityController.playerAccountFor(replacement.player, room),
            "相同名字和连接id也不能把旧Player引用的头像交给新成员")
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccountFor(replacement.player, room))
        assertEquals(2, count("/api/v1/users/lookup"))
        replacement.connectionId = "connection-replaced"
        assertNull(RoomIdentityController.playerAccountFor(replacement.player, room))
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccountFor(replacement.player, room))
        assertEquals(3, count("/api/v1/users/lookup"))
        members = listOf(self.player)
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
    }

    @Test
    fun ambiguousServerResultAndMismatchedAccountIdsAreNotTrusted() = runBlocking {
        response = { request -> if (request.url.encodedPath == "/api/v1/roomid/lookup") Reply("""
            {"users":[{"user_id":2,"username":"bob","nickname":"Guest"},{"user_id":3,"username":"other","nickname":"Guest"}]}
        """.trimIndent()) else defaultResponse(request) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertEquals(true, RoomIdentityController.cardAvailability["Guest"], "多候选仍须保留歧义名片入口")
        assertEquals(0, count("/api/v1/users/lookup"))

        response = { if (it.url.encodedPath == "/api/v1/users/lookup") publicUser(id = 99) else defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertNull(RoomIdentityController.playerAccountFor(remote.player, room))
        assertTrue(RoomIdentityController.playerAccounts.isEmpty(), "公开lookup返回其他id不得配上该成员头像")
        assertEquals(1, count("/api/v1/users/lookup"))
    }

    @Test
    fun ambiguousMembershipKeepsTheExistingProfileDialogAccessible() = runBlocking {
        response = { request -> when (request.url.encodedPath) {
            "/api/v1/roomid/lookup" -> Reply("""
                {"users":[{"user_id":2,"username":"bob","nickname":"Guest"},{"user_id":3,"username":"other","nickname":"Guest"}]}
            """.trimIndent())
            "/api/v1/users/lookup" -> if (request.url.queryParameter("username") == "other") {
                Reply("""{"user":{"id":3,"username":"other","nickname":"Other account","has_avatar":true}}""")
            } else publicUser()
            else -> defaultResponse(request)
        } }
        RoomIdentityController.prefetchPlayerCards(room)
        assertEquals(true, RoomIdentityController.cardAvailability["Guest"])
        assertNull(RoomIdentityController.playerAccountFor(remote.player, room))
        assertEquals(0, count("/api/v1/users/lookup"), "头像预取不能自行挑选歧义候选")

        val result = assertIs<RoomIdentityResult.Ambiguous>(RoomIdentityController.resolvePlayer(remote.player, room))
        assertEquals(setOf(2L, 3L), result.users.map { it.id }.toSet())
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
    }

    @Test
    fun membershipFailureAndFailedProfileRefreshRemoveCachedAccounts() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        response = { if (it.url.encodedPath == "/api/v1/roomid/lookup") Reply("server failed", 500) else defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertNull(RoomIdentityController.cardAvailability["Guest"])

        response = { defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccountFor(remote.player, room))
        now = 30_000
        response = { if (it.url.encodedPath == "/api/v1/users/lookup") Reply("""{"code":"internal_error"}""", 500) else defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertEquals(0, RoomIdentityController.playerAvatarVersionFor(remote.player, room))
    }

    @Test
    fun unsupportedMembershipCapabilityClearsAccountsAndNeverQueriesPublicProfiles() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        response = { if (it.url.encodedPath == "/api/v1/capabilities") Reply("""{"room_id_membership_verified":false}""") else defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertTrue(RoomIdentityController.featureUnavailable)
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        val lookups = count("/api/v1/roomid/lookup")
        RoomIdentityController.prefetchPlayerCards(room)
        assertEquals(lookups, count("/api/v1/roomid/lookup"))
        assertEquals(1, count("/api/v1/users/lookup"))
    }

    @Test
    fun latePublicProfileCannotBelongToAReplacementOrNewDuplicateMember() = runBlocking {
        val gate = blockProfile()
        val pending = async { RoomIdentityController.prefetchPlayerCards(room) }
        gate.await()
        members = listOf(self.player, Member("Guest", "new-connection").player)
        gate.release.countDown()
        pending.await()
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())

        response = { defaultResponse(it) }
        val duplicateGate = blockProfile()
        val duplicatePending = async { RoomIdentityController.prefetchPlayerCards(room) }
        duplicateGate.await()
        members = members + Member(" Guest ", "duplicate-connection").player
        duplicateGate.release.countDown()
        duplicatePending.await()
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
    }

    @Test
    fun roomEpochDiscardsLateProfilesAndLeavingClearsVisibleAccounts() = runBlocking {
        val gate = blockProfile()
        val pending = async { RoomIdentityController.prefetchPlayerCards(room) }
        gate.await()
        RoomIdentityController.onJoiningRoom("Q54321", null)
        gate.release.countDown()
        pending.await()
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        response = { defaultResponse(it) }
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccountFor(remote.player, room))
        RoomIdentityController.stopPublishing()?.join()
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertNull(RoomIdentityController.playerAccountFor(remote.player, room))
    }

    @Test
    fun accountChangeAndLogoutClearAccountsAndRejectLateResponses() = runBlocking {
        RoomIdentityController.prefetchPlayerCards(room)
        assertNotNull(RoomIdentityController.playerAccountFor(remote.player, room))
        AccountSession.applyPreview(AccountUser(3, "charlie", nickname = "Charlie"), "token-C")
        assertTrue(RoomIdentityController.playerAccounts.isEmpty(), "切号立即清缓存，无需等下一秒轮询")
        AccountSession.networkEnabled = true
        members = members + Member("Second guest", "connection-second-guest").player
        val gate = blockProfile()
        val pending = async { RoomIdentityController.prefetchPlayerCards(room) }
        gate.await()
        AccountSession.applyLoggedOutPreview()
        gate.release.countDown()
        pending.await()
        assertTrue(RoomIdentityController.playerAccounts.isEmpty())
        assertNull(RoomIdentityController.playerAccountFor(remote.player, room))
        assertFalse(requests.any { it.url.encodedPath == "/api/v1/roomid/lookup" &&
            it.url.queryParameter("player_name") == "Second guest" },
            "登出作废代次后，不能继续带旧token查询剩余成员")
    }

    private fun count(path: String) = requests.count { it.url.encodedPath == path }

    private fun defaultResponse(request: Request): Reply = when (request.url.encodedPath) {
        "/api/v1/capabilities" -> Reply("""{"room_id_membership_verified":true}""")
        "/api/v1/roomid/lookup" -> Reply("""{"users":[{"user_id":2,"username":"bob","nickname":"Guest"}]}""")
        "/api/v1/users/lookup" -> publicUser()
        "/api/v1/users/logout", "/api/v1/roomid/publish" -> Reply("""{"ok":true}""")
        else -> error("Unexpected request: ${request.method} ${request.url}")
    }

    private fun publicUser(id: Long = 2, hasAvatar: Boolean = true) = Reply("""
        {"user":{"id":$id,"username":"bob","nickname":"Different account nickname","has_avatar":$hasAvatar}}
    """.trimIndent())

    private fun blockProfile(): Gate {
        val gate = Gate().also(gates::add)
        val previous = response
        response = { request ->
            if (request.url.encodedPath == "/api/v1/users/lookup") {
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "没有释放账号资料响应" }
            }
            previous(request)
        }
        return gate
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        suspend fun await() = withContext(Dispatchers.IO) {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "未收到账号资料请求")
        }
    }

    private data class Reply(val body: String, val code: Int = 200)

    private class Member(@Volatile var name: String, @Volatile var connectionId: String, val ai: Boolean = false) {
        val player: Player = stub(Player::class.java) { method -> when (method) {
            "getName" -> name
            "getConnectHexId" -> connectionId
            "isAI" -> ai
            else -> error("Unexpected player access: $method")
        } }
    }

    companion object {
        private fun <T> stub(type: Class<T>, value: (String) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "equals" -> instance === args!![0]
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RoomIdentity${type.simpleName}"
                else -> value(method.name)
            } },
        )
    }
}
