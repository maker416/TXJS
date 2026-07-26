/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.i18n

import io.github.rwpp.core.Initialization
import org.koin.core.component.KoinComponent

interface GameI18nResolver : Initialization, KoinComponent {
    /**
     * Read RW translations (not RWPP)
     */
    fun i18n(str: String, vararg args: Any?): String

    /**
     * 按 [io.github.rwpp.config.Settings.language] 对齐 [io.github.rwpp.config.Settings.forceEnglish]，
     * 并在游戏引擎可用时写入 SettingsEngine.forceEnglish。
     */
    fun syncGameLanguage()

    /**
     * 按当前语言重新加载 RWPP bundle（清空缓存后再次 [init]）。
     */
    fun reloadBundle()

    /**
     * 刷新游戏引擎翻译缓存（如 `h.a.c()`）。引擎未就绪时忽略。
     */
    fun refreshGameTranslations()
}
