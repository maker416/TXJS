/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.android.LogcatAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import io.github.rwpp.android.impl.GameSoundPoolImpl
import io.github.rwpp.app.PermissionHelper
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.CoreImplModule
import io.github.rwpp.config.ConfigModule
import io.github.rwpp.game.audio.GameSoundPool
import io.github.rwpp.game.units.comp.CompModule
import io.github.rwpp.generatedLibDir
import io.github.rwpp.inject.runtime.Builder
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.ui.defaultBuildLogger
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module
import android.util.Log
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess


class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        koinApplication = startKoin {
            androidLogger()
            modules(ConfigModule().module, CoreImplModule().module, AndroidModule().module, CompModule().module)
        }

        koinInit = true
        appKoin = koinApplication.koin

        appKoin.declare(GameSoundPoolImpl(), secondaryTypes = listOf(GameSoundPool::class))
        appKoin.declare(this, secondaryTypes = listOf(Context::class))

        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        lc.stop()

        // setup FileAppender
        val encoder1 = PatternLayoutEncoder()
        encoder1.context = lc
        encoder1.pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
        encoder1.start()

        val fileAppender = FileAppender<ILoggingEvent>()
        fileAppender.isAppend = false
        fileAppender.context = lc
        val logFile = File("/storage/emulated/0/rustedWarfare/rwpp-log.txt")
        logFile.parentFile?.mkdirs()
        fileAppender.file = logFile.absolutePath
        fileAppender.encoder = encoder1
        val fileAppenderOk = runCatching { fileAppender.start() }.isSuccess
        if (!fileAppenderOk) {
            Log.e("RWPP", "File logging unavailable (storage permission or path); using logcat only")
        }

        // setup LogcatAppender
        val encoder2 = PatternLayoutEncoder()
        encoder2.context = lc
        encoder2.pattern = "[%thread] %msg%n"
        encoder2.start()

        val logcatAppender = LogcatAppender()
        logcatAppender.context = lc
        logcatAppender.encoder = encoder2
        logcatAppender.start()

        // add the newly created appenders to the root logger;
        // qualify Logger to disambiguate from org.slf4j.Logger
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        if (fileAppenderOk) {
            root.addAppender(fileAppender)
        }
        root.addAppender(logcatAppender)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                LoggerFactory.getLogger(packageName).error("Uncaught exception in thread ${thread.name}", ex)
            } catch (_: Throwable) { /* logger may not be ready */ }
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                defaultHandler?.uncaughtException(thread, ex)
            } else {
                exitProcess(0)
            }
        }

        logger = LoggerFactory.getLogger(packageName)

        onInit()
    }

    fun onInit() {
        if (!init) {
            appKoin.get<ConfigIO>().readAllConfig()
            val permissionHelper = appKoin.get<PermissionHelper>()
            val hasPermission = permissionHelper.hasManageFilePermission()
            Builder.outputDir = generatedLibDir
            Builder.logger = defaultBuildLogger
            init = true
            dexFolder = getDir("dexfiles", MODE_PRIVATE)
            requireReloadingLib = Builder.prepareReloadingLib() || !File(dexFolder, "classes.dex").exists()
            logger.info("hasPermission: $hasPermission, requireReloadingLib: $requireReloadingLib, generatedLibDir: $generatedLibDir, dexExists: ${File(dexFolder, "classes.dex").exists()}")
            if (!requireReloadingLib) {
                // 加载阶段：在全新进程的启动早期干净加载已构建好的 DEX，并校验其确实可用。
                // 任何游戏类被真正触达之前必须完成加载（GameSoundPoolImpl 仅在方法体内引用游戏类，安全）。
                val loadResult = runCatching {
                    loadDex(this, "${dexFolder.absolutePath}/classes.dex")
                    // 校验：尝试解析一个已知游戏类，确认注入后的 DEX 真正加载成功。
                    Class.forName("com.corrodinggames.rts.gameFramework.k", false, classLoader)
                }
                if (loadResult.isSuccess) {
                    // 已进入良好状态，清除重建循环计数。
                    resetRebuildAttemptCount()
                } else {
                    val error = loadResult.exceptionOrNull()
                    logger.error("Clean DEX load/verify failed, will rebuild.\n${buildDiagnosticsReport("dexLoad", error)}")
                    // 作废已生成产物，转入重建流程交由 LoadingScreen 处理。
                    runCatching { File(dexFolder, "classes.dex").delete() }
                    runCatching { File(generatedLibDir, "android-game-lib.jar").delete() }
                    requireReloadingLib = true
                }
            }
        }
    }
}