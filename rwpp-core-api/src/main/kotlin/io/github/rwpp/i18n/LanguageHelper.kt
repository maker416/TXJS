/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.i18n

import io.github.rwpp.config.Settings
import java.util.Locale

/**
 * 统一解析启动器 [Settings.language] 与游戏 [Settings.forceEnglish]，
 * 避免「跟随系统 / 遗留 forceEnglish」两端各判各的导致中英混杂。
 */
object LanguageHelper {
    /** 解析后的有效语言：仅 "zh" 或 "en"。 */
    fun resolveEffectiveLanguage(settings: Settings): String {
        return when (settings.language) {
            "zh" -> "zh"
            "en" -> "en"
            "auto" -> detectSystemLanguage()
            else -> if (settings.forceEnglish) "en" else "zh"
        }
    }

    /** 游戏侧是否应强制英文（与 [resolveEffectiveLanguage] 对齐）。 */
    fun resolveForceEnglish(settings: Settings): Boolean =
        resolveEffectiveLanguage(settings) == "en"

    /** 将 [Settings.forceEnglish] 写成与 language 解析结果一致。 */
    fun applyToSettings(settings: Settings) {
        settings.forceEnglish = resolveForceEnglish(settings)
    }

    fun resolveBundleFile(settings: Settings): String {
        return when (resolveEffectiveLanguage(settings)) {
            "zh" -> "files/bundle_zh.toml"
            else -> "files/bundle_en.toml"
        }
    }

    fun detectSystemLanguage(): String {
        val locale = Locale.getDefault()
        if (locale.language == "zh") return "zh"
        val country = locale.country
        if (country == "CN" || country == "TW" || country == "HK" || country == "SG") return "zh"
        val sysLang = runCatching { System.getProperty("user.language") }.getOrNull().orEmpty()
        if (sysLang == "zh") return "zh"
        return "en"
    }
}
