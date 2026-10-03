/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.browser.BrowserModStreamTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BrowserModStreamTransferTest {
    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun streamsTheAuthenticatedResponseOnceAndReportsProgress() = runBlocking {
        val bytes = ByteArray(150_000) { it.toByte() }
        val input = TrackedInput(bytes)
        val transfer = BrowserModStreamTransfer(input, bytes.size.toLong())
        val file = Files.createTempFile("gecko-mod-", ".part").toFile()
        try {
            val progress = mutableListOf<Pair<Long, Long?>>()
            transfer.download(file) { received, total -> progress += received to total }
            assertContentEquals(bytes, file.readBytes())
            assertEquals(0L, progress.first().first)
            assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
            assertEquals(1, input.closes)
            assertFailsWith<IllegalStateException> { transfer.download(file) { _, _ -> } }
            transfer.cancel()
            assertEquals(1, input.closes)
        } finally { file.delete() }
    }

    @Test fun cancellingBeforeConfirmationClosesTheResponseAndDoesNotCreateAFile() = runBlocking {
        val input = TrackedInput(byteArrayOf(1))
        val transfer = BrowserModStreamTransfer(input, 1)
        val directory = Files.createTempDirectory("gecko-mod-cancel-").toFile()
        val file = directory.resolve("not-created.part")
        try {
            transfer.cancel()
            assertEquals(1, input.closes)
            assertFailsWith<IllegalStateException> { transfer.download(file) { _, _ -> } }
            assertFalse(file.exists())
        } finally { directory.delete() }
    }

    @Test fun truncatedResponsesFailAndUnknownLengthsRemainSupported() = runBlocking {
        val file = Files.createTempFile("gecko-mod-size-", ".part").toFile()
        try {
            val input = TrackedInput(byteArrayOf(1, 2))
            assertFailsWith<IOException> { BrowserModStreamTransfer(input, 3).download(file) { _, _ -> } }
            assertEquals(1, input.closes)
            BrowserModStreamTransfer(TrackedInput(byteArrayOf(4, 5)), null).download(file) { _, total -> assertNull(total) }
            assertContentEquals(byteArrayOf(4, 5), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun parentCancellationClosesAndUnblocksAStalledRead() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("Read did not unblock")
                throw IOException("Response closed")
            }
            override fun close() { closed.countDown() }
        }
        val transfer = BrowserModStreamTransfer(input, null)
        val file = Files.createTempFile("gecko-mod-blocked-", ".part").toFile()
        try {
            val download = async { transfer.download(file) { _, _ -> } }
            assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
            download.cancel()
            withTimeout(2000) { download.join() }
            assertEquals(0L, closed.count)
        } finally { transfer.cancel(); file.delete() }
    }
}
