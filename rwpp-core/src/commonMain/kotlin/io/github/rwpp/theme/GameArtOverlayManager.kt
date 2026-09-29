/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.logger
import io.github.rwpp.resourceOutputDir
import io.github.rwpp.themeDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主题美术包「游戏内贴图」覆盖层（v4）的编排器。
 *
 * 主题包内 `game/` 目录镜像 assets 结构（`game/units/...`、`game/tilesets/bitmaps/...`），
 * 启用时叠加到 `resource_generated/`（与扩展系统的 `.rwres` 组合，主题包最后叠加、冲突时赢），
 * 经两端 AssetInject 的资产重定向生效。单位贴图需 `modReload` 重新解码；
 * 地块贴图在下次进图时自然生效。
 *
 * 幂等设计：`resource_generated/.rwpp_theme_overlay` 标记文件记录当前叠加的主题 id，
 * 状态一致时跳过重建——这使启动恢复（[ensureInitialized]→apply）与 [init] 启动同步
 * 天然不冲突（启动期 init 已重建过则运行期 sync 直接跳过，不会触发多余的 modReload）。
 */
object GameArtOverlayManager {

    private const val MARKER_FILE = ".rwpp_theme_overlay"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在重建覆盖层 + 重载单位（供主题页显示进度）。 */
    var applying by mutableStateOf(false)
        private set

    /**
     * 当前期望叠加的主题包 `game/` 目录。
     * 优先取控制器已加载的激活主题；控制器尚未初始化（启动早期）时按 Settings 直接探目录。
     */
    fun currentThemeOverlayDir(): File? {
        ArtThemeController.activeTheme?.gameOverlayDir?.let { return it }
        val id = runCatching { appKoin.getOrNull<Settings>()?.selectedThemePack }.getOrNull()
            ?: return null
        val dir = File(themeDir, "$id/game")
        return dir.takeIf { it.isDirectory && it.walkTopDown().any { f -> f.isFile } }
    }

    /**
     * 启动期同步（`BaseExternalHandlerImpl.init()` 调用，此时引擎尚未读取资产）：
     * 标记与期望不一致才重建，绝不触发 modReload（引擎即将自行全量加载）。
     */
    fun syncAtStartup() {
        val expected = currentThemeOverlayDir()?.parentFile?.name
        if (readMarker() == expected) return
        logger.info("Game art overlay changed at startup (expected=$expected), rebuilding resources...")
        rebuildInternal(expected)
    }

    /**
     * 运行期同步（主题包启用/停用后调用）：状态不一致时重建覆盖层并重载单位贴图。
     * 整个流程在 IO 协程内异步执行，进度经 [applying] 暴露。
     */
    fun syncAsync() {
        scope.launch {
            val expected = currentThemeOverlayDir()?.parentFile?.name
            if (readMarker() == expected) return@launch
            applying = true
            try {
                rebuildInternal(expected)
                logger.info("Game art overlay applied ($expected), reloading units...")
                // 主题页与房间/对局页互斥，此处必不在房间内，modReload 安全
                appKoin.getOrNull<ModManager>()?.modReload()
            } catch (t: Throwable) {
                logger.error("Game art overlay sync failed: ${t.message}")
            } finally {
                applying = false
            }
        }
    }

    /** 重建 resource_generated/ 并写标记。 */
    private fun rebuildInternal(expectedThemeId: String?) {
        val handler = appKoin.getOrNull<ExternalHandler>() ?: return
        handler.rebuildResourceOverlay()
        val marker = File(resourceOutputDir, MARKER_FILE)
        if (expectedThemeId == null) {
            marker.delete()
        } else {
            runCatching { marker.writeText(expectedThemeId) }
        }
    }

    private fun readMarker(): String? =
        runCatching {
            File(resourceOutputDir, MARKER_FILE).takeIf { it.exists() }?.readText()
        }.getOrNull()
}
