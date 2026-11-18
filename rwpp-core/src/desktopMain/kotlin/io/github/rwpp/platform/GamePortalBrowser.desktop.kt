/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.rwpp.widget.LocalDialogInteractive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRequestContext
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.callback.CefFileDialogCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDialogHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestContextHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.net.URI
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 原生宿主可替换系统浏览器动作；网页不能访问或设置此回调。 */
internal val LocalGamePortalDesktopExternalNavigation = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun GamePortalEmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val externalNavigation = LocalGamePortalDesktopExternalNavigation.current
    val browser = remember(state, externalNavigation) { DesktopGamePortalBrowser(state, Color(background.toArgb()), externalNavigation) }
    DisposableEffect(browser) {
        state.controller = browser
        onDispose { state.controller = null; browser.dispose() }
    }
    BoxWithConstraints(modifier) {
        SwingPanel(factory = browser::createPanel, background = background, modifier = Modifier.fillMaxSize())
        if (!browser.contentShown && LocalDialogInteractive.current) {
            Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false)) {
                Box(Modifier.size(maxWidth, maxHeight).background(background))
            }
        }
    }
}

/** 每个入口拥有不共享的内存 RequestContext；关闭后不写入论坛缓存或 Cookie 库。 */
private class DesktopGamePortalBrowser(private val state: EmbeddedBrowserState, private val color: Color, private val externalNavigation: ((String) -> Unit)?) : EmbeddedBrowserController {
    private val binding = requireNotNull(state.gamePortalBridge)
    private val dispatcher = GamePortalBrowserDispatcher(binding)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ConcurrentHashMap<Long, Job>()
    private val initializing = AtomicBoolean(false)
    private val navigation = AtomicLong(0)
    @Volatile private var disposed = false
    @Volatile private var documentReady = false
    private var panel: JPanel? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var router: CefMessageRouter? = null
    private var context: CefRequestContext? = null
    var contentShown by mutableStateOf(false)
        private set

    init { require(gamePortalOrigin(state.initialUrl) == gamePortalOrigin(binding.origin)) }
    fun createPanel(): JPanel = JPanel(BorderLayout()).also { it.background = color; panel = it; initialize() }
    private fun initialize() {
        if (disposed || !initializing.compareAndSet(false, true)) return
        Thread({
            try {
                val app = DesktopBrowserRuntime.getApplication()
                publish {
                    if (!binding.isActive()) return@publish
                    val cef = app.createClient()
                    client = cef
                    // JCEF 该版本的 createContext 使用空 CefRequestContextSettings，cache_path 为空，原生为独立内存存储。
                    val isolated = CefRequestContext.createContext(object : CefRequestContextHandlerAdapter() {})
                    check(!isolated.isGlobal) { "Game portal context must be isolated" }
                    context = isolated
                    attachHandlers(cef)
                    val created = cef.createBrowser(state.initialUrl, false, false, isolated)
                    browser = created
                    created.uiComponent.background = color
                    panel?.add(created.uiComponent, BorderLayout.CENTER)
                    panel?.revalidate(); panel?.repaint()
                    created.createImmediately()
                }
            } catch (_: Exception) { publish { state.error = "无法打开网游网页，请重试"; state.isLoading = false } }
        }, "game-portal-browser-init").apply { isDaemon = true; start() }
    }

