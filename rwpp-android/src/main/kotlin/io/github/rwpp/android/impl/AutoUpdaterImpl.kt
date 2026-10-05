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
import io.github.rwpp.app.*
import io.github.rwpp.projectVersion
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.UpdateDownloadPlan
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val context: Context by inject()
    private val net: Net by inject()
    private val lock = Any()
    private var active: UpdateDownloadSession? = null
    @Volatile private var pendingApk: File? = null
    override fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    override fun cancelPendingUpdate() { synchronized(lock) { active?.cancel() } }
    override fun downloadAndInstall(downloadUrls: List<String>, sha256Url: String?, onProgress: (Float) -> Unit) =
        downloadAndInstall(UpdateDownloadPlan(downloadUrls, sha256Url)) { onProgress(it.legacyProgress) }

    override fun downloadAndInstall(plan: UpdateDownloadPlan, onStatus: (UpdateProgress) -> Unit) {
        val session = synchronized(lock) {
            if (active != null) null else UpdateDownloadSession().also { active = it }
        } ?: return
        var apk: File? = null
        var work: File? = null
        var handedToInstaller = false
        try {
            if (!hasInstallPermission()) {
                requestPermission(onStatus)
                return
            }
            work = File.createTempFile("rwpp-update-work-", "", context.cacheDir).apply { delete(); mkdir() }
            apk = File.createTempFile("rwpp-update-", ".apk", context.cacheDir)
            UpdatePackageDownloader(net.client).download(plan, session, work, apk, ".apk", onStatus)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            require(info?.packageName == context.packageName) { "APK package does not match this application" }
            require(compareVersions(info?.versionName.orEmpty(), projectVersion) > 0) {
                "Downloaded APK is not a newer version"
            }
            handedToInstaller = session.startInstallation {
                launchInstaller(apk)
                pendingApk = apk
                onStatus(UpdateProgress(UpdateStage.INSTALLING, 1f))
            }
        } catch (e: Exception) {
            if (!session.isCancelled) {
                logger.error("Update failed", e)
                onStatus(UpdateProgress(UpdateStage.FAILED, error = e.message))
            }
        } finally {
            work?.deleteRecursively()
            // 系统安装器仍可能读取 APK，已交接文件留给下次启动回收。
            if (!handedToInstaller) apk?.delete()
            synchronized(lock) { if (active === session) active = null }
        }
    }

    override fun installPendingUpdate(onStatus: (UpdateProgress) -> Unit): Boolean {
        val apk = pendingApk?.takeIf { it.isFile } ?: return false
        try {
            if (!hasInstallPermission()) requestPermission(onStatus)
            else {
                launchInstaller(apk)
                onStatus(UpdateProgress(UpdateStage.INSTALLING, 1f))
            }
        } catch (e: Exception) {
            onStatus(UpdateProgress(UpdateStage.FAILED, error = e.message))
        }
        return true
    }

    private fun hasInstallPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    private fun requestPermission(onStatus: (UpdateProgress) -> Unit) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onStatus(UpdateProgress(UpdateStage.PERMISSION_REQUIRED))
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }
}
