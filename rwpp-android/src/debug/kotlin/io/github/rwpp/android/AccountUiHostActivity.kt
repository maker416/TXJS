/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.Net
import io.github.rwpp.ui.AccountView
import io.github.rwpp.ui.FakeAccountSession
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.RWPPTheme
import org.koin.compose.KoinContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy

/**
 * Debug-only：在真机/模拟器上走假数据账号流程，不加载游戏引擎、不读原版 assets、
 * 不访问 Gitee / BBS / 自有 Auth API。
 *
 * 正式启动器仍从 [LoadingScreen] 进入。
 */
class AccountUiHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        FakeAccountSession.applyLoggedOut()

        val previewKoin = koinApplication {
            modules(
                module {
                    single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh") }
                    single<ConfigIO> { stub(ConfigIO::class.java) }
                    single<Game> { stub(Game::class.java) }
                    single<Net> { stub(Net::class.java) }
                    single<AppContext> { stub(AppContext::class.java) }
                },
            )
        }.koin

        // 触发 RWPP bundle 解析（走 Application 里已有的 resolver，不发网络）。
        runCatching { readI18n("account.title") }

        setContent {
            KoinContext(previewKoin) {
                RWPPTheme(default = true) {
                    var showAccount by remember { mutableStateOf(false) }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(53, 57, 53)),
                    ) {
                        CompositionLocalProvider(
                            LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight),
                        ) {
                            if (showAccount) {
                                AccountView(onExit = { showAccount = false })
                            } else {
                                UI.UiProvider.MainMenu(
                                    multiplayer = {},
                                    singlePlayer = {},
                                    settings = {},
                                    mods = {},
                                    extension = {},
                                    resourceBrowser = {},
                                    openSourceInfo = {},
                                    account = { showAccount = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> stub(clazz: Class<T>): T {
    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "account-ui-stub:${clazz.simpleName}"
            "getKoin" -> appKoin
            "getLatestVersionProfile" -> null
            "isAndroid" -> true
            "isDesktop" -> false
            "isGameCouldContinue" -> false
            else -> when (method.returnType) {
                Void.TYPE, Void::class.java -> null
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.util.List::class.java, MutableList::class.java -> emptyList<Any>()
                java.util.Map::class.java, MutableMap::class.java -> emptyMap<Any, Any>()
                String::class.java -> ""
                else -> null
            }
        }
    } as T
}
