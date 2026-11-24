/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.expansion.*
import io.github.rwpp.theme.personalizedThemeCatalog
import io.github.rwpp.theme.validateThemeToml
import org.junit.Assume
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ExpansionPackageInstallerTest {
    private val work = Files.createTempDirectory("rwjs-expansion-installer-test-").toFile()
    private val appId = "11111111-1111-1111-1111-111111111111"
    private val packId = "22222222-2222-2222-2222-222222222222"
    @AfterTest fun cleanup() { work.deleteRecursively() }

    @Test fun importsOneImmutableRevisionAndKeepsItOutsideOrdinaryMods() {
        val archive = build()
        val ordinary = File(work, "ordinary-mods/personal.rwmod").apply { parentFile.mkdirs(); writeText("untouched") }
        val root = File(work, "expansions")
        val installed = ExpansionPackageInstaller.install(archive, root, appId)
        assertEquals("1.0.0", installed.version)
        assertEquals(File(root, "$appId/${installed.sha256}").canonicalFile, installed.dir.canonicalFile)
        assertEquals("theme", installed.themeDir.name)
        assertEquals("untouched", ordinary.readText())
        assertEquals(listOf(installed.binding), ExpansionPackageInstaller.scan(root).map { it.binding })
        ExpansionPackageInstaller.verify(installed)
        assertEquals(installed.dir, ExpansionPackageInstaller.install(archive, root, appId, installed.binding).dir)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("pending-") })
    }

    @Test fun rejectsWrongGameHashSizeAndArtBindingBeforeActivation() {
        val archive = build()
        val installed = ExpansionPackageInstaller.install(archive, File(work, "initial"))
        val wrong = listOf(installed.binding.copy(sha256 = "0".repeat(64)), installed.binding.copy(size = installed.size + 1),
            installed.binding.copy(artBinding = installed.binding.artBinding.copy(catalogHash = "0".repeat(64))))
        wrong.forEachIndexed { index, binding ->
            assertFailsWith<IOException> { ExpansionPackageInstaller.install(archive, File(work, "wrong-$index"), appId, binding) }
        }
        assertFailsWith<IOException> { ExpansionPackageInstaller.install(archive, File(work, "wrong-game"), packId) }
    }

    @Test fun refusesSameGameVersionWithDifferentBytesAndRetainsOriginal() {
        val root = File(work, "expansions")
        val original = ExpansionPackageInstaller.install(build(), root)
        assertFailsWith<IOException> { ExpansionPackageInstaller.install(build("changed-mod"), root) }
        assertEquals(listOf(original.sha256), ExpansionPackageInstaller.scan(root).map { it.sha256 })
        ExpansionPackageInstaller.verify(original)
    }

    @Test fun cachedNonPersonalizedArtIsAlsoAuthenticatedByTheOriginalArchive() {
        val installed = ExpansionPackageInstaller.install(build(), File(work, "expansions"))
        File(installed.themeDir, "default.png").writeText("tampered")
        assertFailsWith<IOException> { ExpansionPackageInstaller.verify(installed) }
    }

    @Test fun reusesPersistedActivationVerificationAfterRescanAndRebuildsAnInvalidRecord() {
        val root = File(work, "expansions")
        val installed = ExpansionPackageInstaller.install(build(), root)
        val changeToken: (File) -> String = { "stable" }
        assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
        val record = File(installed.dir.parentFile, ".${installed.sha256}.verified-v1")
        assertEquals(32L, record.length())
        val rescanned = ExpansionPackageInstaller.scan(root).single()
        assertTrue(ExpansionPackageInstaller.verifyForActivation(rescanned, changeToken))
        for (invalid in listOf(byteArrayOf(1), ByteArray(32))) {
            record.writeBytes(invalid)
            assertFalse(ExpansionPackageInstaller.verifyForActivation(rescanned, changeToken))
            assertTrue(ExpansionPackageInstaller.verifyForActivation(rescanned, changeToken))
        }
        Files.delete(record.toPath())
        assertFalse(ExpansionPackageInstaller.verifyForActivation(rescanned, changeToken))
    }

    @Test fun changedFileTokenRechecksSameSizeRewritesWithRestoredModificationTime() {
        val archive = build()
        for (path in listOf("content.zip", "mods/sample.rwmod", "theme/default.png")) {
            val root = File(work, "expansions-${File(path).name}")
            val installed = ExpansionPackageInstaller.install(archive, root)
            val changed = installed.dir.resolve(path)
            var generation = 0
            val changeToken: (File) -> String = { if (it == changed) generation.toString() else "stable" }
            assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
            assertTrue(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
            generation++
            assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
            assertTrue(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))

            val modified = Files.getLastModifiedTime(changed.toPath())
            val bytes = changed.readBytes()
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            changed.writeBytes(bytes)
            Files.setLastModifiedTime(changed.toPath(), modified)
            generation++
            repeat(2) {
                assertFailsWith<IOException>(path) { ExpansionPackageInstaller.verifyForActivation(installed, changeToken) }
            }
        }
    }

    @Test fun activationRechecksAddedAndMissingFilesWithStableTokens() {
        val installed = ExpansionPackageInstaller.install(build(), File(work, "expansions"))
        val changeToken: (File) -> String = { "stable" }
        assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
        val extra = installed.themeDir.resolve("unexpected.png")
        extra.writeText("extra")
        assertFailsWith<IOException> { ExpansionPackageInstaller.verifyForActivation(installed, changeToken) }
        Files.delete(extra.toPath())
        Files.delete(installed.themeDir.resolve("default.png").toPath())
        assertFailsWith<IOException> { ExpansionPackageInstaller.verifyForActivation(installed, changeToken) }
    }

    @Test fun unavailableChangeTokenRequiresDeepVerificationEveryTime() {
        val installed = ExpansionPackageInstaller.install(build(), File(work, "expansions"))
        val changeToken: (File) -> String? = { if (it.name == "content.zip") null else "stable" }
        repeat(2) { assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken)) }
        installed.themeDir.resolve("default.png").writeText("damaged")
        assertFailsWith<IOException> { ExpansionPackageInstaller.verifyForActivation(installed, changeToken) }
    }

    @Test fun cancelledActivationVerificationLeavesNoReusableRecord() {
        val installed = ExpansionPackageInstaller.install(build(), File(work, "expansions"))
        val changeToken: (File) -> String = { "stable" }
        var checks = 0
        assertFailsWith<InterruptedException> {
            ExpansionPackageInstaller.verifyForActivation(installed, changeToken) {
                if (++checks == 3) throw InterruptedException("cancel")
            }
        }
        assertFalse(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
        assertTrue(ExpansionPackageInstaller.verifyForActivation(installed, changeToken))
    }

    @Test fun installsBaseThemeWithoutPersonalizedVariants() {
        val installed = ExpansionPackageInstaller.install(build(withVariants = false), File(work, "base-theme"))
        val spec = validateThemeToml(File(installed.themeDir, "theme.toml").readText()).first!!
        assertNull(personalizedThemeCatalog(installed.themeDir, spec.personalization))
        ExpansionPackageInstaller.verify(installed)
    }

    @Test fun rejectsEscapingDuplicateAndUndeclaredPathsWithoutPartialInstallation() {
        for ((index, extra) in listOf("../outside.txt", "theme/DEFAULT.PNG", "plugins/evil.jar").withIndex()) {
            val root = File(work, "unsafe-$index")
            assertFailsWith<IOException> { ExpansionPackageInstaller.install(build(extra = extra), root) }
            assertTrue(ExpansionPackageInstaller.scan(root).isEmpty())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("pending-") })
        }
        assertFalse(File(work, "outside.txt").exists())
    }

    @Test fun rejectsTreeDigestMismatchEvenWhenTheOuterArchiveIsValid() {
        val archive = build(treeOverride = "0".repeat(64))
        assertFailsWith<IOException> { ExpansionPackageInstaller.install(archive, File(work, "bad-tree")) }
    }

    @Test fun cancellationDoesNotPublishAPartialPackage() {
        var checks = 0
        val archive = build()
        val root = File(work, "cancel")
        assertFailsWith<InterruptedException> {
            ExpansionPackageInstaller.install(archive, root) { if (++checks > 5) throw InterruptedException("cancel") }
        }
        assertTrue(ExpansionPackageInstaller.scan(root).isEmpty())
    }

    /** Run explicitly with the independent server CLI fixture to verify the cross-language contract. */
    @Test fun independentServerToolArchiveInteroperates() {
        val path = System.getenv("RWJS_EXPANSION_CONTRACT_FIXTURE")
        Assume.assumeTrue("Independent server-tool fixture was not requested", path != null)
        val installed = ExpansionPackageInstaller.install(File(requireNotNull(path)), File(work, "server-tool"))
        assertTrue(installed.binding.valid())
        ExpansionPackageInstaller.verify(installed)
    }

    private fun build(content: String = "fixture", extra: String? = null, treeOverride: String? = null, withVariants: Boolean = true): File {
        val source = Files.createTempDirectory(work.toPath(), "source-").toFile()
        val mod = Files.createTempDirectory(work.toPath(), "mod-").toFile()
        File(mod, "mod-info.txt").writeText("[mod]\ntitle: $content\n")
        File(mod, "nested/数据.txt").apply { parentFile.mkdirs(); writeText("portable source") }
        val rwmod = File(source, "mods/sample.rwmod").apply { parentFile.mkdirs() }
        zip(mod, rwmod)
        val theme = File(source, "theme").apply { mkdirs() }
        File(theme, "default.png").writeText("default asset")
        val manifest = File(theme, "theme.toml")
        val baseTheme = """
            [theme]
            id="expansion-fixture"
            name="Fixture"
            version="1.0.0"
            [personalization]
            platformPackId="$packId"
        """.trimIndent()
        val variant = """
            [personalization.slots."room.player.frame".variants.gold]
            borderColor="#FFD700"
        """.trimIndent()
        manifest.writeText(if (withVariants) "$baseTheme\n$variant" else baseTheme)
        val spec = validateThemeToml(manifest.readText()).first!!
        val catalogHash = if (withVariants) personalizedThemeCatalog(theme, spec.personalization)!!.catalogHash else {
            val canonical = "RWJS-ART-CATALOG-1\n${ExpansionPackageInstaller.hashFile(manifest)}\n"
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        File(source, "version.txt").writeText("1.0.0\n")
        File(source, "expansion.toml").writeText("""
            schema_version=1
            game_app_id="$appId"
            name="Fixture game"
            [[mods]]
            id="sample"
            path="mods/sample.rwmod"
            size=${rwmod.length()}
            sha256="${ExpansionPackageInstaller.hashFile(rwmod)}"
            tree_sha256="${treeOverride ?: ExpansionPackageInstaller.portableTreeSha256(mod)}"
            [theme]
            path="theme"
            pack_id="$packId"
            version="1.0.0"
            catalog_hash="$catalogHash"
        """.trimIndent())
        return File(work, "archive-${System.nanoTime()}.zip").also { zip(source, it, extra) }
    }

    private fun zip(source: File, output: File, extra: String? = null) = ZipOutputStream(output.outputStream()).use { zip ->
        source.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath).apply { time = 0 })
            file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
        if (extra != null) { zip.putNextEntry(ZipEntry(extra)); zip.write(byteArrayOf(1)); zip.closeEntry() }
    }
}
