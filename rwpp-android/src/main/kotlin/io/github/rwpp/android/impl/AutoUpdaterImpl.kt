/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.app.AutoUpdater.Companion.PROGRESS_FAILED
import io.github.rwpp.app.AutoUpdater.Companion.PROGRESS_NEED_INSTALL_PERMISSION
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import okhttp3.Request
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val context: Context by inject()
    private val net: Net by inject()
    private val downloadLock = Any()
    @Volatile
    private var downloadInProgress = false
    @Volatile
    private var updateCancelled = false
    private var installPermissionRetryCallback: Application.ActivityLifecycleCallbacks? = null

    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun cancelPendingUpdate() {
        updateCancelled = true
        unregisterInstallPermissionRetry()
    }

    override fun downloadAndInstall(downloadUrl: String, onProgress: (Float) -> Unit) {
        synchronized(downloadLock) {
            if (downloadInProgress) return
            downloadInProgress = true
            updateCancelled = false
        }

        try {
            if (updateCancelled) return

            if (!hasInstallPermission()) {
                requestInstallPermission(downloadUrl, onProgress)
                return
            }

            val apkFile = File.createTempFile("rwpp-update-", ".apk", context.cacheDir)

            val request = Request.Builder().url(downloadUrl).build()
            runCatching {
                net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onProgress(PROGRESS_FAILED)
                        return
                    }

                    val body = response.body ?: run {
                        onProgress(PROGRESS_FAILED)
                        return
                    }

                    val contentLength = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded: Long = 0
                            var read: Int

                            while (input.read(buffer).also { read = it } != -1) {
                                if (updateCancelled) return
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
                onProgress(PROGRESS_FAILED)
                return
            }

            if (updateCancelled) return

            logger.info("Download completed: ${apkFile.absolutePath}")

            val authority = "${context.packageName}.fileprovider"
            val uri = runCatching {
                FileProvider.getUriForFile(context, authority, apkFile)
            }.getOrElse {
                logger.error("Failed to create APK content uri (authority=$authority): ${it.stackTraceToString()}")
                onProgress(PROGRESS_FAILED)
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            runCatching {
                context.startActivity(installIntent)
            }.onFailure {
                logger.error("Failed to start install activity: ${it.stackTraceToString()}")
                onProgress(PROGRESS_FAILED)
            }
        } finally {
            synchronized(downloadLock) {
                downloadInProgress = false
            }
        }
    }

    private fun hasInstallPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
    }

    private fun requestInstallPermission(downloadUrl: String, onProgress: (Float) -> Unit) {
        if (updateCancelled) return

        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        registerInstallPermissionRetry(downloadUrl, onProgress)

        runCatching {
            context.startActivity(intent)
        }.onSuccess {
            onProgress(PROGRESS_NEED_INSTALL_PERMISSION)
        }.onFailure {
            unregisterInstallPermissionRetry()
            logger.error("Failed to request install permission: ${it.stackTraceToString()}")
            onProgress(PROGRESS_FAILED)
        }
    }

    private fun registerInstallPermissionRetry(downloadUrl: String, onProgress: (Float) -> Unit) {
        val application = context.applicationContext as? Application ?: return
        unregisterInstallPermissionRetry()

        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!hasInstallPermission() || updateCancelled) return

                unregisterInstallPermissionRetry()
                thread(name = "rwpp-auto-update-retry") {
                    onProgress(0f)
                    downloadAndInstall(downloadUrl, onProgress)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        application.registerActivityLifecycleCallbacks(callback)
        installPermissionRetryCallback = callback
    }

    private fun unregisterInstallPermissionRetry() {
        val callback = installPermissionRetryCallback ?: return
        val application = context.applicationContext as? Application ?: return
        application.unregisterActivityLifecycleCallbacks(callback)
        installPermissionRetryCallback = null
    }
}
