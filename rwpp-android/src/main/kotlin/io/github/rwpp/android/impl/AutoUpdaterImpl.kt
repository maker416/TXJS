/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import okhttp3.Request
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val context: Context by inject()
    private val net: Net by inject()

    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun downloadAndInstall(downloadUrl: String, onProgress: (Float) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                onProgress(-1f)
                return
            }
        }

        val apkFile = File(context.cacheDir, "rwpp-update.apk")

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
                    FileOutputStream(apkFile).use { output ->
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

        logger.info("Download completed: ${apkFile.absolutePath}")

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        runCatching {
            context.startActivity(installIntent)
        }.onFailure {
            logger.error("Failed to start install activity: ${it.stackTraceToString()}")
            onProgress(-1f)
        }
    }
}
