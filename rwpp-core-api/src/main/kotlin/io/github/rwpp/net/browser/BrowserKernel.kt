/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import io.github.rwpp.app.UpdateDownloadSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.SequenceInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock

@Serializable data class BrowserKernelPart(val name: String, val size: Long, val sha256: String)
@Serializable data class BrowserKernelFile(val path: String, val size: Long, val sha256: String, val executable: Boolean = false)
@Serializable data class BrowserKernelManifest(
    val format: Int, val id: String, val engine: String, val version: String,
    val platform: String, val arch: String, val releaseTag: String, val sha256: String,
    val parts: List<BrowserKernelPart>, val files: List<BrowserKernelFile>,
    val componentVersion: String = "0.0.0",
)
data class BrowserKernelRequirement(
    val id: String, val releaseTag: String, val manifestName: String,
    val manifestSha256: String, val manifest: BrowserKernelManifest,
    val manifestJson: String = Json.encodeToString(manifest),
) {
    companion object {
        fun read(text: String): BrowserKernelRequirement {
            val value = Json.parseToJsonElement(text).jsonObject
            val embedded = value.getValue("embeddedManifest").toString()
            val result = BrowserKernelRequirement(value.getValue("id").jsonPrimitive.content,
                value.getValue("releaseTag").jsonPrimitive.content, value.getValue("manifest").jsonPrimitive.content,
                value.getValue("manifestSha256").jsonPrimitive.content,
                Json.decodeFromString<BrowserKernelManifest>(embedded), embedded)
            require(digest(embedded.toByteArray()) == result.manifestSha256) { "Bundled kernel manifest is invalid" }
            result.validate()
            return result
        }
        fun fromManifest(text: String, name: String): BrowserKernelRequirement {
            val normalized = Json.parseToJsonElement(text).toString()
            val manifest = Json.decodeFromString<BrowserKernelManifest>(normalized)
            return BrowserKernelRequirement(manifest.id, manifest.releaseTag, name, digest(normalized.toByteArray()), manifest, normalized).also { it.validate() }
        }
    }
    fun toJson(): String = buildJsonObject {
        put("id", id); put("releaseTag", releaseTag); put("manifest", manifestName); put("manifestSha256", manifestSha256)
        put("embeddedManifest", Json.parseToJsonElement(manifestJson))
    }.toString()
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_.+-]+")) && manifest.id == id && manifest.releaseTag == releaseTag)
        require(releaseTag.isNotBlank() && manifest.format in 1..99)
        val componentVersion = requireNotNull(BrowserKernelVersion.parse(manifest.componentVersion)) { "Invalid browser component version" }
        val legacy = componentVersion == BrowserKernelVersion(0, 0, 0)
        require(id == "${manifest.engine}-${manifest.version}-v${manifest.format}-${manifest.platform}-${manifest.arch}" + (if (legacy) "" else "-k$componentVersion"))
        require(manifest.parts.isNotEmpty() && manifest.parts.size <= 32 && manifest.parts.map { it.name }.distinct().size == manifest.parts.size)
        require(manifest.parts.all { safeName(it.name) && it.size in 1..(95L * 1024 * 1024) && validHash(it.sha256) })
        require(manifest.files.isNotEmpty() && manifest.files.size <= 4096 && manifest.files.map { it.path }.distinct().size == manifest.files.size)
        require(manifest.files.all { safePath(it.path) && it.size in 0..(1024L * 1024 * 1024) && validHash(it.sha256) })
        require(manifest.files.sumOf { it.size } <= 2L * 1024 * 1024 * 1024)
        require(validHash(manifest.sha256) && validHash(manifestSha256) && safeName(manifestName))
        if (!legacy) {
            val base = "RWJS-Browser-${manifest.engine}-${manifest.platform}-${manifest.arch}-v$componentVersion"
            require(manifestName == "$base.json") { "Kernel filename/version mismatch" }
            require(manifest.parts.mapIndexed { index, part -> part.name == "$base.kernel.${(index + 1).toString().padStart(3, '0')}" }.all { it }) { "Invalid kernel volume order" }
        }
    }
}

enum class BrowserKernelStage { CHECKING, DOWNLOADING, VERIFYING, INSTALLING, READY }
data class BrowserKernelProgress(val stage: BrowserKernelStage, val bytes: Long = 0, val total: Long = 0)

/** 可替换的附件定位；生产只访问最新 Gitee Release，测试使用本地 HTTP 服务。 */
fun interface BrowserKernelSource { fun asset(tag: String, name: String, session: UpdateDownloadSession): String }

