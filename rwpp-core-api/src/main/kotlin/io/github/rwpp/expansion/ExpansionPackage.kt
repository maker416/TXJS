/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.expansion

import io.github.rwpp.io.AtomicFileWrite
import io.github.rwpp.theme.isSafePersonalizedThemeAssetPath
import io.github.rwpp.theme.personalizedThemeCatalog
import io.github.rwpp.theme.validateThemeToml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

const val EXPANSION_MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
const val EXPANSION_MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
const val EXPANSION_MAX_MOD_BYTES = 512L * 1024 * 1024
const val EXPANSION_MAX_MODS = 64
const val EXPANSION_MEDIA_TYPE = "application/vnd.rwjs.game-expansion+zip"

@Serializable
data class ExpansionArtBinding(
    @SerialName("pack_id") val packId: String,
    val version: String,
    @SerialName("catalog_hash") val catalogHash: String,
)

@Serializable
data class ExpansionBinding(
    val version: String,
    val sha256: String,
    val size: Long,
    @SerialName("art_binding") val artBinding: ExpansionArtBinding,
) {
    fun valid(): Boolean = VERSION.matches(version) && SHA.matches(sha256) && size in 1..EXPANSION_MAX_ARCHIVE_BYTES &&
        UUID.matches(artBinding.packId) && VERSION.matches(artBinding.version) && SHA.matches(artBinding.catalogHash)
}

@Serializable
data class ExpansionMod(
    val id: String, val path: String, val size: Long, val sha256: String,
    @SerialName("tree_sha256") val treeSha256: String,
)

@Serializable
data class ExpansionTheme(
    val path: String = "theme",
    @SerialName("pack_id") val packId: String,
    val version: String,
    @SerialName("catalog_hash") val catalogHash: String,
)

@Serializable
data class ExpansionManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("game_app_id") val gameAppId: String,
    val name: String,
    val mods: List<ExpansionMod> = emptyList(),
    val theme: ExpansionTheme,
)

data class InstalledExpansion(
    val dir: File, val manifest: ExpansionManifest, val version: String, val sha256: String, val size: Long,
) {
    val gameAppId: String get() = manifest.gameAppId
    val name: String get() = manifest.name
    val themeDir: File get() = File(dir, manifest.theme.path)
    val modFiles: List<File> get() = manifest.mods.map { File(dir, it.path) }
    val binding: ExpansionBinding get() = ExpansionBinding(version, sha256, size,
        ExpansionArtBinding(manifest.theme.packId, manifest.theme.version, manifest.theme.catalogHash))
}

private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val SHA = Regex("[0-9a-f]{64}")
private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
private val MOD_ID = Regex("[a-z][a-z0-9_-]{0,63}")

/** Pure JVM installer. Content never enters the ordinary mod directory or network-mod cache. */
object ExpansionPackageInstaller {
    private const val MARKER = ".installation.json"
    private const val MAX_FILES = 65536
    private const val MAX_TEXT_BYTES = 1024L * 1024
    private val json = Json { ignoreUnknownKeys = false }

    @Serializable private data class Marker(val version: String, val sha256: String, val size: Long)
    private class Budget(var bytes: Long = 0, var files: Int = 0)

