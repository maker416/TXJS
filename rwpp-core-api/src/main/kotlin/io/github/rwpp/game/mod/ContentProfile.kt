/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** Only the current package's immutable files can participate in an expansion game. */
sealed interface ContentProfile {
    data class Normal(val selection: Map<String, Boolean>? = null) : ContentProfile
    data class Expansion(val gameAppId: String, val revision: String, val sources: List<ContentModSource>) : ContentProfile
}

data class ContentModSource(val id: String, val file: File)
data class ModSettingsSnapshot(val modSettings: String?, val version: Int, val lastModCount: Int)

/** Engine-independent source policy, used by every native scan before parsing units. */
object ContentProfiles {
    @Volatile var current: ContentProfile = ContentProfile.Normal()
        private set
    @Volatile var preserveSelection: Boolean = false
        private set
    @Volatile private var roots: List<File> = emptyList()
    private var startupChecked = false
    private var startupRecovery = false
    internal var recoveryFileOverride: File? = null

    val expansionActive: Boolean get() = current is ContentProfile.Expansion
    val expansionSources: List<ContentModSource> get() = (current as? ContentProfile.Expansion)?.sources.orEmpty()
    val recoveryFile: File? get() = recoveryFileOverride ?: runCatching {
        File(appKoin.get<AppContext>().internalStoragePath("expansions/"), ".session-baseline.properties")
    }.getOrNull()

    fun configureRoot(root: File) { roots = (roots + root.canonicalFile).distinct() }
    fun isExpansionPath(path: String): Boolean = runCatching {
        val file = File(path).canonicalFile
        roots.any { root -> file.toPath().startsWith(root.toPath()) }
    }.getOrDefault(false)

    /** null leaves an ordinary native selection alone; expansion selection never falls back by filename. */
    fun resolve(candidates: List<String>): Boolean? {
        val profile = current
        if (profile is ContentProfile.Expansion) {
            val files = profile.sources.map { it.file.canonicalPath }.toSet()
            return candidates.any { runCatching { File(it).canonicalPath in files }.getOrDefault(false) }
        }
        if (candidates.any(::isExpansionPath)) return false
        return (profile as ContentProfile.Normal).selection?.let { resolveModEnabledByFileName(candidates, it) }
    }

    fun stage(profile: ContentProfile) { current = profile; preserveSelection = true }
    fun finishNormal() { current = ContentProfile.Normal(); preserveSelection = false }

    /** Journal is persisted before touching engine state; no tokens or server credentials are stored. */
    fun writeRecovery(snapshot: ModSettingsSnapshot, selection: Map<String, Boolean>) {
        val file = requireNotNull(recoveryFile) { "Expansion recovery storage unavailable" }
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("settings.present", (snapshot.modSettings != null).toString())
            setProperty("settings", snapshot.modSettings.orEmpty())
            setProperty("version", snapshot.version.toString())
            setProperty("lastModCount", snapshot.lastModCount.toString())
            selection.forEach { (path, enabled) ->
                setProperty("source." + Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8)), enabled.toString())
            }
        }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile(".baseline-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { properties.store(it, "RWJS normal content recovery") }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }

    fun readRecovery(): Pair<ModSettingsSnapshot, Map<String, Boolean>>? {
        val file = recoveryFile?.takeIf { it.isFile } ?: return null
        check(file.length() <= 2L * 1024 * 1024) { "Expansion recovery journal is too large" }
        val properties = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
        check(properties.getProperty("schema") == "1") { "Invalid expansion recovery journal" }
        val snapshot = ModSettingsSnapshot(
            properties.getProperty("settings").takeIf { properties.getProperty("settings.present") == "true" },
            properties.getProperty("version").toInt(), properties.getProperty("lastModCount").toInt(),
        )
        val selection = properties.stringPropertyNames().filter { it.startsWith("source.") }.associate { key ->
            String(Base64.getUrlDecoder().decode(key.removePrefix("source.")), Charsets.UTF_8) to
                properties.getProperty(key).toBooleanStrict()
        }
        return snapshot to selection
    }

    /** Called by the first native scan, after SettingsEngine exists and before any unit is parsed. */
    fun recoverBeforeFirstScan(restore: (ModSettingsSnapshot) -> Unit) {
        if (startupChecked) return
        recoveryFile?.parentFile?.let(::configureRoot)
        val recovery = readRecovery()
        if (recovery == null) { startupChecked = true; return }
        restore(recovery.first)
        current = ContentProfile.Normal(recovery.second)
        preserveSelection = true
        startupRecovery = true
        startupChecked = true
    }

    fun completeStartupScan() {
        if (!startupRecovery) return
        startupRecovery = false
        finishNormal()
        clearRecovery()
    }

    fun clearRecovery() { recoveryFile?.let { check(!it.exists() || it.delete()) { "Cannot clear expansion recovery journal" } } }

    internal fun resetForTests() {
        current = ContentProfile.Normal(); preserveSelection = false; roots = emptyList()
        startupChecked = false; startupRecovery = false; recoveryFileOverride = null
    }
}
