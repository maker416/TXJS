/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toAwtImage
import io.github.rwpp.app.*
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.i18n.i18nTable
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MandatoryUpdateUiTest {
    private val release = LatestVersionProfile("1.21.0", "## 更流畅的全新体验\n\n- 新增 Android 分卷更新\n- 更新后自动清理旧产物\n- 保留您的模组与个人数据\n\n### 体验优化\n联机与资源管理体验优化。", false, emptyList())
    private fun screenshot(name: String, width: Int, height: Int, progress: UpdateProgress) =
        runDesktopComposeUiTest(width = width, height = height) {
            i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
            var exits = 0
            setContent { MandatoryUpdateScreen(release, progress, {}, {}, { exits++ }, {}) }
            onNodeWithText("退出应用").assertIsDisplayed()
            onNodeWithText(if (progress.stage == UpdateStage.DOWNLOADING) "停止下载" else "下载并安装").assertIsDisplayed()
            onNodeWithText("稍后提醒").assertDoesNotExist()
            val output = File("build/reports/update-ui/$name.png").apply { parentFile.mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
            onNodeWithText("退出应用").performClick()
            assertEquals(1, exits)
        }
    @Test fun desktopReady() = screenshot("desktop", 1100, 700, UpdateProgress(UpdateStage.READY))
    @Test fun phonePortrait() = screenshot("phone", 360, 720, UpdateProgress(UpdateStage.READY))
    @Test fun phoneLandscapeDownloading() = screenshot("landscape", 800, 360,
        UpdateProgress(UpdateStage.DOWNLOADING, .42f, 65000000, 150000000, 2000000, 1, 2))
}
