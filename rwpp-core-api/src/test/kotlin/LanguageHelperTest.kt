/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp

import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.LanguageHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageHelperTest {
    @Test
    fun resolveEffectiveLanguageHonorsExplicitZhAndEn() {
        assertEquals("zh", LanguageHelper.resolveEffectiveLanguage(Settings(language = "zh", forceEnglish = true)))
        assertEquals("en", LanguageHelper.resolveEffectiveLanguage(Settings(language = "en", forceEnglish = false)))
    }

    @Test
    fun applyToSettingsAlignsForceEnglishWithLanguage() {
        val zh = Settings(language = "zh", forceEnglish = true)
        LanguageHelper.applyToSettings(zh)
        assertFalse(zh.forceEnglish)

        val en = Settings(language = "en", forceEnglish = false)
        LanguageHelper.applyToSettings(en)
        assertTrue(en.forceEnglish)
    }

    @Test
    fun migratePromotesLegacyForceEnglishToLanguageEn() {
        val settings = Settings(language = "zh", forceEnglish = true, configVersion = 1)
        settings.migrate()
        assertEquals("en", settings.language)
        assertTrue(settings.forceEnglish)
        assertEquals(2, settings.configVersion)
    }

    @Test
    fun resolveBundleFileMatchesEffectiveLanguage() {
        assertEquals("files/bundle_zh.toml", LanguageHelper.resolveBundleFile(Settings(language = "zh")))
        assertEquals("files/bundle_en.toml", LanguageHelper.resolveBundleFile(Settings(language = "en")))
    }
}
