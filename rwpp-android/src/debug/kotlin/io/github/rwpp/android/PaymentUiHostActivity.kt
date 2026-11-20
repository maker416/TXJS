/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.os.Bundle
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.PaymentController
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.payment.PaymentRules
import io.github.rwpp.ui.AccountUiHostContent
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.ConstraintWindowManager
import io.github.rwpp.widget.RWPPTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.peanuuutz.tomlkt.Toml
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy

/** Isolated ADB-only fixture host. It never loads or writes the user's account preferences. */
class PaymentUiHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the separate test package may run this exported host.
        if (!packageName.endsWith(".paymenttest")) { finish(); return }
        val uncaught = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            android.util.Log.e("PaymentUiFixture", "Isolated fixture failure", failure)
            uncaught?.uncaughtException(thread, failure)
        }
        requestedOrientation = if (intent.getBooleanExtra("landscape", false))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val uas = loopback(intent.getStringExtra("uas_url") ?: "http://127.0.0.1:18080") ?: run { finish(); return }
        val platform = loopback(intent.getStringExtra("platform_url") ?: "http://127.0.0.1:18081") ?: run { finish(); return }
        val paymentId = intent.getStringExtra("payment_id").orEmpty()
        if (paymentId.isNotBlank() && !PaymentRules.validId(paymentId)) { finish(); return }
        val appKey = intent.getStringExtra("app_key") ?: "ak_payment_client_test"
        val insecure = intent.getBooleanExtra("insecure", false)
        if (appKey.isBlank()) { finish(); return }
        logger = LoggerFactory.getLogger("PaymentUiFixture")
        val http = OkHttpClient()
        val prefs = AccountPreferences(apiUrl = if (insecure) "http://unsafe.invalid" else uas, appKey = appKey)
        System.setProperty("rwjs.openPlatform.url", platform)
        appKoin = startKoin { modules(module {
            single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh") }
            single { prefs }
            single<ConfigIO> { fixtureStub(ConfigIO::class.java, http) }
            single<Game> { fixtureStub(Game::class.java, http) }
            single<Net> { fixtureStub(Net::class.java, http) }
            single<AppContext> { fixtureStub(AppContext::class.java, http) }
        }) }.koin
        koinInit = true
        AccountSession.resetForTests()
        AccountSession.bindClient(AccountApiClient(uas, appKey, http), http)
        if (insecure) {
            // Deliberately not a JWT: this scenario must fail local URL validation before any payment request.
            AccountSession.applyPreview(AccountUser(200, "payment-fixture", email = "fixture@example.test",
                nickname = "支付联调账号", accountId = "200"), "local-ui-preview-not-a-jwt")
        } else AccountSession.applyLoggedOutPreview()
        AccountSession.networkEnabled = true
        UI.showAccountView = true
        UI.showFriendsView = false
        lifecycleScope.launch {
            try {
                val bundle = withContext(Dispatchers.IO) {
                    assets.open("composeResources/io.github.rwpp.rwpp_core.generated.resources/files/bundle_zh.toml")
                        .bufferedReader().use { it.readText() }
                }
                i18nTable = Toml.parseToTomlTable(bundle)
                android.util.Log.i("PaymentUiFixture", "Fixture resources loaded")
                setContent {
                KoinContext(appKoin) {
                    RWPPTheme(default = true) {
                        BoxWithConstraints(Modifier.fillMaxSize().background(Color(53, 57, 53))) {
                            CompositionLocalProvider(LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight)) {
                                AccountUiHostContent()
                                LaunchedEffect(Unit) { android.util.Log.i("PaymentUiFixture", "Fixture UI ready") }
                                // A normal synthetic-account login obtains its JWT inside UAS/AccountSession.
                                LaunchedEffect(AccountSession.loggedIn) {
                                    if (AccountSession.loggedIn) {
                                        try {
                                            val orderId = paymentId.takeIf(PaymentRules::validId) ?: withContext(Dispatchers.IO) {
                                                val payer = AccountSession.user?.accountId.orEmpty()
                                                require(payer.matches(Regex("[1-9][0-9]{0,18}")))
                                                val fixtureHttp = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
                                                fixtureHttp.newCall(Request.Builder().url("$platform/test/new-order?account_id=$payer").build()).execute().use { response ->
                                                    check(response.isSuccessful)
                                                    val body = requireNotNull(response.body)
                                                    check(body.contentLength() <= 8192)
                                                    JSONObject(body.string()).getString("id").also { require(PaymentRules.validId(it)) }
                                                }
                                            }
                                            PaymentController.open(orderId)
                                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                        catch (_: Exception) { UI.showWarning("本机支付测试服务暂未就绪，请联系测试人员。") }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Throwable) {
                android.util.Log.e("PaymentUiFixture", "Fixture startup failed", failure)
                setContentView(android.widget.TextView(this@PaymentUiHostActivity).apply {
                    text = "支付测试界面初始化失败：${failure.javaClass.simpleName}。请联系测试人员。"
                    setPadding(32, 64, 32, 32)
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.DKGRAY)
                })
            }
        }
    }

    private fun loopback(raw: String?): String? {
        val url = raw?.toHttpUrlOrNull() ?: return null
        if (url.host !in setOf("127.0.0.1", "localhost", "::1") || url.username.isNotBlank() ||
            url.password.isNotBlank() || url.query != null || url.fragment != null || url.encodedPath != "/") return null
        return url.toString().trimEnd('/')
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> fixtureStub(type: Class<T>, http: OkHttpClient): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "PaymentFixture"
            "getKoin" -> appKoin
            "isAndroid" -> true
            "isDesktop" -> false
            "getClient" -> http
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.util.List::class.java -> emptyList<Any>()
                java.util.Map::class.java -> emptyMap<Any, Any>()
                String::class.java -> ""
                else -> null
            }
        }
    } as T
