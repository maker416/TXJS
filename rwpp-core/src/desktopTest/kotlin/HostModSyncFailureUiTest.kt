/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.*
import java.io.File
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class HostModSyncFailureUiTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        reloadI18n()
        setI18nOverride(null)
    }

    @Test
    fun failureRequiresAnExplicitChoiceAndBothChoicesRemainReachable() = runDesktopComposeUiTest(width = 340, height = 300) {
        var retries = 0
        var continues = 0
        var cancellations = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    HostModSyncFailureCard("无法连接服务器。".repeat(80), { retries++ }, { continues++ }, { cancellations++ })
                }
            }
        }
        assertEquals(0, retries + continues + cancellations)
        onNodeWithText(readI18n("modSync.hostPreflightFailedTitle")).assertIsDisplayed()
        onNodeWithText(readI18n("modSync.retrySharing")).assertIsDisplayed().performClick()
        assertEquals(1, retries)
        assertEquals(0, continues)
        onNodeWithText(readI18n("modSync.hostContinueWithoutSharing")).assertIsDisplayed().performClick()
        assertEquals(1, continues)
        onNodeWithContentDescription(readI18n("common.close")).assertIsDisplayed().performClick()
        assertEquals(1, cancellations)
    }
}
