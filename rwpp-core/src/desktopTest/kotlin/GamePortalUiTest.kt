/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.net.games.OnlineGame
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GamePortalUiTest {
    @BeforeTest fun setup() {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { reloadI18n() }

    @Test fun firstDownloadPermissionFitsPortraitWithoutServerSelection() = runDesktopComposeUiTest(width = 412, height = 844) {
        val consent = PortalContentConsent(OnlineGame("game", "网游测试入口"), required = true)
        setContent { MaterialTheme { PortalContentConsentDialog(consent) } }
        onNodeWithTag("game-portal-content-consent").assertIsDisplayed()
        onNodeWithTag("portal-content-accept").assertIsDisplayed()
        onNodeWithTag("portal-content-decline").assertIsDisplayed()
        onNodeWithTag("hall-server-main").assertDoesNotExist()
        val file = File("build/reports/game-portal/consent-portrait.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("game-portal-content-consent").captureToImage().toAwtImage(), "png", file)
        onNodeWithTag("portal-content-decline").performClick()
        runOnIdle { assertFalse(consent.decision.getCompleted()) }
    }

    @Test fun optionalAppearancePermissionOffersDefaultInLandscape() = runDesktopComposeUiTest(width = 960, height = 432) {
        val consent = PortalContentConsent(OnlineGame("game", "网游测试入口"), required = false)
        setContent { MaterialTheme { PortalContentConsentDialog(consent) } }
        onNodeWithText("使用默认外观").assertIsDisplayed().performClick()
        runOnIdle { assertFalse(consent.decision.getCompleted()) }
    }
}
