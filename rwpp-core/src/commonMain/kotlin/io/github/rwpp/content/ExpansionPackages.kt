/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.expansion.ExpansionPackageInstaller
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.expansion.InstalledExpansion
import io.github.rwpp.game.mod.ContentProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** Versioned package storage is physically distinct from ordinary Mod and theme roots. */
object ExpansionPackages {
    internal var rootOverride: File? = null
    val rootDir: File get() = (rootOverride ?: File(appKoin.get<AppContext>().internalStoragePath("expansions/")))
        .also(ContentProfiles::configureRoot)
    var installed by mutableStateOf<List<InstalledExpansion>>(emptyList())
        private set
    val activeSha: String? get() = ExpansionContentController.activeSha
    private val storage = Mutex()

    suspend fun refresh() = storage.withLock {
        val packages = withContext(Dispatchers.IO) { ExpansionPackageInstaller.scan(rootDir) }
        withContext(Dispatchers.Main.immediate) { installed = packages }
    }

    suspend fun import(archive: File): InstalledExpansion = install(archive)

    internal suspend fun install(
        archive: File,
        gameAppId: String? = null,
        binding: ExpansionBinding? = null,
    ): InstalledExpansion = storage.withLock {
        val packageFile = withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext()
            ExpansionPackageInstaller.install(archive, rootDir, gameAppId, binding, operation::ensureActive)
        }
        val packages = withContext(Dispatchers.IO) { ExpansionPackageInstaller.scan(rootDir) }
        withContext(Dispatchers.Main.immediate) { installed = packages }
        packageFile
    }

    suspend fun delete(sha256: String): Boolean = storage.withLock {
        if (ExpansionContentController.isReserved(sha256)) return@withLock false
        val candidate = installed.singleOrNull { it.sha256 == sha256 } ?: return@withLock false
        withContext(Dispatchers.IO) {
            val root = rootDir.canonicalFile.toPath()
            val path = candidate.dir.canonicalFile.toPath()
            check(path != root && path.startsWith(root)) { "Expansion package is outside its storage root" }
            ExpansionPackageInstaller.forgetActivationVerification(candidate)
            // Files.walk does not follow links, including a link inserted after validation.
            Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        val packages = withContext(Dispatchers.IO) { ExpansionPackageInstaller.scan(rootDir) }
        withContext(Dispatchers.Main.immediate) { installed = packages }
        true
    }
}
