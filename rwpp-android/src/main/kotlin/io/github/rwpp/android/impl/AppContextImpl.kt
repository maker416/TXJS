/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import io.github.rwpp.AppContext
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.graphics.GL
import io.github.rwpp.impl.BaseAppContextImpl
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.ui.UI
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

@Single([AppContext::class])
class AppContextImpl : BaseAppContextImpl() {
    private val exitActions = mutableListOf<() -> Unit>()

    init {
        Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    }

    override fun onExit(action: () -> Unit) {
        exitActions.add(action)
    }

    override fun isAndroid(): Boolean = true

    override fun isDesktop(): Boolean = false

    override fun hasNetworkConnection(): Boolean = runCatching {
        val manager = get<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    override fun isBackgroundNetworkUnrestricted(): Boolean {
        val context = get<Context>().applicationContext
        return (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun openBackgroundNetworkSettings() {
        val context = get<Context>().applicationContext
        runCatching {
            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.recoverCatching {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            logger.warn("[KEEPALIVE] cannot open background settings", it)
            UI.showWarning(readI18n("settings.backgroundConnectionSettingsFailed"))
        }
    }

    override fun externalStoragePath(path: String): String {
        return Environment.getExternalStorageDirectory().absolutePath + "/rustedWarfare/$path"
    }

    override fun internalStoragePath(path: String): String {
        // getExternalFilesDir(null) → /Android/data/<package>/files/
        // 该目录属于应用私有外部存储：Android 11+ 起普通文件管理器无法访问，
        // 非 root 设备上对玩家不可见，卸载 App 时由系统一并清除。
        // 同时原版游戏核心的内部存储后端 e.a.i() 也使用同一路径，二者保持一致。
        return get<Context>().getExternalFilesDir(null)!!.absolutePath + "/$path"
    }

    override fun generatedLibPath(): String {
        return get<Context>().filesDir.absolutePath + "/generated_lib/"
    }

    override fun init() {
        super.init()
        GL.gameCanvas = GameCanvasImpl()
    }

    override fun exit() {
        markExitOverlayVisible()
        get<ConfigIO>().saveAllConfig()
        runCatching { GameEngine.t() }.getOrNull()?.bN?.apply {
            numLoadsSinceRunningGameOrNormalExit = 0
            numIncompleteLoadAttempts = 0
            save()
        }
        exitActions.forEach { it.invoke() }
        exitProcess(0)
    }
}
