/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.net.browser.BrowserModTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WebView 下载带回浏览器 UA、来源和按目标 URL 选取的 Cookie；不向跨站重定向泄漏原站 Cookie。 */
class BrowserModHttpTransfer(
    http: OkHttpClient,
    private val url: String,
    private val userAgent: String?,
    private val referer: String?,
    cookieProvider: (String) -> String? = { null },
) : BrowserModTransfer {
    private val client = http.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder().removeHeader("Cookie")
            cookieProvider(chain.request().url.toString())?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
            chain.proceed(request.build())
        }.build()
    private val lock = Any()
    private var cancelled = false
    private var job: Job? = null

    override fun cancel() = synchronized(lock) {
        cancelled = true
        job?.cancel()
        Unit
    }

    override suspend fun download(target: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        synchronized(lock) {
            check(!cancelled) { "Download cancelled" }
            job = context[Job]
        }
        val request = Request.Builder().url(url).apply {
            userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
        }.build()
        try {
            client.useCancellable(request) { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download response")
                val total = body.contentLength().takeIf { it >= 0 }
                var copied = 0L
                onProgress(copied, total)
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied, total)
                        }
                    }
                }
                if (total != null && copied != total) throw IOException("Incomplete mod download")
                onProgress(copied, total)
            }
        } finally { synchronized(lock) { job = null } }
    }
}
