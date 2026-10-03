/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserModStreamTransfer
import io.github.rwpp.net.browser.browserModFileName
import io.github.rwpp.i18n.readI18n
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebRequestError

/** 每个进程只建一个 runtime；关闭资源站只关闭 session，不销毁整个 Gecko 内核。 */
private object BrowserGeckoRuntime {
    private var instance: GeckoRuntime? = null
    fun get(context: Context): GeckoRuntime = instance
        ?: GeckoRuntime.create(context.applicationContext).also { instance = it }
}

@Composable
actual fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val browser = remember(context, state) { AndroidGeckoBrowser(context, state) }
    DisposableEffect(browser) {
        state.controller = browser
        browser.initialize()
        onDispose {
            state.controller = null
            browser.dispose()
        }
    }
    DisposableEffect(browser, lifecycleOwner) {
        browser.session.setActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> browser.session.setActive(true)
                Lifecycle.Event.ON_PAUSE -> browser.session.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { browser.view }, modifier = modifier)
}

private class AndroidGeckoBrowser(context: Context, private val state: EmbeddedBrowserState) : EmbeddedBrowserController {
    private val runtime = BrowserGeckoRuntime.get(context)
    val session = GeckoSession()
    val view = GeckoView(context).apply {
        // TextureView 参与 Compose 裁剪/动画合成，避免 SurfaceView 覆盖工具栏或对话框。
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        setSession(session)
    }
    private var disposed = false
    private var initialized = false
    private var initializing = false
    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var bridgeReady = false
    private var pageFinished = false
    private var currentUrl: String? = null

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (disposed) return
                currentUrl = url
                state.url = url ?: state.initialUrl
                if (url != state.initialUrl) clearBridge()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (!disposed) state.canGoBack = canGoBack
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                if (!disposed) state.canGoForward = canGoForward
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (disposed) return GeckoResult.fromValue(AllowOrDeny.DENY)
                // target=_blank 沿用当前 session，Cookie 和下载响应仍由 Gecko 管理。
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    session.loadUri(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (!disposed) {
                    state.error = "Gecko ${error.category}/${error.code}"
                    state.isLoading = false
                }
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (disposed) return
                clearBridge()
                pageFinished = false
                currentUrl = url
                state.url = url
                state.pageStarted()
                state.error = null
                state.isLoading = true
                state.progress = 0f
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (disposed) return
                state.isLoading = false
                if (success) {
                    pageFinished = true
                    state.progress = 1f
                    publishLoadedPage()
                } else if (state.error == null) {
                    state.error = readI18n("browser.loadFailed")
                }
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (!disposed) state.progress = progress / 100f
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val input = response.body ?: return
                val name = browserModFileName(response.uri, response.headers["Content-Disposition"])
                if (disposed || name == null || response.statusCode !in 200..299) {
                    input.close()
                    return
                }
                val total = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }
                state.isLoading = false
                state.offerModDownload(BrowserModDownload(name, total, BrowserModStreamTransfer(input, total)))
            }
            override fun onCrash(session: GeckoSession) {
                if (!disposed) {
                    clearBridge()
                    state.error = readI18n("browser.engineStopped")
                    state.isLoading = false
                }
            }
        }
        session.open(runtime)
    }

    fun initialize() {
        if (disposed || initializing) return
        initializing = true
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/rwpp-forum-bridge/", "forum-bridge@rwpp").accept({ addon ->
            initializing = false
            if (disposed) return@accept
            extension = addon
            session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
                override fun onConnect(candidate: WebExtension.Port) {
                    val sender = candidate.sender
                    if (disposed || sender.session !== session || !sender.isTopLevel ||
                        sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                        sender.url != state.initialUrl || currentUrl != state.initialUrl) {
                        candidate.disconnect()
                        return
                    }
                    clearBridge()
                    port = candidate
                    candidate.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (disposed || port !== source || currentUrl != state.initialUrl) return
                            val payload = message as? JSONObject ?: return
                            if (payload.optString("type") != "ready") return
                            if (!payload.optBoolean("hasClient")) {
                                state.failClientLogin(readI18n("browser.bootstrapUnavailable"))
                                return
                            }
                            bridgeReady = true
                            publishLoadedPage()
                        }
                        override fun onDisconnect(source: WebExtension.Port) {
                            if (port === source) { port = null; bridgeReady = false }
                        }
                    })
                }
            }, "rwppForum")
            initialized = true
            session.loadUri(state.initialUrl)
        }, {
            initializing = false
            if (!disposed) state.failClientLogin(readI18n("browser.engineInitFailed"))
        })
    }

    private fun publishLoadedPage() {
        if (!pageFinished) return
        val url = currentUrl ?: return
        // 登录页同时等页面完成和扩展就绪，避免一次性 handoff 被提前消耗。
        if (url != state.initialUrl || bridgeReady) state.pageLoaded(url)
    }

    private fun clearBridge() {
        val previous = port
        port = null
        bridgeReady = false
        previous?.disconnect()
    }

    override fun submitForumHandoff(trustedUrl: String, handoff: String?) {
        require(handoff == null || handoff.matches(Regex("[a-f0-9]{64}")))
        if (!disposed && trustedUrl == state.initialUrl && currentUrl == trustedUrl && bridgeReady) {
            port?.postMessage(JSONObject().put("type", "handoff").put("url", trustedUrl).put("handoff", handoff ?: JSONObject.NULL))
        }
    }
    override fun goBack() { if (!disposed) session.goBack() }
    override fun goForward() { if (!disposed) session.goForward() }
    override fun loadUrl(url: String) {
        if (disposed) return
        if (!session.isOpen) session.open(runtime)
        session.loadUri(url)
    }
    override fun reload() {
        if (disposed) return
        if (!initialized) initialize()
        else if (!session.isOpen) loadUrl(currentUrl ?: state.initialUrl)
        else session.reload()
    }
    fun dispose() {
        disposed = true
        clearBridge()
        extension?.let { session.webExtensionController.setMessageDelegate(it, null, "rwppForum") }
        state.modDownload?.transfer?.cancel()
        session.stop()
        view.releaseSession()
        session.close()
    }
}
