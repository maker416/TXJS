/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.app.*
import io.github.rwpp.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MandatoryUpdateControllerTest {
    private val newer = LatestVersionProfile("2.0.0", "notes", false, listOf(
        ReleaseAsset("RWJS-Android.zip.001", "https://example.com/1"),
        ReleaseAsset("RWJS-Android.zip.002", "https://example.com/2"),
        ReleaseAsset("RWJS-Android.zip.sha256", "https://example.com/sha")))
    private class Updater : AutoUpdater {
        var calls = 0
        var cancellations = 0
        val plans = mutableListOf<UpdateDownloadPlan>()
        var report: ((UpdateProgress) -> Unit)? = null
        override fun isSupported() = true
        override fun cancelPendingUpdate() { cancellations++ }
        override fun downloadAndInstall(downloadUrls: List<String>, sha256Url: String?, onProgress: (Float) -> Unit) = error("rich plan required")
        override fun downloadAndInstall(plan: UpdateDownloadPlan, onStatus: (UpdateProgress) -> Unit) {
            calls++; plans += plan; report = onStatus
            onStatus(UpdateProgress(UpdateStage.PERMISSION_REQUIRED))
        }
    }

    @Test fun offlineLaunchDoesNotRememberPreviousLaunchRequirement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val online = MandatoryUpdateController({ newer }, this, null, true, "1.0.0", dispatcher)
        online.check()
        assertEquals(newer, online.release)
        val offline = MandatoryUpdateController({ null }, this, null, true, "1.0.0", dispatcher)
        offline.check()
        assertFalse(offline.checking)
        assertNull(offline.release)
        val failed = MandatoryUpdateController({ throw java.io.IOException("offline") }, this, null, true, "1.0.0", dispatcher)
        failed.check()
        assertFalse(failed.checking)
        assertNull(failed.release)
    }

    @Test fun currentAndOlderVersionsAllowLauncher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (version in listOf("1.0.0", "0.9.0")) {
            val controller = MandatoryUpdateController({ newer.copy(version = version) }, this, null, true, "1.0.0", dispatcher)
            controller.check()
            assertNull(controller.release)
        }
    }

    @Test fun permissionRetryPreservesEveryVolumeAndChecksumAndCancelKeepsGate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val updater = Updater()
            val controller = MandatoryUpdateController({ newer }, this, updater, true, "1.0.0", dispatcher)
            controller.check()
            controller.start(); advanceUntilIdle()
            assertEquals(UpdateStage.PERMISSION_REQUIRED, controller.progress.stage)
            controller.start(); advanceUntilIdle()
            assertEquals(2, updater.calls)
            assertEquals(updater.plans[0], updater.plans[1])
            assertEquals(2, updater.plans[1].partUrls.size)
            assertEquals("https://example.com/sha", updater.plans[1].sha256Url)
            val oldReport = updater.report!!
            controller.cancel(); advanceUntilIdle()
            oldReport(UpdateProgress(UpdateStage.FAILED, error = "late callback")); advanceUntilIdle()
            assertEquals(UpdateStage.READY, controller.progress.stage)
            assertEquals(newer, controller.release)
            assertEquals(1, updater.cancellations)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun disconnectedNetworkSkipsRequestCompletely() = runTest {
        var calls = 0
        val controller = MandatoryUpdateController({ calls++; newer }, this, null, true, "1.0.0",
            StandardTestDispatcher(testScheduler), hasNetworkConnection = { false })
        controller.check()
        assertEquals(0, calls)
        assertNull(controller.release)
        assertFalse(controller.checking)
    }
}
