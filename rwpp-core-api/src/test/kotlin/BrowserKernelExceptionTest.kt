/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.UpdateDownloadCancelledException
import io.github.rwpp.net.browser.*
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CancellationException
import kotlin.test.*

class BrowserKernelExceptionTest {
    @Test fun retryFilterRequiresAccessDenialInCauseChainAndFileAccessCategory() {
        val denied = AccessDeniedException("native-file")
        assertTrue(BrowserKernelException.from(IOException("wrapped", denied), BrowserKernelStage.INSTALLING).isRetryableAccessDenial())
        assertFalse(BrowserKernelException.from(FileAlreadyExistsException("native-file"), BrowserKernelStage.INSTALLING).isRetryableAccessDenial())
        val checksum = BrowserKernelException(BrowserKernelFailureKind.VERIFICATION, BrowserKernelStage.VERIFYING, null, null, "checksum mismatch", denied)
        assertFalse(checksum.isRetryableAccessDenial())
        val invalidPath = IOException("invalid archive").apply { addSuppressed(denied) }
        assertFalse(BrowserKernelException.from(invalidPath, BrowserKernelStage.VERIFYING).isRetryableAccessDenial())
    }
    @Test fun deniedCreationKeepsOsExceptionTypePathAndStateBeforeCleanup() {
        val root = Files.createTempDirectory("browser-kernel-diagnosis")
        try {
            val path = root.resolve("chrome_elf.dll").toString()
            val denied = AccessDeniedException(path)
            val failure = BrowserKernelException.from(denied, BrowserKernelStage.INSTALLING)
            assertEquals(BrowserKernelFailureKind.FILE_ACCESS, failure.kind)
            assertEquals(BrowserKernelStage.INSTALLING, failure.stage)
            assertEquals(path, failure.path)
            assertEquals("AccessDeniedException", failure.systemReason)
            assertEquals(false, assertNotNull(failure.fileState).exists)
            assertSame(denied, failure.cause)
            assertSame(failure, BrowserKernelException.from(failure, BrowserKernelStage.DOWNLOADING, fallback = BrowserKernelFailureKind.NETWORK))
            assertTrue(failure.message.orEmpty().contains("exists=false"))
        } finally { Files.delete(root) }
    }

    @Test fun outputWriteFlushAndCloseFailuresKeepLocalPathInsteadOfBecomingNetworkFailures() {
        for (operation in listOf("write", "flush", "close")) {
            val original = IOException("disk refused $operation")
            val output = browserKernelOutput("local-part", BrowserKernelStage.DOWNLOADING) {
                object : OutputStream() {
                    override fun write(value: Int) { if (operation == "write") throw original }
                    override fun flush() { if (operation == "flush") throw original }
                    override fun close() { if (operation == "close") throw original }
                }
            }
            val failure = assertFailsWith<BrowserKernelException> {
                browserKernelOperation(BrowserKernelStage.DOWNLOADING, kind = BrowserKernelFailureKind.NETWORK) {
                    when (operation) {
                        "write" -> output.write(1)
                        "flush" -> output.flush()
                        else -> output.close()
                    }
                }
            }
            assertEquals(BrowserKernelFailureKind.FILE_ACCESS, failure.kind)
            assertEquals(BrowserKernelStage.DOWNLOADING, failure.stage)
            assertEquals("local-part", failure.path)
            assertSame(original, failure.cause)
        }
    }

    @Test fun cleanupFailureIsSuppressedOnOriginalFailureIncludingCancellation() {
        val originals = listOf(
            BrowserKernelException.from(AccessDeniedException("chrome_elf.dll"), BrowserKernelStage.INSTALLING),
            UpdateDownloadCancelledException(),
            CancellationException("cancel browser installation"),
        )
        for (original in originals) {
            val cleanup = AccessDeniedException("staging")
            val thrown = assertFails {
                browserKernelOperation(BrowserKernelStage.INSTALLING) {
                    withBrowserKernelCleanup(cleanup = { throw cleanup }) { throw original }
                }
            }
            assertSame(original, thrown)
            assertSame(cleanup, thrown.suppressed.single())
        }
    }

    @Test fun failedCleanupIsReportedWhenThereIsNoEarlierFailure() {
        val cleanup = AccessDeniedException("staging")
        val thrown = assertFailsWith<BrowserKernelException> {
            browserKernelOperation(BrowserKernelStage.INSTALLING) {
                withBrowserKernelCleanup(cleanup = { throw cleanup }) { Unit }
            }
        }
        assertEquals(BrowserKernelFailureKind.FILE_ACCESS, thrown.kind)
        assertSame(cleanup, thrown.cause)
    }
}