    @Synchronized
    fun install(archive: File, root: File, expectedGameAppId: String? = null,
        expectedBinding: ExpansionBinding? = null, checkCancelled: () -> Unit = {}): InstalledExpansion {
        if (!archive.isFile || archive.length() !in 1..EXPANSION_MAX_ARCHIVE_BYTES) invalid("Invalid expansion archive size")
        if (expectedBinding != null && !expectedBinding.valid()) invalid("Invalid approved expansion binding")
        val hash = hashFile(archive, checkCancelled)
        if (expectedBinding != null && (hash != expectedBinding.sha256 || archive.length() != expectedBinding.size)) invalid("Expansion archive checksum mismatch")
        root.mkdirs()
        val realRoot = root.canonicalFile
        val staging = Files.createTempDirectory(realRoot.toPath(), "pending-").toFile()
        try {
            val unpacked = File(staging, "package").apply { mkdirs() }
            val budget = Budget()
            unpack(archive, unpacked, budget, checkCancelled, outer = true)
            val candidate = read(unpacked, Marker(readVersion(unpacked), hash, archive.length()))
            if (expectedGameAppId != null && candidate.gameAppId != expectedGameAppId) invalid("Expansion belongs to another game")
            if (expectedBinding != null && candidate.binding != expectedBinding) invalid("Expansion does not match the approved release")
            validateFiles(candidate, budget, staging, checkCancelled)
            val existing = scan(realRoot)
            if (existing.any { it.gameAppId == candidate.gameAppId && it.version == candidate.version && it.sha256 != hash }) {
                invalid("A different expansion already uses this game and version")
            }
            val gameRoot = File(realRoot, candidate.gameAppId).apply { mkdirs() }.canonicalFile
            if (gameRoot.parentFile != realRoot) invalid("Invalid expansion directory")
            val destination = File(gameRoot, hash)
            if (destination.exists()) {
                val installed = existing.singleOrNull { it.dir.canonicalFile == destination.canonicalFile && it.binding == candidate.binding }
                    ?: invalid("Existing expansion installation is damaged")
                verify(installed, checkCancelled)
                return installed
            }
            checkCancelled()
            // Retain the exact approved archive for cache revalidation and immutable revision identity.
            Files.copy(archive.toPath(), File(unpacked, "content.zip").toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            File(unpacked, MARKER).writeText(json.encodeToString(Marker.serializer(), Marker(candidate.version, hash, candidate.size)))
            Files.move(unpacked.toPath(), destination.toPath())
            return candidate.copy(dir = destination)
        } finally {
            if (staging.canonicalFile.parentFile == realRoot) staging.deleteRecursively()
        }
    }

    fun scan(root: File): List<InstalledExpansion> = root.listFiles().orEmpty().filter { UUID.matches(it.name) && realDirectory(it) }
        .flatMap { app -> app.listFiles().orEmpty().filter { SHA.matches(it.name) && realDirectory(it) }.mapNotNull { dir ->
            runCatching {
                val marker = File(dir, MARKER)
                if (marker.length() !in 1..4096 || !realFile(marker)) return@runCatching null
                read(dir, json.decodeFromString(Marker.serializer(), marker.readText())).takeIf {
                    it.gameAppId == app.name && it.sha256 == dir.name && File(dir, "content.zip").length() == it.size
                }
            }.getOrNull()
        } }.sortedWith(compareBy({ it.name }, { it.version }))

    fun verify(installed: InstalledExpansion, checkCancelled: () -> Unit = {}) {
        val archive = File(installed.dir, "content.zip")
        if (!realFile(archive) || archive.length() != installed.size || hashFile(archive, checkCancelled) != installed.sha256) invalid("Cached expansion archive is damaged")
        val staging = Files.createTempDirectory(installed.dir.parentFile.toPath(), "verify-").toFile()
        try {
            // The archive digest also authenticates default art/fonts/strings, not only personalized assets.
            ZipFile(archive).use { zip ->
                val expected = HashSet<String>()
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    checkCancelled()
                    if (!isSafePersonalizedThemeAssetPath(entry.name) || !expected.add(entry.name)) invalid("Invalid cached expansion archive")
                    val file = File(installed.dir, entry.name)
                    if (!realFile(file) || file.length() != entry.size) invalid("Cached expansion file changed")
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { checkCancelled(); val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                    }
                    if (hashFile(file, checkCancelled) != digest.digest().hex()) invalid("Cached expansion file changed")
                }
                val actual = installed.dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(installed.dir).invariantSeparatorsPath }
                    .filterNot { it == MARKER || it == "content.zip" }.toSet()
                if (expected != actual) invalid("Cached expansion contains unexpected files")
            }
            validateFiles(installed, Budget(), staging, checkCancelled)
        }
        finally { staging.deleteRecursively() }
    }

    // Store client-generated receipts beside the immutable package, outside the ZIP namespace.
    private fun activationRecord(installed: InstalledExpansion) =
        File(installed.dir.parentFile, ".${installed.dir.name}.verified-v1")

    fun forgetActivationVerification(installed: InstalledExpansion) {
        activationRecord(installed).delete()
    }

    /** Returns true when a previous complete verification still covers this installation. */
    fun verifyForActivation(installed: InstalledExpansion, changeToken: (File) -> String?,
        checkCancelled: () -> Unit = {}): Boolean {
        val before = installationFingerprint(installed, changeToken, checkCancelled)
        val record = activationRecord(installed)
        if (before != null && realFile(record) && record.length() == before.size.toLong()) {
            val matches = try {
                DataInputStream(record.inputStream()).use { input ->
                    val saved = ByteArray(before.size)
                    input.readFully(saved)
                    saved.contentEquals(before) && input.read() == -1
                }
            } catch (_: IOException) { false }
            checkCancelled()
            if (matches) return true
        }

        verify(installed, checkCancelled)
        if (before == null) return false
        val after = installationFingerprint(installed, changeToken, checkCancelled)
        if (!before.contentEquals(after)) invalid("Expansion changed during verification")
        checkCancelled()
        try {
            AtomicFileWrite.write(record) { it.write(before) }
        } catch (_: IOException) {
            // Read-only installations can still use the completed verification.
        }
        return false
    }

    private fun installationFingerprint(installed: InstalledExpansion, changeToken: (File) -> String?,
        checkCancelled: () -> Unit): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        field(installed.sha256)
        field(installed.size.toString())
        field(installed.version)
        field(json.encodeToString(ExpansionManifest.serializer(), installed.manifest))

        fun visit(file: File): Boolean {
            checkCancelled()
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attrs.isDirectory && !attrs.isRegularFile) invalid("Expansion contains links or special files")
            field(file.relativeTo(installed.dir).invariantSeparatorsPath)
            field(if (attrs.isDirectory) "dir" else "file")
            field(changeToken(file) ?: return false)
            if (attrs.isRegularFile) {
                field(attrs.size().toString())
                return true
            }
            val children = file.listFiles() ?: invalid("Cannot read expansion directory")
            return children.sortedBy { it.name }.all(::visit)
        }
        return if (visit(installed.dir)) digest.digest() else null
    }

    private fun read(dir: File, marker: Marker): InstalledExpansion {
        val manifestFile = File(dir, "expansion.toml")
        if (!realFile(manifestFile) || manifestFile.length() !in 1..MAX_TEXT_BYTES) invalid("Missing expansion manifest")
        val manifest = Toml.decodeFromString(ExpansionManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
        if (manifest.schemaVersion != 1 || !UUID.matches(manifest.gameAppId) || manifest.name.isBlank() ||
            manifest.name.codePointCount(0, manifest.name.length) > 128 || manifest.mods.size > EXPANSION_MAX_MODS) invalid("Invalid expansion manifest")
        if (manifest.theme.path != "theme" || !UUID.matches(manifest.theme.packId) || !VERSION.matches(manifest.theme.version) || !SHA.matches(manifest.theme.catalogHash)) invalid("Invalid expansion theme")
        val ids = HashSet<String>()
        manifest.mods.forEach {
            if (!MOD_ID.matches(it.id) || !ids.add(it.id) || it.path != "mods/${it.id}.rwmod" ||
                it.size !in 1..EXPANSION_MAX_MOD_BYTES || !SHA.matches(it.sha256) || !SHA.matches(it.treeSha256)) invalid("Invalid expansion mod")
        }
        if (!SHA.matches(marker.sha256) || marker.size !in 1..EXPANSION_MAX_ARCHIVE_BYTES || marker.version != readVersion(dir)) invalid("Invalid expansion installation")
        return InstalledExpansion(dir, manifest, marker.version, marker.sha256, marker.size)
    }

    private fun readVersion(dir: File): String {
        val file = File(dir, "version.txt")
        if (!realFile(file) || file.length() !in 1..128) invalid("Missing expansion version")
        val value = file.readText(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
        if (!VERSION.matches(value)) invalid("Invalid expansion version")
        return value
    }

    private fun validateFiles(installed: InstalledExpansion, budget: Budget, staging: File, checkCancelled: () -> Unit) {
        val allowedMods = installed.manifest.mods.map { it.path }.toSet()
        installed.dir.walkTopDown().forEach { file ->
            checkCancelled()
            if (!realFile(file) && !realDirectory(file)) invalid("Expansion contains links or special files")
            if (file.isFile) {
                val path = file.relativeTo(installed.dir).invariantSeparatorsPath
                if (path !in setOf("version.txt", "expansion.toml", MARKER, "content.zip") && path !in allowedMods && !path.startsWith("theme/")) invalid("Undeclared expansion file")
            }
        }
        installed.manifest.mods.forEach { mod ->
            val file = File(installed.dir, mod.path)
            if (!realFile(file) || file.length() != mod.size || hashFile(file, checkCancelled) != mod.sha256) invalid("Expansion mod checksum mismatch")
            val expanded = File(staging, "mod-${mod.id}").apply { mkdirs() }
            unpack(file, expanded, budget, checkCancelled, outer = false)
            if (!realFile(File(expanded, "mod-info.txt")) || portableTreeSha256(expanded, checkCancelled) != mod.treeSha256) invalid("Expansion mod tree mismatch")
            expanded.deleteRecursively()
        }
        val themeManifest = File(installed.themeDir, "theme.toml")
        if (installed.themeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } > 256L * 1024 * 1024) invalid("Expansion art exceeds size limits")
        if (!realFile(themeManifest) || themeManifest.length() !in 1..MAX_TEXT_BYTES) invalid("Missing expansion theme manifest")
        val spec = validateThemeToml(themeManifest.readText(Charsets.UTF_8)).first ?: invalid("Invalid expansion theme")
        if (spec.personalization.platformPackId != installed.manifest.theme.packId || spec.theme.version != installed.manifest.theme.version ||
            personalizedThemeCatalog(installed.themeDir, spec.personalization, allowEmptyVariants = true)?.catalogHash != installed.manifest.theme.catalogHash) invalid("Expansion art catalog mismatch")
        listOf("strings_zh.toml", "strings_en.toml").forEach { name ->
            File(installed.themeDir, name).takeIf { it.isFile }?.let { file ->
                if (file.length() > MAX_TEXT_BYTES) invalid("Expansion strings too large")
                Toml.parseToTomlTable(file.readText().replace("\r", "\n"))
            }
        }
    }

    private fun unpack(archive: File, destination: File, budget: Budget, checkCancelled: () -> Unit, outer: Boolean) {
        ZipFile(archive).use { zip ->
            val seen = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled()
                val entry = entries.nextElement()
                if (++budget.files > MAX_FILES) invalid("Too many expansion files")
                val name = entry.name.removeSuffix("/")
                if (!isSafePersonalizedThemeAssetPath(name) || !seen.add(name.lowercase())) invalid("Unsafe or duplicate expansion path")
                if (outer && name !in setOf("version.txt", "expansion.toml", "mods", "theme") && !name.startsWith("mods/") && !name.startsWith("theme/")) invalid("Unsupported expansion content")
                val target = File(destination, name)
                if (!target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) invalid("Expansion path escapes directory")
                if (entry.isDirectory) { if (!target.mkdirs() && !target.isDirectory) invalid("Invalid expansion directory") }
                else {
                    val limit = if (name in setOf("version.txt", "expansion.toml") || name.endsWith("theme.toml") || name.endsWith("strings_zh.toml") || name.endsWith("strings_en.toml")) MAX_TEXT_BYTES
                        else if (outer && name.startsWith("mods/")) EXPANSION_MAX_MOD_BYTES else EXPANSION_MAX_EXTRACTED_BYTES
                    if (entry.size < 0 || entry.size > limit || entry.size > EXPANSION_MAX_EXTRACTED_BYTES - budget.bytes) invalid("Expansion extraction exceeds limits")
                    target.parentFile.mkdirs()
                    val crc = CRC32()
                    var count = 0L
                    zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read; budget.bytes += read
                            if (count > entry.size || count > limit || budget.bytes > EXPANSION_MAX_EXTRACTED_BYTES) invalid("Expansion extraction exceeds limits")
                            crc.update(buffer, 0, read); output.write(buffer, 0, read)
                        }
                    } }
                    if (count != entry.size || crc.value != entry.crc) invalid("Corrupt expansion ZIP entry")
                }
            }
        }
    }

    fun hashFile(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { checkCancelled(); val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().hex()
    }

    /** Same portable source tree identity as the independent server preparation tool. */
    fun portableTreeSha256(root: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) {} ; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
        DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
            root.walkTopDown().toList().sortedWith { a, b -> compareUtf8(a.relativeTo(root).invariantSeparatorsPath, b.relativeTo(root).invariantSeparatorsPath) }
                .forEach { file ->
                    checkCancelled()
                    if (!realDirectory(file) && !realFile(file)) invalid("Mod contains a link or special file")
                    val path = (if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath).toByteArray(Charsets.UTF_8)
                    output.writeByte(if (file.isDirectory) 0 else 1); output.writeInt(path.size); output.write(path)
                    if (file.isFile) {
                        output.writeLong(file.length())
                        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { checkCancelled(); val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read) } }
                    }
                }
        }
        return digest.digest().hex()
    }

    private fun compareUtf8(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 255) - (right[index].toInt() and 255)
            if (difference != 0) return difference
        }
        return left.size - right.size
    }
    private fun realFile(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())
    private fun realDirectory(file: File) = Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalid(message: String): Nothing = throw IOException(message)
}
