/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebRequestError
import java.util.UUID

/** 独立插件，不安装/调用论坛 handoff、文件上传或模组下载通道。 */
private object GamePortalGeckoExtension {
    private var preparation: GeckoResult<WebExtension>? = null
    fun prepare(context: Context): GeckoResult<WebExtension> {
        preparation?.let { return it }
        val result = GeckoResult<WebExtension>()
        preparation = result
        val runtime = BrowserGeckoRuntime.get(context)
        val preferences = context.getSharedPreferences("rwpp_game_portal_extension", Context.MODE_PRIVATE)
        val installation = androidInstallationId(context)
        val controller = runtime.webExtensionController
        val pending = if (preferences.getString("installation", null) == installation)
            controller.ensureBuiltIn("resource://android/assets/rwpp-game-portal-bridge/", "game-portal-bridge@rwpp")
        else controller.installBuiltIn("resource://android/assets/rwpp-game-portal-bridge/")
        fun fail(error: Throwable?) { preparation = null; result.completeExceptionally(error ?: IllegalStateException("Cannot prepare portal bridge")) }
        pending.accept({ extension ->
            if (extension == null) { fail(null); return@accept }
            controller.setAllowedInPrivateBrowsing(extension, true).accept({ configured ->
                if (configured == null || !preferences.edit().putString("installation", installation).commit()) fail(null)
                else result.complete(configured)
            }, ::fail)
        }, ::fail)
        return result
    }
}

