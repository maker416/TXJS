/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 请求与正文消费共用取消句柄，离开 [block] 时关闭响应并释放句柄。
 * 调用方在 IO 上执行；取消必须在 Job 开始取消时触发，不能等阻塞请求结束。
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend inline fun <T> OkHttpClient.useCancellable(
    request: Request,
    block: (Response) -> T,
): T {
    val context = currentCoroutineContext()
    val call = newCall(request)
    val cancelHandle = context.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        context.ensureActive()
        return call.execute().use { response ->
            context.ensureActive()
            val result = block(response)
            context.ensureActive()
            result
        }
    } catch (error: IOException) {
        // Socket 被取消关闭时通常抛 IOException，仍应向上保留协程取消语义。
        context.ensureActive()
        throw error
    } finally {
        cancelHandle.dispose()
    }
}
