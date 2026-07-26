/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.impl

import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.i18n.LanguageHelper
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.rwpp_core.generated.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.peanuuutz.tomlkt.Toml
import org.koin.core.component.get

abstract class BaseGameI18nResolverImpl : GameI18nResolver {
    override fun init() {
        runBlocking {
            i18nTable = Toml.parseToTomlTable(withContext(Dispatchers.IO) {
                val settings = get<Settings>()
                LanguageHelper.applyToSettings(settings)
                val bundleFile = LanguageHelper.resolveBundleFile(settings)
                runCatching {
                    Res.readBytes(bundleFile)
                }.getOrNull() ?: Res.readBytes("files/bundle_en.toml")
            }.decodeToString().replace("\r", "\n"))
        }
    }

    override fun reloadBundle() {
        reloadI18n()
        init()
    }

    override fun syncGameLanguage() {
        val settings = get<Settings>()
        LanguageHelper.applyToSettings(settings)
        runCatching {
            get<ConfigIO>().setGameConfig("forceEnglish", settings.forceEnglish)
        }
        refreshGameTranslations()
    }
}
