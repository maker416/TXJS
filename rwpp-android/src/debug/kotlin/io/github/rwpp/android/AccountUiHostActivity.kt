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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.config.resolveAccountApiUrl
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.koinInit
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.ui.AccountUiHostContent
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.RWPPTheme
import okhttp3.OkHttpClient
import org.koin.compose.KoinContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * Debug-only：账号 / 好友 / 聊天走统一账号 API，不加载游戏引擎。
 *
 * 正式启动器仍从 [LoadingScreen] 进入。
 *
 * adb: am start -n io.github.rwjs/io.github.rwpp.android.AccountUiHostActivity
 */
class AccountUiHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        AccountSession.resetForTests()
        FriendsSession.clear()
        UI.showAccountView = false
        UI.showFriendsView = false

        if (koinInit) {
            runCatching { appKoin.get<GameI18nResolver>().init() }
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val accountPrefs = AccountPreferences()
        // AccountSession 走全局 KoinComponent；独立宿主未 startKoin 时必须自带客户端。
        AccountSession.bindClient(
            AccountApiClient(
                baseUrl = resolveAccountApiUrl(accountPrefs.apiUrl),
                appKey = resolveAccountAppKey(accountPrefs.appKey),
                http = http,
            ),
            http,
        )

        val previewKoin = koinApplication {
            modules(
                module {
                    single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh") }
                    single { accountPrefs }
                    single<ConfigIO> { stub(ConfigIO::class.java) }
                    single<Game> { stub(Game::class.java) }
                    single<Net> { stub(Net::class.java, http) }
                    single<AppContext> { stub(AppContext::class.java) }
                },
            )
        }.koin

        setContent {
            KoinContext(previewKoin) {
                RWPPTheme(default = true) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(53, 57, 53)),
                    ) {
                        CompositionLocalProvider(
                            LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight),
                        ) {
                            AccountUiHostContent()
                        }
                    }
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> stub(clazz: Class<T>, http: OkHttpClient? = null): T {
    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "account-ui-stub:${clazz.simpleName}"
            "getKoin" -> if (koinInit) appKoin else null
            "getLatestVersionProfile" -> null
            "isAndroid" -> true
            "isDesktop" -> false
            "isGameCouldContinue" -> false
            "getClient" -> http
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