class GiteeBrowserKernelSource(client: OkHttpClient,
    private val latestUrl: String = "https://gitee.com/api/v5/repos/maker416/TXJS/releases/latest") : BrowserKernelSource {
    private val client = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    @Volatile private var release: BrowserKernelRelease? = null
    fun latest(session: UpdateDownloadSession): BrowserKernelRelease {
        val result = session.execute(client.newCall(Request.Builder().url(latestUrl).build())) { response ->
            if (!response.isSuccessful) throw IOException("Kernel release returned HTTP ${response.code}")
            val json = Json.parseToJsonElement(readKernelMetadata(response.body?.byteStream() ?: throw IOException("Empty release"))).jsonObject
            val tag = json.getValue("tag_name").jsonPrimitive.content
            require(tag.isNotBlank())
            val assets = json["assets"]?.jsonArray.orEmpty().map { entry ->
                val item = entry.jsonObject
                BrowserKernelReleaseAsset(item.getValue("name").jsonPrimitive.content, item.getValue("browser_download_url").jsonPrimitive.content)
            }
            BrowserKernelRelease(tag, assets)
        }
        release = result
        return result
    }
    override fun asset(tag: String, name: String, session: UpdateDownloadSession): String {
        return (release ?: latest(session)).source().asset(tag, name, session)
    }
}

