/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.*
import io.github.rwpp.app.*
import io.github.rwpp.projectVersion
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.*
import io.github.rwpp.utils.compareVersions
import kotlinx.coroutines.*

/** 本次启动内的更新闸门；失败离线放行，发现新版后停止下载也不放行。 */
class MandatoryUpdateController(
    private val fetchRelease: () -> LatestVersionProfile?,
    private val scope: CoroutineScope,
    private val updater: AutoUpdater?,
    private val android: Boolean,
    private val currentVersion: String = projectVersion,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hasNetworkConnection: () -> Boolean = { true },
) {
    var checking by mutableStateOf(true)
        private set
    var release by mutableStateOf<LatestVersionProfile?>(null)
        private set
    var progress by mutableStateOf(UpdateProgress(UpdateStage.READY))
        private set
    private var job: Job? = null
    private var epoch = 0

    suspend fun check(): LatestVersionProfile? {
        try {
            val latest = withContext(ioDispatcher) {
                try { if (hasNetworkConnection()) fetchRelease() else null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            latest?.let(::accept)
            return latest
        } finally { checking = false }
    }

    fun accept(profile: LatestVersionProfile) {
        if (compareVersions(profile.version, currentVersion) > 0) release = profile
    }

    fun start() {
        if (job?.isActive == true) return
        val profile = release ?: return
        val plan = if (android) profile.resolveAndroidUpdatePlan() else profile.resolveDesktopUpdatePlan()
        val requestEpoch = ++epoch
        progress = UpdateProgress(UpdateStage.DOWNLOADING)
        val report: (UpdateProgress) -> Unit = { status ->
            scope.launch(Dispatchers.Main.immediate) { if (requestEpoch == epoch) progress = status }
        }
        job = scope.launch(ioDispatcher) {
            if (updater == null || !updater.isSupported() || plan == null) {
                report(UpdateProgress(UpdateStage.FAILED, error = readI18n("mandatoryUpdate.noPackage", I18nType.RWPP)))
            } else if (!updater.installPendingUpdate(report)) {
                updater.downloadAndInstall(plan, report)
            }
        }
    }

    fun cancel() {
        ++epoch
        updater?.cancelPendingUpdate()
        val previous = job
        job = scope.launch {
            previous?.join()
            progress = UpdateProgress(UpdateStage.READY)
        }
    }
}
