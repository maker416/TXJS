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
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.resourceOutputDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 美术包磁盘覆盖与引擎内存贴图的编排器；普通 modReload 会复用旧图，不能用于此处。 */
object GameArtOverlayManager {
    private const val MARKER_FILE = ".rwpp_theme_overlay"
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    var applying by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    private val synchronizer = GameArtOverlaySynchronizer(
        readMarker = { marker.takeIf { it.isFile }?.readText() },
        rebuild = { appKoin.get<ExternalHandler>().rebuildResourceOverlay() },
        commitMarker = { id ->
            if (id == null) {
                check(!marker.exists() || marker.delete()) { "Cannot remove game art marker" }
            } else {
                checkNotNull(marker.parentFile).mkdirs()
                marker.writeText(id)
            }
        },
        reload = { appKoin.get<ModManager>().reloadGameArt() },
        beforeSync = {
            val room = appKoin.get<Game>().gameRoom
            check(!room.isConnecting && !room.isStartGame && !room.isStartingGame) {
                readI18n("themes.gameArtSessionActive")
            }
        },
    )
    private val marker: File get() = File(resourceOutputDir, MARKER_FILE)

    /** 启动早期控制器尚未加载时，按持久化选择探目录。 */
    fun currentThemeOverlayDir(): File? {
        val id = ArtThemeController.activeTheme?.id
            ?: runCatching { appKoin.getOrNull<Settings>()?.selectedThemePack }.getOrNull()
            ?: return null
        return ArtThemeController.resolveGameOverlayDir(id)
    }

    /** 必须在引擎首次加载资产前调用；启动流程随后会完整加载全部图片。 */
    fun syncAtStartup() {
        synchronizer.atStartup(currentThemeOverlayDir()?.parentFile?.name)
    }

    /** Main 上先置 busy，避免双击/切页；磁盘工作在 IO，贴图刷新由各平台分派到引擎线程。 */
    fun syncAsync() {
        // 无引擎的预览/测试环境不执行游戏资源操作。
        if (appKoin.getOrNull<ExternalHandler>() == null || appKoin.getOrNull<ModManager>() == null) {
            logger.info("[GAMEART] skip synchronization without engine services")
            return
        }
        if (applying) return
        applying = true
        failure = null
        scope.launch {
            try {
                logger.info("[GAMEART] synchronizing selected game art source")
                do {
                    val expected = currentThemeOverlayDir()?.parentFile?.name
                    withContext(Dispatchers.IO) { synchronizer.sync(expected) }
                    // 组合外调用若在重载中更新了选择，继续收敛到最新来源。
                } while (expected != currentThemeOverlayDir()?.parentFile?.name)
                logger.info("[GAMEART] disk overlay and engine textures synchronized")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger.error("Game art overlay sync failed", error)
                failure = error.message ?: error.javaClass.simpleName
            } finally {
                applying = false
            }
        }
    }
}
