/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import io.github.rwpp.account.AccountSession
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.net.appearance.AppearanceClient
import io.github.rwpp.net.appearance.GameTestIdentity
import io.github.rwpp.net.sync.direct.DirectContentArtifact
import io.github.rwpp.net.sync.direct.DirectModDownloadProgress
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

data class ExpansionJoinExpectation(val gameAppId: String, val binding: ExpansionBinding, val testIdentity: GameTestIdentity? = null)

/** Direct bytes alone cannot grant a game identity: approval is looked up by the actual TCP peer. */
internal object ExpansionDirectSync {
    internal suspend fun authorize(
        artifact: DirectContentArtifact,
        transport: DirectModSyncTransport,
        expected: ExpansionJoinExpectation?,
        serverId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val peer = transport.peerEndpoint ?: throw IOException("Missing actual Direct peer")
        val testIdentity = expected?.testIdentity
        if (testIdentity != null && testIdentity.accountToken != AccountSession.sessionSnapshotOrNull()?.token) {
            throw IOException("Game test session changed")
        }
        val identity = AppearanceClient(resolveOpenPlatformUrl()).lookupServerInfo(peer, testIdentity)
            ?: throw IOException("This endpoint has no approved game expansion")
        val appId = identity.gameAppId
        val binding = identity.expansionBinding
        if (appId == null || binding == null || !binding.valid() ||
            artifact.sha256 != binding.sha256 || artifact.version != binding.version || artifact.size != binding.size ||
            expected != null && (appId != expected.gameAppId || binding != expected.binding) ||
            serverId != null && identity.serverId != serverId) {
            throw IOException("Direct content does not match the approved game release")
        }
        identity
    }

    suspend fun prepareAndActivate(artifact: DirectContentArtifact, transport: DirectModSyncTransport,
        expected: ExpansionJoinExpectation?, allowDownload: Boolean,
        progress: suspend (DirectModDownloadProgress) -> Unit): Long {
        val identity = authorize(artifact, transport, expected)
        val appId = requireNotNull(identity.gameAppId)
        val binding = requireNotNull(identity.expansionBinding)
        ExpansionPackages.refresh()
        var installed = ExpansionPackages.installed.singleOrNull { it.gameAppId == appId && it.binding == binding }
        if (installed == null) {
            if (!allowDownload) throw IOException("The original expansion is not installed for recovery")
            val archive = transport.downloadArtifact(artifact, ExpansionPackages.rootDir.resolve(".transfers"), progress)
            try {
                installed = ExpansionPackages.install(archive, appId, binding)
            } finally { archive.delete() }
        }
        val authorization = ExpansionJoinExpectation(appId, binding, expected?.testIdentity)
        authorize(artifact, transport, authorization, identity.serverId)
        val token = ExpansionContentController.prepare(requireNotNull(installed), appId)
        try {
            ExpansionContentController.activate(token)
            authorize(artifact, transport, authorization, identity.serverId)
            return token
        } catch (error: Throwable) {
            withContext(NonCancellable) { ExpansionContentController.cancel(token) }
            throw error
        }
    }
}
