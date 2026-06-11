/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import android.util.Log
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.app.PermissionHelper
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.generatedLibDir
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.runtime.Builder
import io.github.rwpp.inject.runtime.Builder.logger
import io.github.rwpp.ui.InjectBuildUiState
import io.github.rwpp.ui.InjectSetupScreen
import io.github.rwpp.ui.logStr
import io.github.rwpp.utils.Reflect
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.MenuLoadingView
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.RWSelectionColors
import io.github.rwpp.widget.v2.TitleBrush
import javassist.android.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.KoinContext
import java.io.File
import kotlin.system.exitProcess


class LoadingScreen : ComponentActivity() {
    private fun isGameEngineReady(): Boolean {
        return gameLoaded && runCatching { GameEngine.t() }.getOrNull() != null
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        appKoin.declare(this, secondaryTypes = listOf(Context::class))

        val permissionHelper = appKoin.get<PermissionHelper>()
        var hasPermission by mutableStateOf(permissionHelper.hasManageFilePermission())
        val hasPermissionPast = hasPermission

        setContent {
            KoinContext {
                val brush = TitleBrush()

                MaterialTheme(
                    typography = typography,
                    colorScheme = lightColorScheme()
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brush),
                        contentAlignment = Alignment.Center
                    ) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides RWSelectionColors,
                            LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight)
                        ) {
                            if (!hasPermission) {
                                LaunchedEffect(Unit) {
                                    Toast.makeText(
                                        appKoin.get(),
                                        "RWPP需要管理文件权限才能正常运行！",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    permissionHelper.requestManageFilePermission {
                                        if (it) hasPermission = true
                                        else {
                                            exitProcess(0)
                                        }
                                    }
                                }
                            } else {
                                if (isGameEngineReady()) {
                                    MenuLoadingView(message)

                                    LaunchedEffect(Unit) {
                                        openMainActivity()
                                    }
                                } else if (requireReloadingLib) {
                                    var buildState by remember {
                                        mutableStateOf(InjectBuildUiState.starting())
                                    }

                                    LaunchedEffect(Unit) {
                                        logStr.value = AnnotatedString("")
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.startBuild()
                                                }

                                                val resource = Thread
                                                    .currentThread()
                                                    .contextClassLoader!!
                                                    .getResourceAsStream("android-game-lib.jar")

                                                val tempJar = File.createTempFile("android-game-lib", ".jar")
                                                tempJar.deleteOnExit()
                                                tempJar.writeBytes(resource.readBytes())
                                                resource.close()

                                                if (!hasPermissionPast) {
                                                    Builder.prepareReloadingLib()
                                                }

                                                GameLibraries.defClassPool.appendDalvikClassPath()

                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.prepareDone()
                                                }

                                                Builder.init(GameLibraries.`android-game-lib`, tempJar)

                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.applyDone()
                                                }

                                                val libPath = "$generatedLibDir/android-game-lib.jar"
                                                logger?.logging("compiling dex: $libPath")
                                                logger?.logging("Saving dex to ${dexFolder.absolutePath}/classes.dex")
                                                val dex = DexFile()
                                                dex.addJarFile(libPath)
                                                dex.writeFile("${dexFolder.absolutePath}/classes.dex")
                                                logger?.logging("Successfully compile dex")

                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.buildSuccess()
                                                }
                                            }.onFailure { error ->
                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.buildFailed(error)
                                                }
                                                logger?.error("failed: ${error.stackTraceToString()}")
                                            }
                                        }
                                    }

                                    val buildLog by logStr

                                    RWPPTheme(true) {
                                        InjectSetupScreen(
                                            uiState = buildState,
                                            log = buildLog,
                                            onExit = {
                                                finishAffinity()
                                                appKoin.get<AppContext>().exit()
                                            },
                                        )
                                    }

                                } else {
                                    MenuLoadingView(message)

                                    LaunchedEffect(Unit) {
                                        message = "loading"
                                        val engineInitSuccess = withContext(Dispatchers.IO) {
                                            appKoin.get<AppContext>().init()
                                            runCatching {
                                                val mapsDir = File(
                                                    appKoin.get<AppContext>().externalStoragePath("maps")
                                                )
                                                if (mapsDir.isDirectory) {
                                                    mapsDir.walk()
                                                        .filter { it.isFile && it.name.startsWith("generated_") }
                                                        .forEach { it.delete() }
                                                }
                                            }.onFailure {
                                                Log.e("RWPP", "cleanup generated_ maps failed", it)
                                            }

                                            if (isGameEngineReady()) {
                                                true
                                            } else {
                                                try {
                                                    val engineImpl = GameEngine.dv.a(this@LoadingScreen)
                                                    Reflect.reifiedSet<GameEngine>(null, "ak", engineImpl)
                                                    loadingThread
                                                    engineImpl.a(this@LoadingScreen as Context)
                                                    val configIO = appKoin.get<ConfigIO>()
                                                    if (!configIO.getGameConfig<Boolean>("hasSelectedAStorageType")) {
                                                        configIO.setGameConfig("hasSelectedAStorageType", true)
                                                        configIO.setGameConfig("storageType", 0)
                                                    }
                                                    true
                                                } catch (e: Exception) {
                                                    Reflect.reifiedSet<GameEngine>(null, "ak", null)
                                                    Log.e("RWPP", "GameEngine init failed", e)
                                                    false
                                                }
                                            }
                                        }

                                        if (engineInitSuccess) {
                                            gameLoaded = true
                                            GameLoadedEvent().broadcastIn()
                                            openMainActivity()
                                        } else {
                                            message = "Game engine init failed, please restart"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
