/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.PublicUser
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalizedThemeSpecTest {
    private val packId = "01234567-89ab-cdef-0123-456789abcdef"
    private val manifest = "[theme]\nid = \"vector\"\nname = \"Vector\"\n"

    private fun spec(vararg images: String) = PersonalizedThemeSpec(platformPackId = packId,
        slots = mapOf("room.player.background" to PersonalizedSlotSpec(images.mapIndexed { index, path ->
            "variant$index" to PersonalizedVariantSpec(image = path)
        }.toMap())))

    @Test
    fun quotedSemanticSlotsParseAndOldPackagesHaveNoPersonalization() {
        assertEquals(PersonalizedThemeSpec(), validateThemeToml(manifest).first?.personalization)
        val (theme, validation) = validateThemeToml(manifest + """
            [personalization]
            schemaVersion = 1
            platformPackId = "$packId"
            [personalization.slots."room.player.background".variants.supporter]
            image = "art/supporter.png"
            [personalization.slots."room.player.frame".variants.gold]
            image = "old/unused.png"
            avatarFrameImage = "art/frame.png"
            displayName = " 棱镜之冠 "
            borderColor = "#aabbcc"
            borderWidth = 20
        """.trimIndent())
        assertTrue(validation.isValid, validation.errors.toString())
        val personalization = assertNotNull(theme).personalization
        assertEquals(setOf("art/supporter.png", "art/frame.png"), personalization.referencedAssetPaths())
        assertEquals("棱镜之冠", personalization.slots.getValue("room.player.frame").variants.getValue("gold").displayName)
        assertEquals("#AABBCC", personalization.slots.getValue("room.player.frame").variants.getValue("gold").borderColor)
        assertEquals(6, personalization.slots.getValue("room.player.frame").variants.getValue("gold").borderWidth)
        assertTrue(validation.warnings.any { it.contains("描边") })
    }

    @Test
    fun futureSchemaAndUnknownSlotShapesCannotBreakTheDefaultTheme() {
        val (future, futureValidation) = validateThemeToml(manifest + """
            [personalization]
            schemaVersion = 99
            slots = ["an incompatible future format"]
        """.trimIndent())
        assertNotNull(future)
        assertEquals(PersonalizedThemeSpec(), future.personalization)
        assertTrue(futureValidation.warnings.any { it.contains("schemaVersion") })
        val (unknown, unknownValidation) = validateThemeToml(manifest + """
            [personalization]
            platformPackId = "$packId"
            [personalization.slots]
            "future.slot" = "new format"
        """.trimIndent())
        assertNotNull(unknown)
        assertTrue(unknown.personalization.slots.isEmpty())
        assertTrue(unknownValidation.warnings.any { it.contains("future.slot") })
    }

    @Test
    fun malformedIdentityAndUnsafeAssetsAreRejected() {
        assertFalse(validatePersonalizedTheme(spec("art/a.png").copy(platformPackId = "1-1-1-1-1")).isValid)
        listOf("../escape.png", "https://host/a.png", "C:/a.png", "a\\b.png", "a//b.png", "/a.png", "a/./b.png", "a/ b.png", "art/CON.png", "art/a?.png", "art/a.", "a".repeat(257)).forEach { path ->
            assertFalse(validatePersonalizedTheme(spec(path)).isValid, path)
            assertEquals("", spec(path).sanitized().slots.getValue("room.player.background").variants.getValue("variant0").image)
        }
        val maliciousCell = PersonalizedVariantSpec(content = RoomPlayerCardContentSpec(rows = listOf(
            RoomPlayerInfoRowSpec(listOf(RoomPlayerInfoCellSpec(iconImage = "../outside.png"))),
        )))
        assertFalse(validatePersonalizedTheme(PersonalizedThemeSpec(platformPackId = packId,
            slots = mapOf("room.player.info" to PersonalizedSlotSpec(mapOf("entry" to maliciousCell))))).isValid)
    }

    @Test
    fun typedSlotsBoundRowsCellsTextAndVariantCounts() {
        val content = RoomPlayerCardContentSpec(avatarImage = "art/avatar.png", rows = List(15) {
            RoomPlayerInfoRowSpec(List(15) {
                RoomPlayerInfoCellSpec(text = "x".repeat(200) + "\n", contentColor = "not a color", weight = Float.NaN)
            })
        })
        val input = PersonalizedThemeSpec(platformPackId = packId.uppercase(), slots = mapOf(
            "room.player.info" to PersonalizedSlotSpec((0..70).associate { "v$it" to PersonalizedVariantSpec(image = "unused.png", content = content) } +
                ("bad id" to PersonalizedVariantSpec())),
            "room.player.frame" to PersonalizedSlotSpec(mapOf("invalid" to PersonalizedVariantSpec(borderColor = "red"))),
            "unsupported" to PersonalizedSlotSpec(),
        ))
        val validation = validatePersonalizedTheme(input)
        assertTrue(validation.isValid)
        assertTrue(validation.warnings.any { it.contains("超过") })
        assertTrue(validation.warnings.any { it.contains("色值") })
        val safe = input.sanitized()
        assertEquals(packId, safe.platformPackId)
        assertFalse("unsupported" in safe.slots)
        val variants = safe.slots.getValue("room.player.info").variants
        assertEquals(64, variants.size)
        assertFalse("bad id" in variants)
        val variant = variants.getValue("v0")
        assertEquals("", variant.image)
        assertEquals("", variant.content.avatarImage)
        assertEquals(12, variant.content.rows.size)
        assertEquals(12, variant.content.rows.first().cells.size)
        assertEquals(160, variant.content.rows.first().cells.first().text.length)
        assertEquals("", variant.content.rows.first().cells.first().contentColor)
        assertEquals(1f, variant.content.rows.first().cells.first().weight)
        assertEquals("", safe.slots.getValue("room.player.frame").variants.getValue("invalid").borderColor)
    }

    @Test
    fun catalogMatchesIndependentPythonSha256VectorAndDetectsResourceOrManifestChanges() = withDirectory { root ->
        File(root, "theme.toml").writeBytes(manifest.toByteArray(Charsets.UTF_8))
        File(root, "art").mkdirs()
        File(root, "art/a.png").writeBytes("abc".toByteArray())
        File(root, "art/z.png").writeBytes(byteArrayOf(0, 1, -1))
        val definition = spec("art/z.png", "art/a.png")
        val catalog = assertNotNull(personalizedThemeCatalog(root, definition))
        // Generated independently with Python hashlib using the documented UTF-8/LF wire contract.
        assertEquals("7787bfbdd4828bbb1c93934cd935e59014b10ae325d0c6324cf15addf2627bcb", catalog.manifestSha256)
        assertEquals("c59311af855d696b075766dab184f391130ee4df373713c6aa4335df8f47458b", catalog.catalogHash)
        assertEquals(listOf("art/a.png", "art/z.png"), catalog.assets.keys.toList())
        assertEquals(listOf("variant0", "variant1"), catalog.variants.map { it.variantId })
        File(root, "art/a.png").writeText("changed")
        assertTrue(personalizedThemeCatalog(root, definition)?.catalogHash != catalog.catalogHash)
        File(root, "theme.toml").appendText("# manifest revision\n")
        assertTrue(personalizedThemeCatalog(root, definition)?.manifestSha256 != catalog.manifestSha256)
    }

    @Test
    fun missingOversizedAndAggregateOversizedResourcesDisablePersonalization() = withDirectory { root ->
        File(root, "theme.toml").writeText(manifest)
        assertNull(personalizedThemeCatalog(root, spec("missing.png")))
        val oversized = File(root, "large.png")
        RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        assertNull(personalizedThemeCatalog(root, spec("large.png")))
        val paths = (1..5).map { "$it.png" }
        paths.forEach { path -> RandomAccessFile(File(root, path), "rw").use { it.setLength(27L * 1024 * 1024) } }
        assertNull(personalizedThemeCatalog(root, spec(*paths.toTypedArray())))
    }

    @Test
    fun catalogUsesUtf8ByteOrderForSupplementaryUnicodePaths() = withDirectory { root ->
        File(root, "theme.toml").writeBytes(manifest.toByteArray(Charsets.UTF_8))
        File(root, "art").mkdirs()
        val bmp = "art/\uE000.png"
        val supplementary = "art/\uD800\uDC00.png"
        File(root, bmp).writeText("x", Charsets.UTF_8)
        File(root, supplementary).writeText("y", Charsets.UTF_8)
        val catalog = assertNotNull(personalizedThemeCatalog(root, spec(supplementary, bmp)))
        assertEquals(listOf(bmp, supplementary), catalog.assets.keys.toList())
        assertEquals("6c66b478ac75aba570df5baad31ae2eec9dd97db1607683f5a3be18e778a4e17", catalog.catalogHash)
    }

    @Test
    fun caseConflictingPathsAndInvalidPlatformVariantIdsCannotBePublished() = withDirectory { root ->
        File(root, "theme.toml").writeText(manifest)
        File(root, "art").mkdirs()
        File(root, "art/a.png").writeText("image")
        assertNull(personalizedThemeCatalog(root, spec("art/a.png", "ART/A.PNG")))
        val invalid = PersonalizedThemeSpec(platformPackId = packId, slots = mapOf(
            "room.player.background" to PersonalizedSlotSpec(mapOf(
                "1wrong" to PersonalizedVariantSpec(), "Upper" to PersonalizedVariantSpec(),
            )),
        ))
        assertTrue(invalid.sanitized().slots.values.all { it.variants.isEmpty() })
        assertTrue(validatePersonalizedTheme(invalid).warnings.any { it.contains("variant id") })
    }

    @Test
    fun symlinkOutsideTheInstalledPackageCannotEnterTheCatalog() = withDirectory { root ->
        File(root, "theme.toml").writeText(manifest)
        val outside = File(root.parentFile, "outside-${root.name}.png").apply { writeText("secret") }
        try {
            val created = runCatching { Files.createSymbolicLink(File(root, "link.png").toPath(), outside.toPath()) }.isSuccess
            org.junit.Assume.assumeTrue("Creating symlinks requires OS support and permission", created)
            assertNull(personalizedThemeCatalog(root, spec("link.png")))
        } finally { outside.delete() }
    }

    @Test
    fun installerRetainsDefaultThemeWhenPersonalizedResourceIsMissing() = withDirectory { root ->
        val zip = File(root, "test.rwtheme")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("theme.toml"))
            it.write((manifest + """
                [personalization]
                platformPackId = "$packId"
                [personalization.slots."room.player.background".variants.supporter]
                image = "missing.png"
            """.trimIndent()).toByteArray(Charsets.UTF_8))
            it.closeEntry()
        }
        val result = ThemePackInstaller.install(zip, File(root, "themes"))
        assertTrue(result.success)
        assertNull(result.catalog)
        assertTrue(result.warnings.any { it.contains("个性化资源") })
        assertTrue(File(root, "themes/vector/theme.toml").isFile)
    }

    @Test
    fun accountIdsRemainOptionalAndSeparateFromApplicationMemberIds() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AccountUser.serializer(), """{"id":7,"username":"a","nickname":"A"}""")
        assertNull(old.accountId)
        val user = json.decodeFromString(PublicUser.serializer(), """{"id":7,"account_id":"101","username":"a","nickname":"A"}""")
        assertEquals(7, user.id)
        assertEquals("101", user.accountId)
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rwjs-personalization-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
