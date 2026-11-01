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
import io.github.rwpp.net.account.PublicUser
import kotlinx.coroutines.runBlocking
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
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 用真实 HTTP 请求验证主菜单好友红点：查看会话 / 接受邀请 / 查看申请后会消失，标记已读失败会重试。 */
class FriendsUnreadBadgeTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val peer = PublicUser(2, "bob", "Bob")

    /** 会话 5：对方先发 20，自己后发 21（例如房间邀请），对方那条尚未读。 */
    private var chatsBody = chats(unread = 1, lastId = 21, lastSender = 1)
    private var messagesBody = """{"messages":[${msg(20, sender = 2)},${msg(21, sender = 1)}]}"""
    private var incomingBody = """{"requests":[]}"""
    private var readResponses = ArrayDeque<Int>()

    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("FriendsUnreadBadgeTest")
        AccountSession.resetForTests()
        val prefs = AccountPreferences()
        val configIO = object : ConfigIO {
            override fun saveConfig(config: Config) = Unit
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
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/api/v1/friends" -> json("""{"friends":[]}""")
                    "/api/v1/friends/requests" ->
                        if (url.queryParameter("box") == "incoming") json(incomingBody) else json("""{"requests":[]}""")
                    "/api/v1/blocks" -> json("""{"blocks":[]}""")
                    "/api/v1/chats" -> json(chatsBody)
                    "/api/v1/chats/message-changes" -> json("""{"changes":[],"has_more":false}""")
                    "/api/v1/chats/5/messages" -> json(messagesBody)
                    "/api/v1/chats/5/read" -> {
                        val code = readResponses.removeFirstOrNull() ?: 200
                        if (code == 200) json("""{"ok":true}""")
                        else json("""{"error":{"code":"internal","message":"boom"}}""").setResponseCode(code)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AccountSession.bindClient(AccountApiClient(server.url("/").toString(), "ak_test", OkHttpClient()))
        AccountSession.applyPreview(AccountUser(1, "alice", nickname = "Alice"), "tok-1")
        FriendsSession.applyPreview(previewFriends = emptyList())
        AccountSession.networkEnabled = true
    }

    @AfterTest
    fun tearDown() {
        FriendsSession.clear()
        AccountSession.resetForTests()
        server.shutdown()
        stopKoin()
    }

    @Test
    fun openingChatWhoseLastMessageIsOwnStillMarksPeerMessagesRead() = runBlocking {
        FriendsSession.refreshLists()
        assertEquals(1, FriendsSession.badgeCount())

        FriendsSession.openChat(peer)

        assertEquals(listOf(21L), readIds())
        assertEquals(0, FriendsSession.badgeCount())
        // 已上报过的位置不再重复请求
        FriendsSession.pollMessages()
        assertEquals(1, readIds().size)
    }

    @Test
    fun failedMarkReadIsRetriedOnNextPollWithoutNewMessages() = runBlocking {
        readResponses.addLast(500)
        FriendsSession.refreshLists()
        FriendsSession.openChat(peer)
        assertEquals(1, FriendsSession.badgeCount())

        FriendsSession.pollMessages()

        assertEquals(listOf(21L, 21L), readIds())
        assertEquals(0, FriendsSession.badgeCount())
    }

    @Test
    fun acceptingInviteFromToastMarksConversationRead() = runBlocking {
        chatsBody = chats(unread = 1, lastId = 22, lastSender = 2)
        FriendsSession.refreshLists()

        FriendsSession.markInviteRead(peer.id, 22)
        FriendsSession.markInviteRead(peer.id, 22)

        assertEquals(listOf(22L), readIds())
        assertEquals(0, FriendsSession.badgeCount())
    }

    @Test
    fun viewedIncomingRequestsNoLongerCountInBadge() = runBlocking {
        chatsBody = chats(unread = 0, lastId = 21, lastSender = 1)
        incomingBody = """{"requests":[${request(7)}]}"""
        FriendsSession.refreshLists()
        assertEquals(1, FriendsSession.badgeCount())

        FriendsSession.markIncomingRequestsSeen(FriendsSession.incoming.map { it.id })
        FriendsSession.refreshLists()
        assertEquals(0, FriendsSession.badgeCount())

        incomingBody = """{"requests":[${request(7)},${request(8)}]}"""
        FriendsSession.refreshLists()
        assertEquals(1, FriendsSession.badgeCount())
    }

    private fun readIds(): List<Long> = requests
        .filter { it.path!!.endsWith("/read") }
        .map { Regex("\"last_message_id\":(\\d+)").find(it.body.clone().readUtf8())!!.groupValues[1].toLong() }

    private fun chats(unread: Int, lastId: Long, lastSender: Long) = """
        {"chats":[{"id":5,"peer":{"id":2,"username":"bob","nickname":"Bob"},
          "last_message":${msg(lastId, lastSender)},"unread":$unread,"updated_at":"updated"}]}
    """.trimIndent()

    private fun msg(id: Long, sender: Long) =
        """{"id":$id,"conversation_id":5,"sender_id":$sender,"body":"m$id","created_at":"created"}"""

    private fun request(id: Long) =
        """{"id":$id,"from_user":{"id":${id + 10},"username":"u$id","nickname":"U$id"},
           "to_user":{"id":1,"username":"alice","nickname":"Alice"},"status":"pending"}"""

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
}
