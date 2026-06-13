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
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.runtime.Builder
import io.github.rwpp.inject.runtime.Builder.logger
import io.github.rwpp.ui.InjectBuildOverall
import io.github.rwpp.ui.InjectBuildUiState
import io.github.rwpp.ui.InjectSetupScreen
import io.github.rwpp.ui.clearInjectLog
import io.github.rwpp.ui.injectLogText
import io.github.rwpp.ui.logStr
import io.github.rwpp.utils.Reflect
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.MenuLoadingView
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.RWSelectionColors
import io.github.rwpp.widget.v2.TitleBrush
import com.corrodinggames.rts.gameFramework.SettingsEngine
import javassist.android.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.KoinContext
import java.io.File
import kotlin.system.exitProcess


class LoadingScreen : ComponentActivity() {
    private fun logGeneratedArtifactState() {
        val generatedLib = File(generatedLibDir, "android-game-lib.jar")
        val generatedDex = File(dexFolder, "classes.dex")
        logger?.info(
            "Generated artifact state: " +
                    "libExists=${generatedLib.exists()}, libSize=${generatedLib.length()}, " +
                    "dexExists=${generatedDex.exists()}, dexSize=${generatedDex.length()}, " +
                    "generatedLibDir=$generatedLibDir, dexFolder=${dexFolder.absolutePath}, " +
                    "requireReloadingLib=$requireReloadingLib"
        )
    }

    private fun isGameEngineReady(): Boolean {
        return gameLoaded && runCatching { GameEngine.t() }.getOrNull() != null
    }

    private fun prepareDefaultStorageType() {
        val settingsEngine = SettingsEngine.getInstance(this)
        if (!settingsEngine.hasSelectedAStorageType) {
            settingsEngine.hasSelectedAStorageType = true
            settingsEngine.storageType = 0
            settingsEngine.save()
        }
    }

    private fun invalidateGeneratedGameDex() {
        runCatching { File(dexFolder, "classes.dex").delete() }
        runCatching { File(generatedLibDir, "android-game-lib.jar").delete() }
        requireReloadingLib = true
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
                            // 失败诊断状态：构建失败或引擎初始化失败时填充，驱动统一的可复制日志界面。
                            var diagnosticsReport by remember { mutableStateOf<String?>(null) }
                            var engineInitError by remember { mutableStateOf<Throwable?>(null) }
                            var showEngineFailure by remember { mutableStateOf(false) }

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
                                } else if (requireReloadingLib && !showEngineFailure) {
                                    var buildState by remember {
                                        mutableStateOf(InjectBuildUiState.starting())
                                    }

                                    LaunchedEffect(Unit) {
                                        clearInjectLog()
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

                                                // Always reload the root inject config from the APK before applying it.
                                                Builder.prepareReloadingLib()

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
                                                logGeneratedArtifactState()

                                                // 防死循环：记录连续重建次数，超过上限则停在诊断界面而非继续自动重启。
                                                val attempts = incrementRebuildAttemptCount()
                                                withContext(Dispatchers.Main) {
                                                    buildState = if (attempts > MAX_REBUILD_ATTEMPTS) {
                                                        diagnosticsReport = buildDiagnosticsReport(
                                                            "injectLoopGuard",
                                                            IllegalStateException("连续重建 $attempts 次仍未成功，已停止自动重启")
                                                        )
                                                        buildState.buildFailed(
                                                            IllegalStateException("Rebuild loop guard tripped after $attempts attempts")
                                                        )
                                                    } else {
                                                        buildState.buildSuccess()
                                                    }
                                                }
                                            }.onFailure { error ->
                                                diagnosticsReport = buildDiagnosticsReport("inject", error)
                                                withContext(Dispatchers.Main) {
                                                    buildState = buildState.buildFailed(error)
                                                }
                                                logger?.error("failed: ${error.stackTraceToString()}")
                                                logGeneratedArtifactState()
                                            }
                                        }
                                    }

                                    val buildLog by logStr
                                    val copyLogText by injectLogText

                                    RWPPTheme(true) {
                                        InjectSetupScreen(
                                            uiState = buildState,
                                            log = buildLog,
                                            copyLogText = diagnosticsReport ?: copyLogText,
                                            autoRestart = true,
                                            onRestart = { scheduleAppRestart(this@LoadingScreen) },
                                            onExit = {
                                                finishAffinity()
                                                exitProcess(0)
                                            },
                                        )
                                    }

                                } else if (showEngineFailure) {
                                    val failureLog = remember(diagnosticsReport) {
                                        AnnotatedString(diagnosticsReport.orEmpty())
                                    }
                                    RWPPTheme(true) {
                                        InjectSetupScreen(
                                            uiState = InjectBuildUiState(
                                                overall = InjectBuildOverall.Failed,
                                                errorSummary = engineInitError?.message?.take(200)
                                                    ?: engineInitError?.let { it::class.simpleName },
                                            ),
                                            log = failureLog,
                                            copyLogText = diagnosticsReport.orEmpty(),
                                            title = readI18n("inject.engineFailedTitle", I18nType.RWPP),
                                            showSteps = false,
                                            onRestart = { scheduleAppRestart(this@LoadingScreen) },
                                            onExit = {
                                                finishAffinity()
                                                exitProcess(0)
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
                                                    // 引擎可能已被上一轮初始化创建（例如 Activity 因配置变更被重建，
                                                    // 上一轮已 new 出引擎但未及置 gameLoaded 即被取消）。此时再次调用
                                                    // GameEngine.dv.a(...) 必抛 "gameEngine already created"，因此优先复用已存在实例。
                                                    val existingEngine = runCatching { GameEngine.t() }.getOrNull()
                                                    if (existingEngine != null) {
                                                        logger?.info("Reusing existing GameEngine instance; skip re-creation.")
                                                        loadingThread
                                                        prepareDefaultStorageType()
                                                    } else {
                                                        val engineImpl = GameEngine.dv.a(this@LoadingScreen)
                                                        Reflect.reifiedSet<GameEngine>(null, "ak", engineImpl)
                                                        loadingThread
                                                        prepareDefaultStorageType()
                                                        engineImpl.a(this@LoadingScreen as Context)
                                                        val configIO = appKoin.get<ConfigIO>()
                                                        if (!configIO.getGameConfig<Boolean>("hasSelectedAStorageType")) {
                                                            configIO.setGameConfig("hasSelectedAStorageType", true)
                                                            configIO.setGameConfig("storageType", 0)
                                                        }
                                                    }
                                                    true
                                                } catch (e: Exception) {
                                                    Reflect.reifiedSet<GameEngine>(null, "ak", null)
                                                    invalidateGeneratedGameDex()
                                                    Log.e("RWPP", "GameEngine init failed", e)
                                                    engineInitError = e
                                                    false
                                                }
                                            }
                                        }

                                        if (engineInitSuccess) {
                                            gameLoaded = true
                                            GameLoadedEvent().broadcastIn()
                                            openMainActivity()
                                        } else {
                                            diagnosticsReport = buildDiagnosticsReport("engineInit", engineInitError)
                                            showEngineFailure = true
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
