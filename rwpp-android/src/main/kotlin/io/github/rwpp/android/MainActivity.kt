/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.corrodinggames.rts.appFramework.d
import io.github.rwpp.App
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.app.PermissionHelper
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.platform.LauncherMusic
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.QuitGameEvent
import io.github.rwpp.event.events.ReturnMainMenuEvent
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.logger
import io.github.rwpp.platform.applyImeImmersiveMode
import io.github.rwpp.ui.UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.KoinContext
import java.io.File


class MainActivity : ComponentActivity() {
    private val configIO: ConfigIO by appKoin.inject()

    /** IME 是否可见，由 onCreate 中的 OnApplyWindowInsetsListener 维护，驱动系统栏操作闸门。 */
    private var imeVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //koinApplication.androidContext(this)
        appKoin.declare(this, secondaryTypes = listOf(Context::class, Activity::class))

        gameLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            isGaming = false
            gameOver = false
            QuitGameEvent().broadcastIn()
            isSinglePlayerGame = false
            if(!isReturnToBattleRoom) {
                ReturnMainMenuEvent().broadcastIn()
            } else {
                GameEngine.t().a(appKoin.get(), gameView)
            }
            isReturnToBattleRoom = false
        }

        fileChooser = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uri = result.data?.data
            val actions = pickFileActions.toList()
            pickFileActions.clear()

            appKoin.get<PermissionHelper>().requestManageFilePermission {
                if (uri != null && actions.isNotEmpty()) {
                    lifecycleScope.launch {
                        val file = withContext(Dispatchers.IO) {
                            FileHelper.getRealPathFromURI(this@MainActivity, uri) { progress ->
                                runOnUiThread {
                                    actions.forEach { it.onProgress?.invoke(progress) }
                                }
                            }?.let { File(it) }
                        }

                        if (file != null) {
                            actions.forEach { it.onChooseFile(file) }
                        } else {
                            actions.forEach { it.onProgress?.invoke(FileChooseProgress(null, 0L, null)) }
                            UI.showWarning("Unable to open selected file")
                        }
                    }
                }
            }
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        Log.i("RWPP", "check permission: ${checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED}")

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }

        requestPermissions(permissions, 1)

        val settings = appKoin.get<Settings>()
        var backgroundImagePath by mutableStateOf(settings.backgroundImagePath ?: "")
        var backgroundImageEnabled by mutableStateOf(settings.backgroundImageEnabled)

        if(d.b(this, true, true)) {
            gameView = d.b(this)
        }

        // 维护 IME 可见性并打 [IMEGUARD] 日志：无真机时供远程诊断键盘被打断的时序。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != imeVisible) {
                logger.info("[IMEGUARD] ime visibility -> $visible")
                imeVisible = visible
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }

        setContent {
            KoinContext(appKoin) {
                // IME 可见期间绝不触碰系统栏：键盘弹出时系统会连带亮出导航栏，此时再
                // 隐藏/显示系统栏可能把正在弹起的输入法打断（Android 各 ROM 均有报告，
                // 华为平板上必现；详见 AGENTS.md 好友输入法踩坑记录）。
                SideEffect {
                    applyImeImmersiveMode(this@MainActivity, imeVisible)
                }

                val isPremium = true

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // 主题美术包的背景图优先于用户在设置中手选的背景；主题文件在 app 可控目录，无需存储权限
                    val themeBackground = ArtThemeController.activeTheme?.backgroundFile?.absolutePath
                    val effectiveBackgroundPath = if (backgroundImageEnabled) themeBackground ?: backgroundImagePath else ""
                    val painter = remember(effectiveBackgroundPath) {
                        if (effectiveBackgroundPath.isNotBlank() && isPremium &&
                            (themeBackground != null || appKoin.get<PermissionHelper>().hasManageFilePermission())
                        ) {
                            runCatching {
                                BitmapPainter(
                                    BitmapFactory.decodeFile(effectiveBackgroundPath).asImageBitmap()
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    }

                    if (effectiveBackgroundPath.isNotBlank() && isPremium && painter != null) {
                        Image(
                            painter = painter,
                            null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    App(isPremium = isPremium) {
                        backgroundImagePath = it
                        backgroundImageEnabled = settings.backgroundImageEnabled
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        configIO.saveAllConfig()
    }

    override fun onPause() {
        super.onPause()
        LauncherMusic.pause()
        if(gameView != null) GameEngine.t()?.b(gameView)
    }
    override fun onResume() {
        super.onResume()
        LauncherMusic.resume()
        if(gameView != null) activityResume()
    }

    override fun onStop() {
        super.onStop()
        configIO.saveAllConfig()
        if(gameView != null) GameEngine.t()?.b(gameView)
    }

    companion object {
        var gameView: com.corrodinggames.rts.appFramework.ab? = null

        fun activityResume() {
            uiHandler.post { runActivityResume(forceWhileReloading = false) }
        }

        /**
         * @param forceWhileReloading 取消回落专用：闸门仍开着、主循环只泵网络时才能安全跑 `i.q()`。
         * 普通退房/onResume 在保连接重载期间必须跳过，否则会和单位表重建撞车。
         */
        fun runActivityResume(forceWhileReloading: Boolean = false) {
            if (!forceWhileReloading &&
                (KeepConnectedReload.active || KeepConnectedReload.shouldRefreshMenuAfterAbort)
            ) {
                logger.info("[MODSYNC] skip activityResume while keep-connected reload/abort refresh is pending")
                return
            }
            GameEngine.t()?.let {
                gameView = d.a(appKoin.get(), gameView)
                it.a(appKoin.get(), gameView, true)
            }

            d.a(appKoin.get(), true)
            com.corrodinggames.rts.gameFramework.h.a.c()
        }
    }
}
