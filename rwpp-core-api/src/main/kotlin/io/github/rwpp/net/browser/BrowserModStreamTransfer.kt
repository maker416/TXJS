/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 接收浏览器已认证的响应流，不导出 Cookie 或重发下载请求。取消也释放尚未确认的流。 */
class BrowserModStreamTransfer(
    private val input: InputStream,
    private val totalBytes: Long?,
) : BrowserModTransfer {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var job: Job? = null

    private fun closeInput() {
        if (closed.compareAndSet(false, true)) runCatching { input.close() }
    }

    override fun cancel() {
        cancelled.set(true)
        job?.cancel()
        closeInput()
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun download(target: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "Download already consumed" }
        val context = currentCoroutineContext()
        job = context[Job]
        // Closing a blocking read is necessary even when only the caller's coroutine was cancelled.
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) closeInput()
        }
        try {
            context.ensureActive()
            check(!cancelled.get()) { "Download cancelled" }
            var copied = 0L
            onProgress(copied, totalBytes)
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, totalBytes)
                }
            }
            if (totalBytes != null && copied != totalBytes) throw IOException("Incomplete mod download")
        } catch (failure: Exception) {
            context.ensureActive()
            throw failure
        } finally {
            cancellation?.dispose()
            job = null
            closeInput()
        }
    }
}
