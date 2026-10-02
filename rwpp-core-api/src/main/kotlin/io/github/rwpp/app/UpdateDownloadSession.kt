/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** 同步更新下载的取消句柄；安装提交与取消共用同一把锁，取消胜出时绝不启动安装。 */
class UpdateDownloadSession {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var activeCall: Call? = null

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        val call = synchronized(lock) {
            cancelled = true
            activeCall
        }
        call?.cancel()
    }

    fun checkActive() {
        if (cancelled) throw UpdateDownloadCancelledException()
    }

    fun <T> execute(call: Call, consume: (Response) -> T): T {
        synchronized(lock) {
            checkActive()
            activeCall = call
        }
        try {
            checkActive()
            return call.execute().use { response ->
                checkActive()
                consume(response)
            }
        } finally {
            synchronized(lock) { if (activeCall === call) activeCall = null }
        }
    }

    fun startInstallation(start: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) false else {
            start()
            true
        }
    }
}

class UpdateDownloadCancelledException : IOException("Update download cancelled")