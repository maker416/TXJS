/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.expansion.ExpansionPackageInstaller
import io.github.rwpp.net.appearance.AppearanceClient
import io.github.rwpp.net.sync.direct.DirectContentArtifact
import io.github.rwpp.net.sync.direct.DirectModDownloadProgress
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

data class ExpansionJoinExpectation(val gameAppId: String, val binding: ExpansionBinding)

/** Direct bytes alone cannot grant a game identity: approval is looked up by the actual TCP peer. */
internal object ExpansionDirectSync {
    suspend fun prepareAndActivate(artifact: DirectContentArtifact, transport: DirectModSyncTransport,
        expected: ExpansionJoinExpectation?, allowDownload: Boolean,
        progress: suspend (DirectModDownloadProgress) -> Unit): Long {
        val peer = transport.peerEndpoint ?: throw IOException("Missing actual Direct peer")
        val client = AppearanceClient(resolveOpenPlatformUrl())
        suspend fun authorize() = withContext(Dispatchers.IO) {
            client.lookupServerInfo(peer)?.takeIf { it.gameAppId != null && it.expansionBinding != null }
                ?: throw IOException("This endpoint has no approved game expansion")
        }
        val identity = authorize()
        val appId = requireNotNull(identity.gameAppId)
        val binding = requireNotNull(identity.expansionBinding)
        if (!binding.valid() || artifact.sha256 != binding.sha256 || artifact.version != binding.version || artifact.size != binding.size ||
            expected != null && (appId != expected.gameAppId || binding != expected.binding)) {
            throw IOException("Direct content does not match the approved game release")
        }
        ExpansionPackages.refresh()
        var installed = ExpansionPackages.installed.singleOrNull { it.gameAppId == appId && it.binding == binding }
        if (installed == null) {
            if (!allowDownload) throw IOException("The original expansion is not installed for recovery")
            val archive = transport.downloadArtifact(artifact, ExpansionPackages.rootDir.resolve(".transfers"), progress)
            try {
                installed = withContext(Dispatchers.IO) {
                    val operation = currentCoroutineContext()
                    ExpansionPackageInstaller.install(archive, ExpansionPackages.rootDir, appId, binding) { operation.ensureActive() }
                }
            } finally { archive.delete() }
            ExpansionPackages.refresh()
        }
        val after = authorize()
        if (after.serverId != identity.serverId || after.gameAppId != appId || after.expansionBinding != binding) {
            throw IOException("Game expansion approval changed during synchronization")
        }
        val token = ExpansionContentController.prepare(requireNotNull(installed), appId)
        try {
            ExpansionContentController.activate(token)
            val finalApproval = authorize()
            if (finalApproval.serverId != identity.serverId || finalApproval.gameAppId != appId || finalApproval.expansionBinding != binding) {
                throw IOException("Game expansion approval changed during content loading")
            }
            return token
        } catch (error: Throwable) {
            withContext(NonCancellable) { ExpansionContentController.cancel(token) }
            throw error
        }
    }
}
