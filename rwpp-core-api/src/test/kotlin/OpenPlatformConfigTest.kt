/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.rwpp_core_api.BuildConfig
import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenPlatformConfigTest {
    @Test
    fun acceptsHttpsAndLoopbackDevelopmentUrls() {
        assertEquals("https://open.example.com/apply", normalizeOpenPlatformUrl(" https://open.example.com/apply/ "))
        assertEquals("http://127.0.0.1:8092", normalizeOpenPlatformUrl("http://127.0.0.1:8092/"))
        assertEquals("http://localhost:8092", normalizeOpenPlatformUrl("http://localhost:8092"))
        assertEquals("http://[::1]:8092", normalizeOpenPlatformUrl("http://[::1]:8092"))
    }

    @Test
    fun rejectsUnsafeOrCredentialBearingUrls() {
        listOf("http://open.example.com", "http://localhost.example.com", "javascript:alert(1)",
            "https://user:password@open.example.com", "https://open.example.com?token=x",
            "https://open.example.com#token=x", "").forEach { assertNull(normalizeOpenPlatformUrl(it), it) }
    }

    @Test
    fun runtimeOverrideUsesValidatedAddressAndFallsBackSafely() {
        val before = System.getProperty("rwjs.openPlatform.url")
        try {
            System.setProperty("rwjs.openPlatform.url", "https://open.example.com/apply/")
            assertEquals("https://open.example.com/apply", resolveOpenPlatformUrl())
            System.setProperty("rwjs.openPlatform.url", "http://open.example.com")
            assertEquals(BuildConfig.OPEN_PLATFORM_URL, resolveOpenPlatformUrl())
        } finally {
            if (before == null) System.clearProperty("rwjs.openPlatform.url") else System.setProperty("rwjs.openPlatform.url", before)
        }
    }

    @Test
    fun addressAppliesToTheCurrentRunAndClearingItRestoresTheDefault() {
        val property = "rwjs.openPlatform.url"
        val before = System.getProperty(property)
        try {
            System.clearProperty(property)
            val environment = System.getenv("RWJS_OPEN_PLATFORM_URL")
            val environmentUrl = environment?.let { normalizeOpenPlatformUrl(it) ?: BuildConfig.OPEN_PLATFORM_URL }
            val settings = Settings(openPlatformApiUrl = "https://stage.example.com/platform/")
            val persisted = Toml.encodeToString(Settings.serializer(), settings)
            assertEquals("", decodeSavedConfig(Settings::class, persisted).openPlatformApiUrl)
            setOpenPlatformUrlForSession(settings.openPlatformApiUrl)
            assertEquals(environmentUrl ?: "https://stage.example.com/platform", resolveOpenPlatformUrl())

            System.setProperty(property, "http://127.0.0.1:8092")
            assertEquals("http://127.0.0.1:8092", resolveOpenPlatformUrl())
            System.clearProperty(property)

            setOpenPlatformUrlForSession("http://stage.example.com")
            assertEquals(environmentUrl ?: BuildConfig.OPEN_PLATFORM_URL, resolveOpenPlatformUrl())

            val oldSettings = decodeSavedConfig(Settings::class, """
                language = "en"
                openPlatformApiUrl = "https://stage.example.com"
            """.trimIndent())
            assertEquals("en", oldSettings.language)
            assertEquals("", oldSettings.openPlatformApiUrl)
            setOpenPlatformUrlForSession(oldSettings.openPlatformApiUrl)
            assertEquals(environmentUrl ?: BuildConfig.OPEN_PLATFORM_URL, resolveOpenPlatformUrl())
        } finally {
            setOpenPlatformUrlForSession("")
            if (before == null) System.clearProperty(property) else System.setProperty(property, before)
        }
    }
}