    private fun attachHandlers(cef: CefClient) {
        val messageRouter = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("rwGameQuery", "rwGameCancel"))
        messageRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(source: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                val epoch = dispatcher.epoch()
                if (disposed || !documentReady || source.identifier != browser?.identifier || persistent || !frame.isValid || !dispatcher.trusted(frame.url, frame.isMain, epoch) || pending.size >= 32) {
                    callback.failure(403, "Portal bridge unavailable"); return true
                }
                val pageURL = frame.url
                val job = scope.launch {
                    val reply = dispatcher.dispatch(request, pageURL, true, epoch)
                    if (!disposed && documentReady && dispatcher.trusted(pageURL, true, epoch)) {
                        if (reply != null) callback.success(reply) else callback.failure(400, "Invalid portal request")
                    }
                }
                pending[queryId] = job
                job.invokeOnCompletion { pending.remove(queryId, job) }
                return true
            }
            override fun onQueryCanceled(browser: CefBrowser, frame: CefFrame, queryId: Long) { pending.remove(queryId)?.cancel() }
        }, true)
        router = messageRouter
        cef.addMessageRouter(messageRouter)
        cef.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                if (!frame.isMain) return false
                if (disposed || !binding.isActive()) return true
                if (gamePortalOrigin(request.url) != gamePortalOrigin(binding.origin)) { openExternal(request.url); return true }
                documentReady = false
                navigation.incrementAndGet()
                pending.values.forEach(Job::cancel); pending.clear()
                // CEF 回调线程先关门；代次与业务 request 的入口一起在 EDT/Main 串行推进。
                // 不能在 CEF 线程先推进业务代次，令尚未入业务的旧 joinServer 误领新代次。
                val nextURL = request.url
                publish { dispatcher.beginDocument(nextURL) }
                return false
            }
            override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, targetUrl: String, userGesture: Boolean): Boolean { openExternal(targetUrl); return true }
        })
        cef.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                if (url == "about:blank" && !documentReady) return
                if (frame.isMain) publish {
                    state.url = url
                    if (!dispatcher.trusted(url, true)) { invalidatePortal(); state.error = "页面来源已改变，请重新打开网游入口" }
                }
            }
        })
        cef.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
                if (!frame.isMain) return
                documentReady = false
                navigation.incrementAndGet()
                pending.values.forEach(Job::cancel); pending.clear()
                val nextURL = frame.url
                publish { dispatcher.beginDocument(nextURL); contentShown = false; state.pageStarted(); state.isLoading = true; state.error = null }
            }
            override fun onLoadingStateChange(browser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) = publish {
                state.isLoading = isLoading; state.canGoBack = canGoBack; state.canGoForward = canGoForward; state.progress = if (isLoading) null else 1f
            }
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain || disposed) return
                if (httpStatusCode !in 200..299) {
                    publish { state.error = "网游网页加载失败"; state.isLoading = false }; return
                }
                val loadedURL = frame.url
                val loadedFrame = frame.identifier
                val loadedNavigation = navigation.get()
                publish {
                    // onBeforeBrowse/onLoadStart 的代次更新已按事件顺序排在本任务之前。
                    if (loadedNavigation != navigation.get() || !dispatcher.trusted(loadedURL, true)) return@publish
                    // JCEF 回调给出的 CefFrame 是临时 JNI 引用，离开回调后即失效。
                    // EDT 中重新获取当前主 frame，并核验文档，不能把临时 frame 跨线程保存。
                    val current = browser.mainFrame ?: return@publish
                    try {
                        if (!current.isValid || current.identifier != loadedFrame || current.url != loadedURL) return@publish
                        documentReady = true
                        current.executeJavaScript(gamePortalDesktopInjection(), loadedURL, 0)
                        state.pageLoaded(loadedURL); showRendered(browser, dispatcher.epoch())
                    } finally { current.dispose() }
                }
            }
            override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) publish { state.error = "网游网页加载失败"; state.isLoading = false }
            }
        })
        cef.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, targetFrameName: String): Boolean { openExternal(targetUrl); return true }
            override fun doClose(browser: CefBrowser): Boolean = true
        })
        cef.addDialogHandler(object : CefDialogHandler {
            override fun onFileDialog(browser: CefBrowser, mode: CefDialogHandler.FileDialogMode, title: String?, defaultFilePath: String?, acceptFilters: Vector<String>, acceptExtensions: Vector<String>, acceptDescriptions: Vector<String>, callback: CefFileDialogCallback): Boolean { callback.Cancel(); return true }
        })
        cef.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String, callback: CefBeforeDownloadCallback): Boolean = true
            override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) { callback.cancel() }
        })
    }

    private fun showRendered(browser: CefBrowser, epoch: Long) {
        val tools = browser.devToolsClient ?: return
        tools.executeDevToolsMethod("Runtime.evaluate", """{"expression":"new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>r(true))))","awaitPromise":true,"returnByValue":true}""")
            .thenCompose { tools.executeDevToolsMethod("Page.captureScreenshot", """{"format":"png","clip":{"x":0,"y":0,"width":1,"height":1,"scale":1},"captureBeyondViewport":false}""") }
            .whenComplete { _, failure -> publish { if (epoch == dispatcher.epoch() && documentReady && binding.isActive()) { if (failure == null) contentShown = true else state.error = "无法显示网游网页" } } }
    }
    private fun publish(action: () -> Unit) { SwingUtilities.invokeLater { if (!disposed) action() } }
    private fun openExternal(url: String) { runCatching {
        val uri = URI(url)
        if (uri.scheme in setOf("http", "https") && uri.rawUserInfo == null) {
            if (externalNavigation != null) externalNavigation.invoke(uri.toString())
            else if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
        }
    } }
    override fun emitPortalEvent(name: String, data: JsonObject) {
        val epoch = dispatcher.epoch()
        publish {
            val active = browser ?: return@publish
            if (!documentReady || !dispatcher.trusted(active.url, true, epoch)) return@publish
            val envelope = kotlinx.serialization.json.buildJsonObject { put("v", kotlinx.serialization.json.JsonPrimitive(1)); put("event", kotlinx.serialization.json.JsonPrimitive(name)); put("data", data) }
            active.executeJavaScript("if(typeof window.__rwGamePortalEvent==='function')window.__rwGamePortalEvent($envelope);", active.url, 0)
        }
    }
    override fun invalidatePortal() { dispatcher.revoke(); documentReady = false; pending.values.forEach(Job::cancel); pending.clear(); browser?.stopLoad() }
    override fun goBack() { if (!disposed && binding.isActive()) browser?.goBack() }
    override fun goForward() { if (!disposed && binding.isActive()) browser?.goForward() }
    override fun loadUrl(url: String) { if (!disposed && binding.isActive() && gamePortalOrigin(url) == gamePortalOrigin(binding.origin)) browser?.loadURL(url) else openExternal(url) }
    override fun reload() { if (!disposed && binding.isActive()) browser?.reload() }
    override fun submitForumHandoff(trustedUrl: String, handoff: String?) = Unit
    fun dispose() {
        if (disposed) return
        disposed = true; dispatcher.revoke(); documentReady = false; pending.values.forEach(Job::cancel); pending.clear(); scope.cancel()
        router?.let { client?.removeMessageRouter(it); it.dispose() }; router = null
        browser?.close(true); client?.dispose(); context?.dispose()
        browser = null; client = null; context = null; panel?.removeAll(); panel = null
    }
}

internal fun gamePortalDesktopInjection(): String = """
    (() => {
      if(window.top!==window||window.rwGame)return;
      ${GamePortalBrowserSDK.source}
      const listeners=new Set();
      const query=window.rwGameQuery;
      if(typeof query!=='function')return;
      const transport={
        request:envelope=>new Promise((resolve,reject)=>query({request:JSON.stringify(envelope),persistent:false,onSuccess:raw=>{try{resolve(JSON.parse(raw));}catch(_){reject(new Error('Invalid native response'));}},onFailure:()=>reject(new Error('Portal bridge unavailable'))})),
        subscribe:listener=>listeners.add(listener)
      };
      Object.defineProperty(window,'__rwGamePortalEvent',{value:message=>{for(const listener of listeners)listener(message);},writable:false,configurable:false});
      Object.defineProperty(window,'rwGame',{value:createRWGame(transport),writable:false,configurable:false});
      window.dispatchEvent(new Event('rwGameReady'));
    })();
""".trimIndent()
