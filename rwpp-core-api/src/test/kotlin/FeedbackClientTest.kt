/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.diagnostics.LogArchive
import io.github.rwpp.net.feedback.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import kotlin.test.*

class FeedbackClientTest {
    @Test fun uploadsFeedbackAndArchiveAsOneRequestAndReturnsReceipt() = runBlocking {
        val file = Files.createTempFile("feedback-client-test-", ".zip").toFile()
        ZipOutputStream(file.outputStream()).close()
        LogArchive(file,listOf("2026-10-25")).use { archive ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"id":"receipt-1","received_at":"2026-10-25T12:00:00Z"}"""))
                server.start()
                val report = FeedbackReport("id", "进房黑屏", "加入房间后黑屏", "", "android", "Android 15", "1.20.38", "official", "2026-10-25T12:00:00Z",archive.days)
                assertEquals("receipt-1",FeedbackClient().submit(server.url("/").toString(),report,archive).id)
                val request=server.takeRequest()
                assertEquals("/v1/feedback",request.path)
                val body=request.body.readUtf8()
                assertTrue(body.contains("name=\"metadata\""))
                assertTrue(body.contains("进房黑屏"))
                assertTrue(body.contains("filename=\"logs.zip\""))
                assertNull(request.getHeader("Authorization"))
            }
        }
        assertFalse(file.exists())
    }
}
