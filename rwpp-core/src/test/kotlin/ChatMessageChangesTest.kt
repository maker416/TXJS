/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.Config
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.ChatItem
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.PublicUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlin.test.assertTrue

/** 用真实 HTTP 请求验证领取、替换、确认的顺序及失败重试，避免提前 ACK 丢失封禁通知。 */
class ChatMessageChangesTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: AccountPreferences
    private var savedPrefs: AccountPreferences? = null
    private var failSave = false
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val ackSnapshots = CopyOnWriteArrayList<Pair<List<ChatMessageDto>, List<ChatItem>>>()
    private var changesResponse: (RecordedRequest) -> MockResponse = { json("""{"changes":[],"has_more":false}""") }
    private var ackResponse: () -> MockResponse = { json("""{"ok":true}""") }
    private val peer = PublicUser(2, "bob", "Bob")
    private val original = ChatMessageDto(20, 5, 1, "hello", "created")

    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("ChatMessageChangesTest")
        AccountSession.resetForTests()
        prefs = AccountPreferences()
        val configIO = object : ConfigIO {
            override fun saveConfig(config: Config) {
                check(!failSave) { "save failed" }
                savedPrefs = (config as AccountPreferences).copy()
            }
            override fun <T : Config> readConfig(clazz: KClass<T>): T? = null
            override fun <T : Config> deleteConfig(clazz: KClass<T>) = Unit
            override fun saveSingleConfig(group: String, key: String, value: Any?) = Unit
            override fun readSingleConfig(group: String, key: String): String? = null
            override fun <T> getGameConfig(name: String): T = error("unused")
            override fun setGameConfig(name: String, value: Any?) = Unit
        }
        startKoin { modules(module { single { prefs }; single<ConfigIO> { configIO } }) }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.requestUrl!!.encodedPath) {
                    "/api/v1/friends" -> json("""{"friends":[]}""")
                    "/api/v1/friends/requests" -> json("""{"requests":[]}""")
                    "/api/v1/blocks" -> json("""{"blocks":[]}""")
                    "/api/v1/chats" -> json("""
                        {"chats":[{"id":5,"peer":{"id":2,"username":"bob","nickname":"Bob"},
                          "last_message":{"id":20,"conversation_id":5,"sender_id":1,"body":"hello","created_at":"created"},
                          "unread":3,"updated_at":"updated"}]}
                    """.trimIndent())
                    "/api/v1/chats/message-changes" -> changesResponse(request)
                    "/api/v1/chats/message-changes/ack" -> {
                        ackSnapshots.add(FriendsSession.messages.toList() to FriendsSession.chats.toList())
                        ackResponse()
                    }
                    "/api/v1/chats/messages" -> json("""
                        {"message":{"id":21,"conversation_id":5,"sender_id":1,"body":"new","created_at":"created"}}
                    """.trimIndent())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AccountSession.bindClient(AccountApiClient(server.url("/").toString(), "ak_test", OkHttpClient()))
        AccountSession.applyPreview(AccountUser(1, "alice", nickname = "Alice"), "tok-1")
        seedCache(listOf(original))
        FriendsSession.changeBatchDelayMs = 0
    }

    @AfterTest
    fun tearDown() {
        FriendsSession.clear()
        FriendsSession.changeBatchDelayMs = 3_000
        AccountSession.resetForTests()
        server.shutdown()
        stopKoin()
    }

    @Test
    fun replacesCachedMessagesAndOnlyMatchingPreviewBeforeAck() = runBlocking {
        seedCache(listOf(original.copy(id = 19, body = "older"), original))
        changesResponse = { page(change(101, deleted = true), change(102, messageId = 19, deleted = true)) }
        FriendsSession.refreshLists()

        val (messages, chats) = ackSnapshots.single()
        assertTrue(messages.all { it.deleted })
        assertEquals("此条消息由于违规已经被删除", messages.last().body)
        assertEquals(20, chats.single().lastMessage!!.id)
        assertTrue(chats.single().lastMessage!!.deleted)
        assertEquals(3, chats.single().unread)
        assertEquals("updated", chats.single().updatedAt)
        val ack = changeRequests().last()
        assertTrue(ack.body.clone().readUtf8().contains("\"change_ids\":[101,102]"))
        val clientId = changeRequests().first().requestUrl!!.queryParameter("client_id")!!
        assertEquals(prefs.chatClientId, clientId)
        assertEquals(clientId, savedPrefs!!.chatClientId)
        assertTrue(ack.body.clone().readUtf8().contains("\"client_id\":\"$clientId\""))
        assertTrue(requests.none { it.path!!.endsWith("/read") })
    }

    @Test
    fun drainsPagesInGetAckOrderAndRestoresUnbannedBody() = runBlocking {
        var pageNumber = 0
        changesResponse = {
            if (++pageNumber == 1) page(change(101, deleted = true), hasMore = true)
            else page(change(102, deleted = false))
        }
        FriendsSession.refreshLists()
        assertEquals(listOf("GET", "POST", "GET", "POST"), changeRequests().map { it.method })
        assertTrue(ackSnapshots.first().first.single().deleted)
        assertFalse(ackSnapshots.last().first.single().deleted)
        assertEquals("hello", FriendsSession.messages.single().body)
        assertEquals("hello", FriendsSession.lastMessagePreview(peer.id))

        // ACK 响应丢失后服务端可能重发旧记录；旧快照不能把已恢复的内容重新封禁。
        changesResponse = { page(change(101, deleted = true)) }
        FriendsSession.refreshLists()
        assertFalse(ackSnapshots.last().first.single().deleted)
    }

    @Test
    fun failedAckRetriesSameIdsWithoutRollingBackMessages() = runBlocking {
        changesResponse = { page(change(101, deleted = true), change(102, deleted = false)) }
        ackResponse = { json("""{"code":"internal_error","message":"failed"}""").setResponseCode(500) }
        FriendsSession.refreshLists()
        assertTrue(FriendsSession.listError.isNotBlank())
        assertEquals("hello", FriendsSession.messages.single().body)

        ackResponse = { json("""{"ok":true}""") }
        FriendsSession.refreshLists()
        assertEquals(2, ackSnapshots.size)
        assertTrue(ackSnapshots.all { !it.first.single().deleted })
        val acks = changeRequests().filter { it.method == "POST" }
        assertEquals(acks.first().body.clone().readUtf8(), acks.last().body.clone().readUtf8())
        changesResponse = { page() }
        FriendsSession.refreshLists()
        assertEquals(2, ackSnapshots.size)
    }

    @Test
    fun uncachedChangeIsAckedWithoutAddingHistoryOrReplacingNewerPreview() = runBlocking {
        changesResponse = { page(change(101, messageId = 19, deleted = true)) }
        FriendsSession.refreshLists()
        assertEquals(listOf(original), FriendsSession.messages.toList())
        assertEquals(original, FriendsSession.chats.single().lastMessage)
        assertEquals(1, ackSnapshots.size)
    }

    @Test
    fun malformedPageIsNotAcked() = runBlocking {
        changesResponse = { json("""{"changes":[{"change_id":101}]}""") }
        FriendsSession.refreshLists()
        assertTrue(FriendsSession.listError.isNotBlank())
        assertTrue(ackSnapshots.isEmpty())
        assertEquals(listOf(original), FriendsSession.messages.toList())
    }

    @Test
    fun concurrentRefreshesSerializeChangeQueriesAndAcks() = runBlocking {
        changesResponse = { page(change(101, deleted = true)) }
        val first = async { FriendsSession.refreshLists() }
        val second = async { FriendsSession.refreshLists() }
        first.await()
        second.await()
        assertEquals(listOf("GET", "POST", "GET", "POST"), changeRequests().map { it.method })
        assertTrue(ackSnapshots.all { it.first.single().deleted })
    }

    @Test
    fun rateLimitDefersBothListAndMessagePollsAndDoesNotAck() = runBlocking {
        changesResponse = {
            json("""{"code":"rate_limited","message":"too frequent"}""")
                .setResponseCode(429).setHeader("Retry-After", "10")
        }
        FriendsSession.refreshLists()
        val before = requests.size
        FriendsSession.refreshLists()
        FriendsSession.pollMessages()
        assertEquals(before, requests.size)
        assertTrue(ackSnapshots.isEmpty())
    }

    @Test
    fun saveFailureDoesNotClaimChangesAndCanRetry() = runBlocking {
        failSave = true
        FriendsSession.refreshLists()
        assertTrue(changeRequests().isEmpty())
        assertEquals("", prefs.chatClientId)
        failSave = false
        changesResponse = { page(change(101, deleted = true)) }
        FriendsSession.refreshLists()
        assertEquals(1, ackSnapshots.size)
        assertEquals(prefs.chatClientId, savedPrefs!!.chatClientId)
    }

    @Test
    fun paginationDelayAllowsSendingBeforeTheNextBatch() = runBlocking {
        val firstAck = CountDownLatch(1)
        var pageNumber = 0
        FriendsSession.changeBatchDelayMs = 1_500
        changesResponse = {
            if (++pageNumber == 1) page(change(101, deleted = true), hasMore = true)
            else page(change(102, deleted = false))
        }
        ackResponse = { firstAck.countDown(); json("""{"ok":true}""") }
        val pending = async { FriendsSession.refreshLists() }
        withContext(Dispatchers.IO) { assertTrue(firstAck.await(5, TimeUnit.SECONDS)) }
        assertTrue(withTimeout(1_000) { FriendsSession.send("new") })
        pending.await()
        val chatRequests = requests.filter { it.path!!.startsWith("/api/v1/chats/message") }
        assertEquals(listOf("GET", "POST", "POST", "GET", "POST"), chatRequests.map { it.method })
        assertEquals("/api/v1/chats/messages", chatRequests[2].path)
        assertEquals(listOf(20L, 21L), FriendsSession.messages.map { it.id })
    }

    @Test
    fun accountSwitchDuringQueryDiscardsOldSnapshotAndDoesNotAck() = runBlocking {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        changesResponse = {
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS)
            page(change(101, deleted = true))
        }
        val pending = async { FriendsSession.refreshLists() }
        try {
            withContext(Dispatchers.IO) { assertTrue(queryStarted.await(5, TimeUnit.SECONDS)) }
            AccountSession.applyPreview(AccountUser(3, "other", nickname = "Other"), "tok-2")
            val otherMessage = original.copy(body = "other user's message")
            seedCache(listOf(otherMessage))
            releaseQuery.countDown()
            pending.await()
            assertEquals(listOf(otherMessage), FriendsSession.messages.toList())
            assertTrue(ackSnapshots.isEmpty())
        } finally {
            releaseQuery.countDown()
        }
    }

    @Test
    fun stableClientIdSurvivesSessionResetAndInvalidStoredIdIsReplaced() {
        val first = AccountSession.messageChangesClientId()
        assertTrue(first.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
        AccountSession.applyLoggedOutPreview()
        AccountSession.resetForTests()
        assertEquals(first, AccountSession.messageChangesClientId())
        assertEquals(first, savedPrefs!!.chatClientId)
        prefs.chatClientId = "invalid value"
        val replaced = AccountSession.messageChangesClientId()
        assertTrue(replaced != first)
        assertEquals(replaced, savedPrefs!!.chatClientId)
    }

    private fun seedCache(messages: List<ChatMessageDto>) {
        FriendsSession.applyPreview(
            previewFriends = emptyList(), previewChats = listOf(ChatItem(5, peer, original)),
            previewMessages = messages, peer = peer, conversationId = 5,
        )
        AccountSession.networkEnabled = true
    }

    private fun change(changeId: Long, messageId: Long = 20, deleted: Boolean): String {
        val body = if (deleted) "此条消息由于违规已经被删除" else "hello"
        return """{"change_id":$changeId,"changed_at":"changed","message":{"id":$messageId,"conversation_id":5,"sender_id":1,"body":"$body","deleted":$deleted,"created_at":"created"}}"""
    }

    private fun page(vararg changes: String, hasMore: Boolean = false) =
        json("""{"changes":[${changes.joinToString(",")}],"has_more":$hasMore}""")

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun changeRequests() = requests.filter { it.path!!.startsWith("/api/v1/chats/message-changes") }
}
