/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.localized
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ConnectionFailureContentTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        reloadI18n()
        setI18nOverride(null)
        setLanguage("en")
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        reloadI18n()
        setI18nOverride(null)
    }

    @Test
    fun detailsAreOptInAndCanBeCollapsed() = runDesktopComposeUiTest {
        val failure = ConnectionFailure(ConnectionFailureReason.REFUSED, details = "java.net.ConnectException: raw diagnostic")
        setContent { KoinContext(appKoin) { MaterialTheme { ConnectionFailureCard(failure) {} } } }
        onNodeWithText(failure.localized().title).assertIsDisplayed()
        onNodeWithText(failure.localized().explanation).assertIsDisplayed()
        onNodeWithText(failure.localized().suggestion).assertIsDisplayed()
        onNodeWithText(failure.details).assertDoesNotExist()
        onNodeWithText(readI18n("connectionError.showDetails")).performClick()
        onNodeWithText(failure.details).assertIsDisplayed()
        onNodeWithText(readI18n("connectionError.hideDetails")).performClick()
        onNodeWithText(failure.details).assertDoesNotExist()
    }

    @Test
    fun absentDetailsHaveNoExpanderAndChineseContentIsLocalized() = runDesktopComposeUiTest {
        setLanguage("zh")
        val failure = ConnectionFailure(ConnectionFailureReason.TIMEOUT)
        var dismissals = 0
        setContent { KoinContext(appKoin) { MaterialTheme { ConnectionFailureContent(failure) { dismissals++ } } } }
        onNodeWithText(failure.localized().title).assertIsDisplayed()
        onNodeWithText(readI18n("connectionError.showDetails")).assertDoesNotExist()
        onNodeWithContentDescription(readI18n("common.close"))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissals)
    }

    @Test
    fun longDetailsKeepTheConfirmationReachableInASmallViewport() = runDesktopComposeUiTest(width = 340, height = 300) {
        val failure = ConnectionFailure(ConnectionFailureReason.UNKNOWN, details = (1..200).joinToString("\n") { "diagnostic line $it" })
        var dismissals = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) { ConnectionFailureCard(failure) { dismissals++ } }
                }
            }
        }
        onNodeWithText(readI18n("connectionError.showDetails")).performScrollTo().performClick()
        onNodeWithTag("connection-failure-details").assertHeightIsEqualTo(180.dp)
        onNodeWithText(readI18n("connectionError.ok")).assertIsDisplayed().performClick()
        assertEquals(1, dismissals)
        onNodeWithContentDescription(readI18n("common.close")).assertIsDisplayed()
    }

    private fun setLanguage(language: String) {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }
}