@Composable
internal fun GamePortalEmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val browser = remember(state, context) { AndroidGamePortalBrowser(context, state) }
    DisposableEffect(browser) {
        state.controller = browser
        browser.initialize()
        onDispose { state.controller = null; browser.dispose() }
    }
    DisposableEffect(browser, lifecycle) {
        browser.session.setActive(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> browser.session.setActive(true)
                Lifecycle.Event.ON_PAUSE -> browser.session.setActive(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { browser.view }, modifier = modifier)
}

private class AndroidGamePortalBrowser(private val context: Context, private val state: EmbeddedBrowserState) : EmbeddedBrowserController {
    private val binding = requireNotNull(state.gamePortalBridge)
    private val dispatcher = GamePortalBrowserDispatcher(binding)
    private val runtime = BrowserGeckoRuntime.get(context)
    private val contextID = "game-portal-" + UUID.randomUUID()
    val session = GeckoSession(GeckoSessionSettings.Builder().contextId(contextID).usePrivateMode(true).build())
    val view = GeckoView(context).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW); setSession(this@AndroidGamePortalBrowser.session) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableSetOf<Job>()
    private var disposed = false
    private var initialized = false
    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var currentURL = state.initialUrl

    init {
        require(gamePortalOrigin(state.initialUrl) == gamePortalOrigin(binding.origin))
        dispatcher.beginDocument(state.initialUrl)
        runtime.storageController.clearDataForSessionContext(contextID)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (disposed || !binding.isActive()) return GeckoResult.fromValue(AllowOrDeny.DENY)
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW || gamePortalOrigin(request.uri) != gamePortalOrigin(binding.origin)) {
                    openExternal(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? { openExternal(uri); return GeckoResult.fromValue(null) }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (disposed || url == null || url == "about:blank") return
                currentURL = url
                state.url = url
                if (!dispatcher.trusted(url, true)) { invalidatePortal(); state.error = "页面来源已改变，请重新打开网游入口" }
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { if (!disposed) state.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { if (!disposed) state.canGoForward = canGoForward }
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (!disposed) { state.error = "网游网页加载失败"; state.isLoading = false }; return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (disposed) return
                clearDocument()
                dispatcher.beginDocument(url)
                currentURL = url
                state.url = url
                state.pageStarted()
                state.isLoading = true
                state.error = null
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (disposed) return
                state.isLoading = false
                state.progress = if (success) 1f else null
                if (success) state.pageLoaded(currentURL) else state.error = "网游网页加载失败"
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { if (!disposed) state.progress = progress / 100f }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { response.body?.close(); if (!disposed) state.error = "此网游网页不提供原生文件下载" }
            override fun onCrash(session: GeckoSession) { invalidatePortal(); if (!disposed) { state.error = "浏览器已停止，请重新打开网游入口"; state.isLoading = false } }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(prompt.dismiss())
            override fun onAuthPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(prompt.dismiss())
            override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(prompt.dismiss())
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) { callback.reject() }
            override fun onContentPermissionRequest(session: GeckoSession, permission: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            override fun onMediaPermissionRequest(session: GeckoSession, uri: String, video: Array<out GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?, callback: GeckoSession.PermissionDelegate.MediaCallback) { callback.reject() }
        }
        session.open(runtime)
    }

    fun initialize() {
        if (disposed || initialized) return
        initialized = true
        GamePortalGeckoExtension.prepare(context).accept({ addon ->
            if (disposed || !binding.isActive()) return@accept
            if (addon == null) { state.error = "无法准备网游网页桥接"; return@accept }
            extension = addon
            session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
                override fun onConnect(candidate: WebExtension.Port) {
                    val sender = candidate.sender
                    if (disposed || sender.session !== session || !sender.isTopLevel || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                        !dispatcher.trusted(sender.url, true) || !dispatcher.trusted(currentURL, true)) { candidate.disconnect(); return }
                    clearDocument()
                    port = candidate
                    val epoch = dispatcher.epoch()
                    candidate.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (disposed || port !== source || !dispatcher.trusted(currentURL, true, epoch)) return
                            val packet = message as? JSONObject ?: return
                            if (packet.optString("type") == "hello") { source.postMessage(JSONObject().put("type", "init").put("origin", gamePortalOrigin(binding.origin))); return }
                            if (packet.optString("type") != "request") return
                            val request = packet.optJSONObject("payload")?.toString() ?: return
                            if (requests.size >= 32) return
                            val job = scope.launch {
                                val reply = dispatcher.dispatch(request, currentURL, true, epoch) ?: return@launch
                                if (!disposed && port === source && dispatcher.trusted(currentURL, true, epoch)) source.postMessage(JSONObject().put("type", "reply").put("payload", JSONObject(reply)))
                            }
                            requests.add(job); job.invokeOnCompletion { requests.remove(job) }
                        }
                        override fun onDisconnect(source: WebExtension.Port) { if (port === source) { port = null; requests.toList().forEach(Job::cancel); requests.clear() } }
                    })
                }
            }, "rwppGamePortal")
            session.loadUri(state.initialUrl)
        }, { if (!disposed) { state.error = "无法准备网游网页桥接"; state.isLoading = false } })
    }

    private fun openExternal(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun clearDocument() { val previous = port; port = null; previous?.disconnect(); requests.toList().forEach(Job::cancel); requests.clear() }
    override fun emitPortalEvent(name: String, data: JsonObject) {
        val epoch = dispatcher.epoch()
        view.post { if (!disposed && dispatcher.trusted(currentURL, true, epoch)) port?.postMessage(JSONObject().put("type", "event").put("payload", JSONObject().put("v", 1).put("event", name).put("data", JSONObject(data.toString())))) }
    }
    override fun invalidatePortal() { dispatcher.revoke(); clearDocument(); if (session.isOpen) session.stop() }
    override fun goBack() { if (!disposed && binding.isActive()) session.goBack() }
    override fun goForward() { if (!disposed && binding.isActive()) session.goForward() }
    override fun loadUrl(url: String) { if (!disposed && binding.isActive() && gamePortalOrigin(url) == gamePortalOrigin(binding.origin)) session.loadUri(url) else openExternal(url) }
    override fun reload() { if (!disposed && binding.isActive()) session.reload() }
    override fun submitForumHandoff(trustedUrl: String, handoff: String?) = Unit
    fun dispose() {
        if (disposed) return
        disposed = true; dispatcher.revoke(); clearDocument(); scope.cancel()
        extension?.let { session.webExtensionController.setMessageDelegate(it, null, "rwppGamePortal") }
        session.stop(); view.releaseSession(); session.close()
        runtime.storageController.clearDataForSessionContext(contextID)
    }
}