/** 阻塞 IO 管理器。调用方在 IO 线程运行；安装目录、分卷和 profile 相互独立。 */
class BrowserKernelManager(val root: File, private val client: OkHttpClient) {
    /** Persist the trusted Release manifest separately from the native runtime and browser profile. */
    private fun remember(requirement: BrowserKernelRequirement) {
        val target = File(root, ".installed-${requirement.id}.json")
        atomicWrite(target, requirement.toJson())
    }
    fun newestInstalled(baseline: BrowserKernelRequirement): BrowserKernelRequirement? {
        val requirements = root.listFiles().orEmpty().filter { it.name.startsWith(".installed-") && it.name.endsWith(".json") && it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= 2 * 1024 * 1024 }
            .mapNotNull { file -> runCatching { BrowserKernelRequirement.read(file.readText()) }.getOrNull() }
            .filter { candidate -> compatible(baseline, candidate) }
        return (requirements + baseline).distinctBy { it.id }.sortedByDescending { BrowserKernelVersion.parse(it.manifest.componentVersion) }.firstOrNull { installed(it) != null }
    }
    /** Set only before this process creates its first native runtime. Child processes use the same pointer. */
    fun activate(requirement: BrowserKernelRequirement) {
        requirement.validate()
        require(File(root, "${requirement.id}/.ready").readText() == requirement.manifestSha256)
        val m = requirement.manifest
        atomicWrite(File(root, ".active-${m.engine}-${m.version}-v${m.format}-${m.platform}-${m.arch}"), requirement.id)
    }
    private fun atomicWrite(target: File, text: String) {
        root.mkdirs()
        val temporary = File.createTempFile(".kernel-metadata-", ".tmp", root)
        try {
            temporary.writeText(text)
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    /** Only import an exact, fully verified legacy runtime; never carry profile/cache files. */
    fun adoptLegacy(requirement: BrowserKernelRequirement, legacy: File): File? {
        requirement.validate()
        return locks.computeIfAbsent(File(root, requirement.id).canonicalPath) { ReentrantLock() }.withLock {
            root.mkdirs()
            FileChannel.open(File(root, ".${requirement.id}.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    installed(requirement)?.let { remember(requirement); return@withLock it }
                    if (!legacy.isDirectory || requirement.manifest.files.any { entry ->
                        val source = File(legacy, entry.path)
                        !source.isFile || Files.isSymbolicLink(source.toPath()) || source.length() != entry.size || hashFile(source) != entry.sha256
                    }) return@withLock null
                    val target = File(root, requirement.id)
                    val staging = File(root, ".${requirement.id}.staging")
                    deleteTree(staging)
                    staging.mkdirs()
                    try {
                        requirement.manifest.files.forEach { entry ->
                            val file = File(staging, entry.path).apply { parentFile.mkdirs() }
                            File(legacy, entry.path).copyTo(file)
                            if (entry.executable) file.setExecutable(true, true)
                        }
                        File(staging, ".ready").writeText(requirement.manifestSha256)
                        if (!verifyFiles(staging, requirement.manifest)) return@withLock null
                        remember(requirement)
                        deleteTree(target)
                        Files.move(staging.toPath(), target.toPath())
                        return@withLock target
                    } finally {
                        deleteTree(staging)
                    }
                }
            }
        }
    }

    fun installed(requirement: BrowserKernelRequirement): File? {
        requirement.validate()
        require(requirement.manifest.format == 1) { "Unsupported browser kernel format" }
        val directory = File(root, requirement.id)
        return directory.takeIf { runCatching { !Files.isSymbolicLink(File(it, ".ready").toPath()) && File(it, ".ready").takeIf(File::isFile)?.readText() == requirement.manifestSha256 && verifyFiles(it, requirement.manifest) }.getOrDefault(false) }
    }

    fun install(requirement: BrowserKernelRequirement, source: BrowserKernelSource,
        session: UpdateDownloadSession, onProgress: (BrowserKernelProgress) -> Unit = {}): File {
        requirement.validate()
        require(requirement.manifest.format == 1) { "Unsupported browser kernel format" }
        root.mkdirs()
        require(!Files.isSymbolicLink(root.toPath())) { "Invalid browser kernel root" }
        val lock = locks.computeIfAbsent(File(root, requirement.id).canonicalPath) { ReentrantLock() }
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) session.checkActive()
        try {
            FileChannel.open(File(root, ".${requirement.id}.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    session.checkActive()
                    onProgress(BrowserKernelProgress(BrowserKernelStage.CHECKING))
                    installed(requirement)?.let { remember(requirement); return it }
                    val bytes = Json.parseToJsonElement(fetch(source.asset(requirement.releaseTag, requirement.manifestName, session), session, 2 * 1024 * 1024).toString(Charsets.UTF_8)).toString().toByteArray()
                    require(digest(bytes) == requirement.manifestSha256) { "Kernel manifest checksum mismatch" }
                    require(Json.decodeFromString<BrowserKernelManifest>(bytes.toString(Charsets.UTF_8)) == requirement.manifest) { "Kernel manifest mismatch" }
                    val work = File(root, ".${requirement.id}.download").apply { mkdirs() }
                    val total = requirement.manifest.parts.sumOf { it.size }
                    var downloaded = 0L
                    val parts = requirement.manifest.parts.mapIndexed { index, part ->
                        session.checkActive()
                        val file = File(work, "part-$index")
                        if (file.isFile && file.length() == part.size && hashFile(file) == part.sha256) {
                            downloaded += part.size
                        } else {
                            if (file.length() >= part.size) file.delete()
                            downloadPart(source.asset(requirement.releaseTag, part.name, session), file, part, session) { current ->
                                onProgress(BrowserKernelProgress(BrowserKernelStage.DOWNLOADING, downloaded + current, total))
                            }
                            downloaded += part.size
                        }
                        file
                    }
                    onProgress(BrowserKernelProgress(BrowserKernelStage.VERIFYING, total, total))
                    val hash = MessageDigest.getInstance("SHA-256")
                    parts.forEach { file -> file.inputStream().buffered().use { input -> copy(input, null, hash, session) } }
                    if (hex(hash.digest()) != requirement.manifest.sha256) {
                        deleteTree(work); throw IOException("Kernel package checksum mismatch")
                    }
                    val staging = File(root, ".${requirement.id}.staging")
                    deleteTree(staging); staging.mkdirs()
                    try {
                        onProgress(BrowserKernelProgress(BrowserKernelStage.INSTALLING, total, total))
                        extract(parts, staging, requirement.manifest, session)
                        session.checkActive()
                        File(staging, ".ready").writeText(requirement.manifestSha256)
                        require(verifyFiles(staging, requirement.manifest)) { "Kernel installation verification failed" }
                        val target = File(root, requirement.id)
                        // Record first: interrupted commits are ignored unless the entire directory verifies.
                        // Once the directory is committed it can be discovered offline, even after a crash.
                        remember(requirement)
                        session.startInstallation {
                            deleteTree(target)
                            try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), target.toPath()) }
                        }
                        session.checkActive()
                        deleteTree(work)
                        onProgress(BrowserKernelProgress(BrowserKernelStage.READY, total, total))
                        return target
                    } finally { deleteTree(staging) }
                }
            }
        } finally { lock.unlock() }
    }

    private fun fetch(url: String, session: UpdateDownloadSession, limit: Int): ByteArray =
        session.execute(client.newCall(Request.Builder().url(url).build())) { response ->
            if (!response.isSuccessful) throw IOException("Kernel metadata returned HTTP ${response.code}")
            readLimited(response.body?.byteStream() ?: throw IOException("Empty kernel metadata"), limit).toByteArray()
        }

    private fun downloadPart(url: String, file: File, part: BrowserKernelPart, session: UpdateDownloadSession, progress: (Long) -> Unit) {
        var failure: IOException? = null
        var failures = 0
        while (failures < 3) {
            session.checkActive()
            try {
                val offset = file.length()
                val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                var continuation = false
                session.execute(client.newCall(request)) { response ->
                    if (!response.isSuccessful) throw IOException("Kernel download returned HTTP ${response.code}")
                    val append = offset > 0 && response.code == 206
                    var end = part.size - 1
                    if (response.code == 206) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        end = range?.groupValues?.get(2)?.toLongOrNull() ?: -1
                        if (range == null || range.groupValues[1].toLongOrNull() != offset || end !in offset until part.size || range.groupValues[3].toLongOrNull() != part.size) throw IOException("Invalid kernel Content-Range")
                    }
                    var count = if (append) offset else 0L
                    var lastProgress = 0L
                    response.body?.byteStream()?.use { input ->
                        java.io.FileOutputStream(file, append).buffered().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                session.checkActive()
                                val n = input.read(buffer); if (n < 0) break
                                if (count + n > end + 1) throw IOException("Kernel part exceeds expected range")
                                output.write(buffer, 0, n); count += n
                                val now = System.nanoTime()
                                if (now - lastProgress >= 100_000_000L || count == part.size) { lastProgress = now; progress(count) }
                            }
                        }
                    } ?: throw IOException("Empty kernel part")
                    if (count != end + 1) throw IOException("Incomplete kernel part")
                    continuation = count < part.size
                }
                if (continuation) continue // A valid 206 may cap its chunk; continue without consuming a retry.
                if (hashFile(file) != part.sha256) { file.delete(); throw IOException("Kernel part checksum mismatch") }
                return
            } catch (e: IOException) { session.checkActive(); failure = e; failures++ }
        }
        throw failure ?: IOException("Kernel download failed")
    }

    companion object {
        fun compatible(baseline: BrowserKernelRequirement, candidate: BrowserKernelRequirement): Boolean {
            val a = baseline.manifest; val b = candidate.manifest
            return a.engine == b.engine && a.version == b.version && a.format == b.format && a.platform == b.platform && a.arch == b.arch &&
                requireNotNull(BrowserKernelVersion.parse(b.componentVersion)) >= requireNotNull(BrowserKernelVersion.parse(a.componentVersion))
        }
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        private fun verifyFiles(root: File, manifest: BrowserKernelManifest): Boolean = runCatching {
            if (Files.isSymbolicLink(root.toPath())) return false
            val actual = mutableSetOf<String>()
            Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    require(!attrs.isSymbolicLink && actual.size <= manifest.files.size) { "Invalid kernel directory" }
                    actual += root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            if (actual != manifest.files.map { it.path }.toSet() + ".ready") return false
            manifest.files.all { entry ->
                val file = File(root, entry.path)
                !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() == entry.size && (!entry.executable || file.canExecute()) && hashFile(file) == entry.sha256
            }
        }.getOrDefault(false)

        private fun extract(parts: List<File>, root: File, manifest: BrowserKernelManifest, session: UpdateDownloadSession) {
            val expected = manifest.files.associateBy { it.path }
            val found = mutableSetOf<String>()
            ZipInputStream(SequenceInputStream(Collections.enumeration(parts.map { it.inputStream().buffered() }))).use { zip ->
                while (true) {
                    session.checkActive()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && safePath(entry.name) && found.add(entry.name)) { "Invalid kernel archive path" }
                    val specification = expected[entry.name] ?: throw IOException("Unexpected kernel file")
                    val file = File(root, entry.name)
                    require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                    file.parentFile.mkdirs()
                    val hash = MessageDigest.getInstance("SHA-256")
                    val size = file.outputStream().buffered().use { copy(zip, it, hash, session, specification.size) }
                    require(size == specification.size && hex(hash.digest()) == specification.sha256) { "Kernel file checksum mismatch" }
                    if (specification.executable) require(file.setExecutable(true, true)) { "Cannot set kernel executable permission" }
                    zip.closeEntry()
                }
            }
            require(found == expected.keys) { "Incomplete kernel archive" }
        }
        private fun deleteTree(file: File) {
            if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(file.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult { Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE }
                override fun postVisitDirectory(path: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult { if (error != null) throw error; Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE }
            })
        }
        private fun copy(input: java.io.InputStream, output: java.io.OutputStream?, hash: MessageDigest, session: UpdateDownloadSession, limit: Long = Long.MAX_VALUE): Long {
            val buffer = ByteArray(65536); var count = 0L
            while (true) {
                session.checkActive(); val n = input.read(buffer); if (n < 0) return count
                require(count + n <= limit) { "Kernel file exceeds expected size" }
                output?.write(buffer, 0, n); hash.update(buffer, 0, n); count += n
            }
        }
    }
}

private fun safeName(value: String) = value.isNotBlank() && value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } && value != "." && value != ".."
private fun safePath(value: String) = value.isNotBlank() && value.split('/').all(::safeName) && !value.startsWith('.')
private fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hashFile(file: File): String = file.inputStream().buffered().use { input ->
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }; hex(hash.digest())
}
private fun readLimited(input: java.io.InputStream, limit: Int): String = input.use {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = it.read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Kernel metadata too large" }; out.write(buffer, 0, n) }
    out.toString("UTF-8")
}
