/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.theme.OnlineGameThemePackage
import io.github.rwpp.theme.personalizedThemeCatalog
import io.github.rwpp.theme.validateThemeToml
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineGameThemePackageTest {
    private val work = Files.createTempDirectory("rwjs-game-package-test-").toFile()
    private val packId = "01234567-89ab-cdef-0123-456789abcdef"
    private val manifest = """
        [theme]
        id = "same-local-id"
        name = "Online test"
        version = "1.0.0"
        [personalization]
        schemaVersion = 1
        platformPackId = "$packId"
        [personalization.slots."room.player.background".variants.gold]
        image = "gold.png"
    """.trimIndent()

    @AfterTest fun cleanup() { work.deleteRecursively() }

    @Test fun verifiesArchiveAndCatalogWithoutReplacingAnInstalledTheme() {
        MockWebServer().use { server ->
            val files = mapOf("theme.toml" to manifest.toByteArray(), "gold.png" to byteArrayOf(1, 2, 3))
            val archive = zip(files)
            server.enqueue(MockResponse().setBody(Buffer().write(archive)))
            server.start()
            val cache = File(work, "cache")
            val original = File(work, "themes/same-local-id/theme.toml").apply { parentFile.mkdirs(); writeText("original") }
            val binding = binding(server, files, archive)
            val installed = OnlineGameThemePackage().install(binding, cache)
            assertEquals(File(cache, "$packId/${binding.catalogHash}").canonicalFile, installed.canonicalFile)
            assertTrue(OnlineGameThemePackage.matches(binding, installed, validateThemeToml(File(installed, "theme.toml").readText()).first!!))
            assertEquals("original", original.readText())
            assertFalse(cache.listFiles()!!.any { it.name.startsWith("pending-") })
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-App-Key"))
            assertNull(request.getHeader("X-Identity-Secret"))
        }
    }

    @Test fun rejectsChecksumMismatchAndRemovesOnlyItsOwnStagingFiles() {
        MockWebServer().use { server ->
            val files = mapOf("theme.toml" to manifest.toByteArray(), "gold.png" to byteArrayOf(1))
            val archive = zip(files)
            server.enqueue(MockResponse().setBody(Buffer().write(archive)))
            server.start()
            val cache = File(work, "cache").apply { mkdirs() }
            val keep = File(cache, "keep.txt").apply { writeText("preserve") }
            assertFailsWith<IOException> { OnlineGameThemePackage().install(binding(server, files, archive).copy(archiveSha256 = "0".repeat(64)), cache) }
            assertEquals("preserve", keep.readText())
            assertEquals(listOf("keep.txt"), cache.list()!!.toList())
        }
    }

    @Test fun archiveDigestCannotSubstituteForTheApprovedCatalogOrVersion() {
        MockWebServer().use { server ->
            val files = mapOf("theme.toml" to manifest.toByteArray(), "gold.png" to byteArrayOf(1))
            val archive = zip(files)
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(archive))) }
            server.start()
            val binding = binding(server, files, archive)
            assertFailsWith<IOException> { OnlineGameThemePackage().install(binding.copy(catalogHash = "0".repeat(64)), File(work, "catalog")) }
            assertFailsWith<IOException> { OnlineGameThemePackage().install(binding.copy(version = "2.0"), File(work, "version")) }
        }
    }

    @Test fun rejectsTraversalCaseCollisionsAndOversizedCompressedEntries() {
        MockWebServer().use { server ->
            val base = mapOf("theme.toml" to manifest.toByteArray(), "gold.png" to byteArrayOf(1))
            val invalids = listOf(
                base + ("../escape.txt" to byteArrayOf(1)),
                base + ("GOLD.PNG" to byteArrayOf(1)),
                base + ("big.png" to ByteArray(32 * 1024 * 1024 + 1)),
            )
            invalids.forEach { server.enqueue(MockResponse().setBody(Buffer().write(zip(it)))) }
            server.start()
            val approved = binding(server, base, zip(base))
            invalids.forEachIndexed { index, files ->
                assertFailsWith<IOException> { OnlineGameThemePackage().install(approved.copy(archiveSha256 = hash(zip(files))), File(work, "bad-$index")) }
            }
            assertFalse(File(work, "escape.txt").exists())
        }
    }

    @Test fun rejectsRedirectsAndCredentialBearingUrls() {
        MockWebServer().use { server ->
            val files = mapOf("theme.toml" to manifest.toByteArray(), "gold.png" to byteArrayOf(1))
            val archive = zip(files)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/unexpected"))
            server.start()
            val binding = binding(server, files, archive)
            assertFailsWith<IOException> { OnlineGameThemePackage().install(binding, File(work, "redirect")) }
            assertEquals(1, server.requestCount)
            assertFailsWith<IllegalArgumentException> { OnlineGameThemePackage().install(binding.copy(downloadUrl = "https://secret@example.com/theme"), File(work, "credentials")) }
        }
    }

    private fun binding(server: MockWebServer, files: Map<String, ByteArray>, archive: ByteArray): OnlineGameArtBinding {
        val source = Files.createTempDirectory(work.toPath(), "source-").toFile()
        files.forEach { (path, bytes) -> File(source, path).apply { parentFile.mkdirs(); writeBytes(bytes) } }
        val spec = validateThemeToml(manifest).first!!
        return OnlineGameArtBinding(packId, "1.0.0", personalizedThemeCatalog(source, spec.personalization)!!.catalogHash,
            "http://127.0.0.1:${server.port}/theme.rwtheme", hash(archive))
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0 })
            zip.write(content)
            zip.closeEntry()
        } }
    }.toByteArray()

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
