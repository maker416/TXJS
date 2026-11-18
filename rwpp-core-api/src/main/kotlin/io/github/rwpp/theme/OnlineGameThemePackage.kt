/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.net.games.isOnlineGameAssetUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** An approved game's archive is installed in an isolated cache, without replacing users' themes. */
class OnlineGameThemePackage(http: OkHttpClient = OkHttpClient()) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun install(binding: OnlineGameArtBinding, cacheRoot: File, checkCancelled: () -> Unit = {}): File {
        require(validBinding(binding) && isOnlineGameAssetUrl(binding.downloadUrl) && SHA256.matches(binding.archiveSha256))
        cacheRoot.mkdirs()
        val root = cacheRoot.canonicalFile
        val staging = Files.createTempDirectory(root.toPath(), "pending-").toFile()
        try {
            checkCancelled()
            val archive = File(staging, "download.rwtheme")
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(binding.downloadUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Art package download failed")
                val body = response.body ?: throw IOException("Empty art package")
                if (body.contentLength() > MAX_ARCHIVE_BYTES) throw IOException("Art package too large")
                body.byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        if (Thread.currentThread().isInterrupted) throw IOException("Art download cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_ARCHIVE_BYTES) throw IOException("Art package too large")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                } }
            }
            if (digest.digest().hex() != binding.archiveSha256) throw IOException("Art package checksum mismatch")
            val target = File(staging, "theme").apply { mkdirs() }
            unpack(archive, target, checkCancelled)
            checkCancelled()
            val spec = validateThemeToml(File(target, "theme.toml").readText(Charsets.UTF_8)).first
                ?: throw IOException("Invalid art package")
            if (!matches(binding, target, spec)) throw IOException("Art package does not match the approved release")
            // Each immutable catalog gets its own directory, including when authors reuse their local theme ID.
            val parent = File(root, binding.packId).apply { mkdirs() }.canonicalFile
            check(parent.parentFile == root) { "Invalid art cache path" }
            val destination = File(parent, binding.catalogHash)
            if (destination.exists()) {
                val existing = runCatching { validateThemeToml(File(destination, "theme.toml").readText()).first }.getOrNull()
                if (existing != null && matches(binding, destination, existing)) return destination
                throw IOException("Cached art package is damaged")
            }
            Files.move(target.toPath(), destination.toPath())
            return destination
        } finally {
            // Only this invocation's freshly-created staging directory is removed; ZIP entries never create links.
            if (staging.canonicalFile.parentFile == root) staging.deleteRecursively()
        }
    }

    private fun unpack(archive: File, target: File, checkCancelled: () -> Unit) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            if (entries.size > MAX_ENTRIES) throw IOException("Too many art package files")
            val names = HashSet<String>()
            var total = 0L
            entries.forEach { entry ->
                checkCancelled()
                val path = entry.name.removeSuffix("/")
                if (!isSafePersonalizedThemeAssetPath(path) || !names.add(path.lowercase())) {
                    throw IOException("Unsafe or duplicate art package path")
                }
                val file = File(target, path)
                if (!file.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Art package path escapes its directory")
                }
                if (entry.isDirectory) {
                    check(file.mkdirs() || file.isDirectory)
                } else {
                    val limit = if (path == "theme.toml" || path in setOf("strings_zh.toml", "strings_en.toml")) MAX_TEXT_BYTES else MAX_ENTRY_BYTES
                    if (entry.size > limit) throw IOException("Art package file too large")
                    file.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read == -1) break
                            size += read
                            total += read
                            if (size > limit || total > MAX_EXTRACTED_BYTES) throw IOException("Art package expands beyond limits")
                            output.write(buffer, 0, read)
                        }
                    } }
                }
            }
        }
        if (!File(target, "theme.toml").isFile) throw IOException("Art package has no theme manifest")
        for (name in listOf("strings_zh.toml", "strings_en.toml")) {
            val file = File(target, name)
            if (file.isFile) net.peanuuutz.tomlkt.Toml.parseToTomlTable(file.readText().replace("\r", "\n"))
        }
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
        private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 256L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 1024L * 1024
        private const val MAX_ENTRIES = 4096

        fun validBinding(binding: OnlineGameArtBinding): Boolean =
            UUID.matches(binding.packId) && VERSION.matches(binding.version) && SHA256.matches(binding.catalogHash)

        fun matches(binding: OnlineGameArtBinding, dir: File, spec: ArtThemeSpec): Boolean =
            validBinding(binding) && spec.personalization.platformPackId == binding.packId &&
                spec.theme.version == binding.version && personalizedThemeCatalog(dir, spec.personalization)?.catalogHash == binding.catalogHash

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
