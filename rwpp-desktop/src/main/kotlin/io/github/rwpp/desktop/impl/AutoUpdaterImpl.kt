/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import okhttp3.Request
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val net: Net by inject()

    override fun isSupported(): Boolean = true

    override fun downloadAndInstall(downloadUrl: String, onProgress: (Float) -> Unit) {
        val tempDir = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir")
        val outputFile = File(tempDir, "RWPP-Setup-update.exe")

        val request = Request.Builder().url(downloadUrl).build()
        runCatching {
            net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress(-1f)
                    return
                }

                val body = response.body ?: run {
                    onProgress(-1f)
                    return
                }

                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded: Long = 0
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                onProgress(downloaded.toFloat() / contentLength.toFloat())
                            }
                        }
                    }
                }
            }
        }.onFailure {
            logger.error("Failed to download update: ${it.stackTraceToString()}")
            onProgress(-1f)
            return
        }

        logger.info("Download completed: ${outputFile.absolutePath}")

        val processBuilder = ProcessBuilder(
            outputFile.absolutePath,
            "RWPP_UPDATE_MODE=1"
        )
        processBuilder.start()
        exitProcess(0)
    }
}
