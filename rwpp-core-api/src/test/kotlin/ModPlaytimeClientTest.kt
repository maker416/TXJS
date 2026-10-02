/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.playtime.ModPlaytimeClient
import io.github.rwpp.net.playtime.ModPlaytimeHttpException
import io.github.rwpp.net.playtime.ModPlaytimeReport
import io.github.rwpp.net.playtime.PlayedMod
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModPlaytimeClientTest {
    @Test fun retryUsesSameCumulativeSessionAndFinalReport() = runBlocking {
        MockWebServer().use { server ->
            val session = UUID.randomUUID().toString()
            val client = ModPlaytimeClient(server.url("/").toString(), OkHttpClient())
            val report = ModPlaytimeReport(session, 30, listOf(PlayedMod("中文模组", "a".repeat(64))))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(receipt(session, 30, 30, false))
            server.enqueue(receipt(session, 45, 15, true))
            assertFailsWith<ModPlaytimeHttpException> { client.report("test-token", report) }
            client.report("test-token", report)
            client.report("test-token", report.copy(elapsedSeconds = 45, ended = true))
            val first = server.takeRequest()
            val repeated = server.takeRequest()
            val final = server.takeRequest()
            assertEquals("/api/v1/playtime/reports", first.path)
            assertEquals("Bearer test-token", first.getHeader("Authorization"))
            assertEquals(first.body.readUtf8(), repeated.body.readUtf8())
            val finalBody = final.body.readUtf8()
            assertTrue(finalBody.contains("\"session_id\":\"$session\""))
            assertTrue(finalBody.contains("\"elapsed_seconds\":45"))
            assertTrue(finalBody.contains("\"ended\":true"))
        }
    }

    @Test fun rejectsMissingAuthenticationAndFalseAcknowledgements() = runBlocking {
        MockWebServer().use { server ->
            val session = UUID.randomUUID().toString()
            val client = ModPlaytimeClient(server.url("/").toString(), OkHttpClient())
            val report = ModPlaytimeReport(session, 45, listOf(PlayedMod("Demo", "a".repeat(64))), ended = true)
            assertFailsWith<IllegalArgumentException> { client.report("", report) }
            assertEquals(0, server.requestCount)
            server.enqueue(receipt(session, 30, 30, false))
            assertFailsWith<IOException> { client.report("test-token", report) }
            Unit
        }
    }

    private fun receipt(id: String, elapsed: Long, added: Long, ended: Boolean) = MockResponse()
        .setBody("""{"session_id":"$id","elapsed_seconds":$elapsed,"added_seconds":$added,"ended":$ended}""")
        .setHeader("Content-Type", "application/json")
}
