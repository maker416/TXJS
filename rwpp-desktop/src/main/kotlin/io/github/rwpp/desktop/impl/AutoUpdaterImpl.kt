/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.app.*
import io.github.rwpp.AppContext
import io.github.rwpp.platform.WindowsUpdateInstaller
import io.github.rwpp.net.Net
import io.github.rwpp.net.UpdateDownloadPlan
import io.github.rwpp.logger
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.component.get
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val net: Net by inject()
    private val lock = Any()
    private var active: UpdateDownloadSession? = null
    override fun isSupported() = System.getProperty("os.name").startsWith("Windows", true)
    override fun cancelPendingUpdate() { synchronized(lock) { active?.cancel() } }
    override fun downloadAndInstall(downloadUrls: List<String>, sha256Url: String?, onProgress: (Float) -> Unit) =
        downloadAndInstall(UpdateDownloadPlan(downloadUrls, sha256Url)) { onProgress(it.legacyProgress) }

    override fun downloadAndInstall(plan: UpdateDownloadPlan, onStatus: (UpdateProgress) -> Unit) {
        val session = synchronized(lock) {
            if (active != null) null else UpdateDownloadSession().also { active = it }
        } ?: return
        var directory: File? = null
        var started = false
        try {
            directory = Files.createTempDirectory("RWJS-update-").toFile()
            val installer = UpdatePackageDownloader(net.client).download(plan, session, directory,
                File(directory, "RWJS-Setup-update.exe"), ".exe", onStatus)
            val launched = CompletableFuture<Unit>()
            val allowed = session.startInstallation {
                onStatus(UpdateProgress(UpdateStage.INSTALLING, 1f))
                thread(name = "RWJS-install", isDaemon = true) {
                    try {
                        session.checkActive()
                        WindowsUpdateInstaller().launch(installer)
                        launched.complete(Unit)
                    } catch (failure: Throwable) { launched.completeExceptionally(failure) }
                }
            }
            // UAC 在独立线程等待；主线程的退出/取消不被安装交接锁阻塞。
            if (allowed) {
                launched.get()
                started = true
            }
        } catch (e: Exception) {
            if (!session.isCancelled) {
                logger.error("Update failed", e)
                onStatus(UpdateProgress(UpdateStage.FAILED, error = e.cause?.message ?: e.message))
            }
        } finally {
            directory?.walkBottomUp()?.forEach {
                if (!started || it.name != "RWJS-Setup-update.exe") it.delete()
            }
            synchronized(lock) { if (active === session) active = null }
        }
        if (started) get<AppContext>().exit()
    }
}
