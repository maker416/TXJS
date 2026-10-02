/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.core.ModPlaytimeController
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModPlaytimeHash
import io.github.rwpp.game.mod.ModPlaytimeState
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.playtime.ModPlaytimeClient
import io.github.rwpp.net.playtime.ModPlaytimeReceipt
import io.github.rwpp.net.playtime.ModPlaytimeReport
import io.github.rwpp.net.playtime.PendingModPlaytimeReport
import io.github.rwpp.net.playtime.PlayedMod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手动推进真实控制器的单调采样点，使用真实异步哈希和延迟 HTTP 响应验证竞态。
 * 不启动引擎或无限轮询；测试入口调用生产采样、结算、恢复、选取和确认方法。
 */
class ModPlaytimeControllerTest {
    private lateinit var directory: File
    private val gates = mutableListOf<HashGate>()
    private val second = 1_000_000_000L
    private val json = Json

    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("ModPlaytimeControllerTest")
        AccountSession.resetForTests()
        ModPlaytimeController.resetForTests()
        directory = Files.createTempDirectory("mod-playtime-controller-test-").toFile()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        gates.forEach { it.release.countDown() }
        AccountSession.resetForTests()
        ModPlaytimeController.resetForTests()
        awaitHashing()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun anonymousPlayerDoesNotStartHashingTimingOrQueueing() = runBlocking {
        val calls = AtomicInteger()
        ModPlaytimeController.hashOverride = { calls.incrementAndGet(); ModPlaytimeHash.sha256(it) }
        val state = active(mod("Anonymous mod"))

        for (time in 0L..60L) sample(state, time)
        ModPlaytimeController.finishMatchForTests(61 * second)
        awaitHashing()

        assertNull(ModPlaytimeController.currentForTests())
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty())
        assertNull(ModPlaytimeController.nextPendingForTests())
        assertEquals(0, calls.get(), "未登录不能读取/计算模组内容")
    }

    @Test
    fun loggedInPlayerWithNoServiceAddressDoesNotCreateReports() = runBlocking {
        login()
        val calls = AtomicInteger()
        ModPlaytimeController.hashOverride = { calls.incrementAndGet(); ModPlaytimeHash.sha256(it) }
        val state = active(mod("No configured service"))
        for (time in 0L..40L) {
            ModPlaytimeController.sampleForTests(state, time * second, apiUrl = "")
        }
        awaitHashing()
        assertEquals(0, calls.get())
        assertNull(ModPlaytimeController.currentForTests())
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty())
    }

    @Test
    fun pausesAndStateTransitionIntervalsDoNotIncreaseTime() = runBlocking {
        login()
        val state = active(mod("Paused mod"))
        sample(state, 0)
        awaitHashing()
        for (time in 1L..10L) sample(state, time)
        for (time in 11L..20L) sample(state.copy(active = false), time)
        for (time in 21L..30L) sample(state, time)
        ModPlaytimeController.finishMatchForTests(31 * second)

        val item = ModPlaytimeController.pendingForTests().single()
        assertEquals(19L, item.report.elapsedSeconds, "10 秒游玩 + 恢复后的 9 秒，暂停和切换边界均不计")
        assertTrue(item.report.ended)
        assertEquals(1L, item.userId)
    }

    @Test
    fun endingBeforeHashCompletesStillQueuesFinalTimeForOriginalIdentity() = runBlocking {
        login()
        val source = mod("Slow hash mod")
        val gate = blockHash()
        sample(active(source), 0)
        gate.await()
        for (time in 1L..12L) sample(active(source), time)
        ModPlaytimeController.finishMatchForTests(13 * second)

        assertNull(ModPlaytimeController.currentForTests())
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty(), "哈希未完成时不能发送不完整模组身份")
        gate.release.countDown()
        awaitHashing()

        val item = ModPlaytimeController.pendingForTests().single()
        assertEquals(12L, item.report.elapsedSeconds)
        assertTrue(item.report.ended)
        assertEquals(1L, item.userId)
        assertEquals(fingerprint("token-A"), item.tokenFingerprint)
        assertEquals(listOf(PlayedMod(source.name, ModPlaytimeHash.sha256(File(source.path)))), item.report.mods)
    }

    @Test
    fun accountSwitchDropsQueueAndPreventsLateOldHashFromReappearing() = runBlocking {
        login()
        val original = active(mod("Queued account A"), generation = 1)
        sample(original, 0)
        awaitHashing()
        for (time in 1L..30L) sample(original, time)
        assertEquals(30L, ModPlaytimeController.pendingForTests().single().report.elapsedSeconds)

        val slow = active(mod("Hashing account A"), generation = 2)
        val gate = blockHash()
        sample(slow, 31)
        gate.await()
        for (time in 32L..36L) sample(slow, time)
        login(userId = 2, token = "token-B")
        assertNull(ModPlaytimeController.currentForTests())
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty(), "开始切号即清除旧账号队列")
        gate.release.countDown()
        awaitHashing()
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty(), "旧账号异步哈希完成不能重新入队")

        ModPlaytimeController.hashOverride = null
        val newSource = mod("Account B mod")
        val current = active(newSource, generation = 3)
        sample(current, 37)
        awaitHashing()
        for (time in 38L..67L) sample(current, time)

        val item = ModPlaytimeController.pendingForTests().single()
        assertEquals(2L, item.userId)
        assertEquals(fingerprint("token-B"), item.tokenFingerprint)
        assertEquals(30L, item.report.elapsedSeconds)
        assertEquals("Account B mod", item.report.mods.single().name)
    }

    @Test
    fun receiptForThirtySecondsCannotRemoveSixtySecondReportQueuedDuringRequest() = runBlocking {
        login()
        val source = mod("In-flight report mod")
        val state = active(source)
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/").toString().trimEnd('/')
            sample(state, 0, url)
            awaitHashing()
            for (time in 1L..30L) sample(state, time, url)
            val oldItem = ModPlaytimeController.pendingForTests().single()
            assertEquals(30L, oldItem.report.elapsedSeconds)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    return MockResponse().setBody(json.encodeToString(ModPlaytimeReceipt(
                        oldItem.report.sessionId, 30, 30, false,
                    )))
                }
            }
            try {
                val flight = async(Dispatchers.IO) {
                    ModPlaytimeClient(url, OkHttpClient()).report("token-A", oldItem.report)
                }
                withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS), "未收到第一份累计报告") }
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("/api/v1/playtime/reports", request.path)
                assertEquals("Bearer token-A", request.getHeader("Authorization"))
                assertEquals(30L, json.decodeFromString<ModPlaytimeReport>(request.body.readUtf8()).elapsedSeconds)

                for (time in 31L..60L) sample(state, time, url)
                assertEquals(60L, ModPlaytimeController.pendingForTests().single().report.elapsedSeconds)
                release.countDown()
                assertEquals(30L, withTimeout(5_000) { flight.await() }.elapsedSeconds)
                ModPlaytimeController.acknowledgeForTests(oldItem)

                val remaining = ModPlaytimeController.pendingForTests().single()
                assertEquals(oldItem.report.sessionId, remaining.report.sessionId)
                assertEquals(60L, remaining.report.elapsedSeconds, "旧响应只能确认旧累计值，不能删掉途中更新")
                assertFalse(remaining.report.ended)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun unknownSamplingPausesClockWithoutEndingOrReplacingSession() = runBlocking {
        login()
        val state = active(mod("Temporarily unknown"))
        sample(state, 0)
        awaitHashing()
        val id = assertNotNull(ModPlaytimeController.currentForTests())
        for (time in 1L..10L) sample(state, time)
        for (time in 11L..20L) sample(ModPlaytimeState(reliable = false), time)

        assertEquals(id, ModPlaytimeController.currentForTests(), "无法读取状态不等于退出对局")
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty())
        for (time in 21L..25L) sample(state, time)
        ModPlaytimeController.finishMatchForTests(26 * second)

        val item = ModPlaytimeController.pendingForTests().single()
        assertEquals(id, item.report.sessionId)
        assertEquals(14L, item.report.elapsedSeconds, "未知期间暂停，只累计前10秒和恢复后4秒")
        assertTrue(item.report.ended)
    }

    @Test
    fun rapidlyStartingNewGenerationFreezesSeparateSessionsAndModSets() = runBlocking {
        login()
        val first = mod("First match mod", "first contents")
        val secondMod = mod("Second match mod", "second contents")
        val firstState = active(first, generation = 1)
        sample(firstState, 0)
        awaitHashing()
        val firstID = assertNotNull(ModPlaytimeController.currentForTests())
        for (time in 1L..5L) sample(firstState, time)
        first.name = "Changed after first match started"

        // 两次采样都仍是hasMatch=true；本局序号变化必须完成旧局并生成新session。
        val next = active(secondMod, generation = 2)
        sample(next, 6)
        awaitHashing()
        val nextID = assertNotNull(ModPlaytimeController.currentForTests())
        assertNotEquals(firstID, nextID)
        for (time in 7L..11L) sample(next, time)
        ModPlaytimeController.finishMatchForTests(12 * second)

        val reports = ModPlaytimeController.pendingForTests().associateBy { it.report.sessionId }
        assertEquals(setOf(firstID, nextID), reports.keys)
        val old = reports.getValue(firstID).report
        val new = reports.getValue(nextID).report
        assertEquals(5L, old.elapsedSeconds)
        assertEquals(5L, new.elapsedSeconds)
        assertTrue(old.ended)
        assertTrue(new.ended)
        assertEquals("First match mod", old.mods.single().name, "开局时冻结名称，后续读取不能改写会话集合")
        assertEquals("Second match mod", new.mods.single().name)
        assertNotEquals(old.mods.single().sha256, new.mods.single().sha256)
    }

    @Test
    fun recoveredOutboxOnlyDeliversMatchingUserAndOriginalToken() = runBlocking {
        login()
        val valid = pending("recovered-current-session", 1, "token-A")
        val wrongUser = pending("recovered-foreign-user", 2, "token-A")
        val oldToken = pending("recovered-old-credentials", 1, "older-token")
        ModPlaytimeController.recoverForTests(listOf(wrongUser, oldToken, valid))

        val selected = assertNotNull(ModPlaytimeController.nextPendingForTests())
        assertEquals(valid.report.sessionId, selected.report.sessionId)
        assertTrue(selected.report.ended, "上次进程已结束，恢复最后已落盘值后关闭旧会话")
        assertEquals(listOf(selected), ModPlaytimeController.pendingForTests())
        assertFalse(json.encodeToString(selected).contains("token-A"), "待发送持久内容不得存储原token")

        // 未恢复账号前允许保留既有outbox，但不能选中或发送；登录其他账号后淘汰旧项。
        AccountSession.applyLoggedOutPreview()
        ModPlaytimeController.recoverForTests(listOf(valid))
        assertNull(ModPlaytimeController.nextPendingForTests())
        assertEquals(1, ModPlaytimeController.pendingForTests().size)
        login(userId = 2, token = "token-B")
        ModPlaytimeController.recoverForTests(listOf(valid))
        assertNull(ModPlaytimeController.nextPendingForTests())
        assertTrue(ModPlaytimeController.pendingForTests().isEmpty())
    }

    @Test
    fun disabledServiceKeepsReportsUnsentAndChangingAddressDropsOldDestination() {
        login()
        val old = pending("recovered-old-destination", 1, "token-A")
        val replacement = pending("recovered-new-destination", 1, "token-A")
            .copy(apiUrl = "http://new-server")
        ModPlaytimeController.recoverForTests(listOf(old, replacement))

        assertNull(ModPlaytimeController.nextPendingForTests(""), "关闭服务配置后不能选择待发送记录")
        assertEquals(2, ModPlaytimeController.pendingForTests().size)
        val selected = assertNotNull(ModPlaytimeController.nextPendingForTests("http://new-server"))
        assertEquals(replacement.report.sessionId, selected.report.sessionId)
        assertEquals("http://new-server", selected.apiUrl)
        assertEquals(listOf(selected), ModPlaytimeController.pendingForTests(), "旧地址记录必须淘汰，不能阻塞新地址或发到旧服务")
    }

    private fun login(userId: Long = 1, token: String = "token-A") {
        AccountSession.applyPreview(AccountUser(userId, "account-$userId", nickname = "Player $userId"), token)
        AccountSession.networkEnabled = true
    }

    private fun active(source: Mod, generation: Long = 1) =
        ModPlaytimeState(hasMatch = true, active = true, mods = listOf(source), generation = generation)

    private fun sample(state: ModPlaytimeState, seconds: Long, url: String = "http://localhost") =
        ModPlaytimeController.sampleForTests(state, seconds * second, url)

    private suspend fun awaitHashing() = withTimeout(5_000) { ModPlaytimeController.awaitHashingForTests() }

    private fun mod(name: String, contents: String = name): FakeMod {
        val file = File(directory, "${directory.listFiles()!!.size}.rwmod")
        file.writeText(contents)
        return FakeMod(name, file)
    }

    private fun blockHash(): HashGate = HashGate().also { gate ->
        gates += gate
        ModPlaytimeController.hashOverride = {
            gate.entered.countDown()
            check(gate.release.await(5, TimeUnit.SECONDS)) { "未释放模拟慢哈希" }
            ModPlaytimeHash.sha256(it)
        }
    }

    private fun pending(sessionId: String, userId: Long, token: String) = PendingModPlaytimeReport(
        userId, fingerprint(token), "http://localhost",
        ModPlaytimeReport(sessionId, 30, listOf(PlayedMod("Recovered mod", "a".repeat(64)))),
    )

    private fun fingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class HashGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        suspend fun await() = withContext(Dispatchers.IO) {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "未进入异步哈希")
        }
    }

    private class FakeMod(override var name: String, private val file: File) : Mod {
        override val id = file.nameWithoutExtension.toInt()
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = true
        override val path: String get() = file.absolutePath
        override fun getRamUsed() = "0"
        override fun getSize() = file.length()
        override fun getBytes() = file.readBytes()
    }
}
