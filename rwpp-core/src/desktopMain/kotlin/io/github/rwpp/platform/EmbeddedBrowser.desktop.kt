/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import io.github.rwpp.widget.LocalDialogInteractive
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDialogHandler
import org.cef.callback.CefFileDialogCallback
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserModTransfer
import io.github.rwpp.net.browser.browserResourceFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.Vector
import io.github.rwpp.net.browser.BrowserModUploadCache

@Composable
actual fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val environment = rememberBrowserKernelEnvironment()
    if (BrowserKernelRuntime.directory(environment.requirement.id) == null) {
        Box(modifier) { BrowserKernelGate(true, onReady = {}) {
            if (state.gamePortalBridge != null) GamePortalEmbeddedBrowser(state, Modifier.fillMaxSize())
            else DesktopEmbeddedBrowser(state, Modifier.fillMaxSize())
        } }
        return
    }
    if (state.gamePortalBridge != null) GamePortalEmbeddedBrowser(state, modifier) else DesktopEmbeddedBrowser(state, modifier)
}

@Composable
private fun DesktopEmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val panelColor = Color(background.toArgb())
    val browser = remember(state) { DesktopBrowserController(state, panelColor) }
    val contentReady = !state.isLoading && !state.clientAuthenticating &&
        state.error == null && state.clientLoginError == null
    DisposableEffect(browser) {
        state.controller = browser
        onDispose {
            state.controller = null
            browser.dispose()
        }
    }
    BoxWithConstraints(modifier) {
        SwingPanel(
            factory = browser::createPanel,
            background = background,
            update = { browser.updatePresentation(panelColor, contentReady) },
            modifier = Modifier.fillMaxSize(),
        )
        // 原生 WINDOW 才能稳定盖住 Chromium 子窗口；退出页面/打开账号等叠加页时立即撤销。
        if (!browser.contentShown && LocalDialogInteractive.current) {
            Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false)) {
                Box(Modifier.size(maxWidth, maxHeight).background(background))
            }
        }
    }
}

/** Chromium 的安装/初始化在后台执行；整个应用共用 runtime，每次打开页面创建独立 client。 */
internal object DesktopBrowserRuntime {
    private var app: CefApp? = null

    @Synchronized
    fun getApplication(): CefApp {
        app?.let { return it }
        val profileRoot = File(System.getProperty("user.home"), ".rwjs/browser")
        val environment = DesktopKernelEnvironment.value
        val kernel = BrowserKernelRuntime.directory(environment.requirement.id)
            ?: io.github.rwpp.net.browser.BrowserKernelManager(environment.root, okhttp3.OkHttpClient()).installed(environment.requirement)
            ?: error("Browser kernel must be prepared before Chromium initialization")
        val builder = CefAppBuilder().apply {
            setInstallDir(kernel)
            setSkipInstallation(true)
            cefSettings.windowless_rendering_enabled = false
            cefSettings.cache_path = File(profileRoot, "cache").absolutePath
            cefSettings.persist_session_cookies = true
            // 下载与校验由公共组件管理器完成；JCEF 不访问默认境外镜像。
            setMirrors(emptyList())
            setProgressHandler { _, _ -> }
            setAppHandler(object : MavenCefAppHandlerAdapter() {
                override fun stateHasChanged(state: CefApp.CefAppState) = Unit
            })
        }
        return builder.build().also { app = it }
    }
}

private class DesktopBrowserController(
    private val state: EmbeddedBrowserState,
    private var background: Color,
) : EmbeddedBrowserController {
    @Volatile
    private var disposed = false
    private val initializing = AtomicBoolean(false)
    private var container: JPanel? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var contentReady = false
    var contentShown by mutableStateOf(false)
        private set
    private var mainFrameLoaded = false
    private var presentationGeneration = 0
    private var pendingPresentation: Int? = null
    private val downloads = ConcurrentHashMap<Int, CefModTransfer>()
    private val downloadCallbacks = ConcurrentHashMap<Int, CefDownloadItemCallback>()
    private val uploads = ConcurrentHashMap.newKeySet<BrowserFileUploadRequest>()
    private val uploadCache = BrowserModUploadCache(File(System.getProperty("java.io.tmpdir")))

    fun createPanel(): JPanel = JPanel(BorderLayout()).also {
        it.background = background
        container = it
        initialize()
    }

    fun updatePresentation(background: Color, contentReady: Boolean) {
        if (this.background != background) {
            this.background = background
            container?.let { setBackground(it, background) }
        }
        this.contentReady = contentReady
        showContentIfReady()
    }

    private fun showContentIfReady() {
        if (disposed || contentShown || !contentReady || !mainFrameLoaded || pendingPresentation != null) return
        val activeBrowser = browser ?: return
        // Chromium 保持可见并在原生浮层遮挡下绘制。隐藏整个浏览器会暂停渲染，
        // 即使 onLoadEnd 已到达，重新显示时仍可能闪白。等待页面帧并确认合成器已输出，
        // 仅取一个像素作提交屏障，不回读/展示整页，也不改变网页原来的背景色。
        val generation = presentationGeneration
        val url = activeBrowser.url
        pendingPresentation = generation
        val parameters = JsonObject()
            .add("expression", "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))))")
            .add("awaitPromise", true).add("returnByValue", true)
        val drawing = runCatching {
            checkNotNull(activeBrowser.devToolsClient) { "Browser display is unavailable" }
                .executeDevToolsMethod("Runtime.evaluate", parameters.toString())
        }.getOrElse {
            pendingPresentation = null
            state.error = it.message ?: "Cannot prepare browser display"
            return
        }
        val rendered = drawing.thenCompose { result ->
            val response = Json.parse(result).asObject()
            check(response["exceptionDetails"] == null && response["result"]?.asObject()?.get("value")?.asBoolean() == true) {
                "Cannot prepare browser display"
            }
            checkNotNull(activeBrowser.devToolsClient) { "Browser display is unavailable" }
                .executeDevToolsMethod("Page.captureScreenshot",
                    """{"format":"png","clip":{"x":0,"y":0,"width":1,"height":1,"scale":1},"captureBeyondViewport":false}""")
        }
        rendered.whenComplete { result, error ->
            publish {
                if (generation != presentationGeneration || pendingPresentation != generation || browser !== activeBrowser) return@publish
                pendingPresentation = null
                if (activeBrowser.url != url || !mainFrameLoaded || state.isLoading || state.clientAuthenticating ||
                    state.error != null || state.clientLoginError != null) return@publish
                val painted = error == null && runCatching {
                    val response = Json.parse(result).asObject()
                    response["data"]?.asString()?.isNotEmpty() == true
                }.getOrDefault(false)
                if (painted) contentShown = true
                else state.error = error?.message ?: "Cannot prepare browser display"
            }
        }
    }

    private fun setBackground(component: Component, color: Color) {
        component.background = color
        if (component is Container) component.components.forEach { setBackground(it, color) }
    }

    private fun initialize() {
        if (disposed || browser != null || !initializing.compareAndSet(false, true)) return
        state.error = null
        state.isLoading = true
        state.progress = null
        Thread({
            try {
                val app = DesktopBrowserRuntime.getApplication()
                publish {
                    try {
                        attachBrowser(app)
                    } catch (error: Throwable) {
                        org.slf4j.LoggerFactory.getLogger("resource-browser").error("Cannot attach desktop browser", error)
                        client?.dispose()
                        client = null
                        browser = null
                        container?.removeAll()
                        state.isLoading = false
                        state.error = error.message ?: error.javaClass.simpleName
                    } finally {
                        initializing.set(false)
                    }
                }
            } catch (error: Throwable) {
                org.slf4j.LoggerFactory.getLogger("resource-browser").error("Cannot initialize desktop browser runtime", error)
                initializing.set(false)
                publish {
                    state.isLoading = false
                    state.error = error.message ?: error.javaClass.simpleName
                }
            }
        }, "resource-browser-init").apply { isDaemon = true; start() }
    }

    private fun attachBrowser(app: CefApp) {
        val cefClient = app.createClient()
        client = cefClient
        cefClient.addDialogHandler(object : CefDialogHandler {
            override fun onFileDialog(browser: CefBrowser, mode: CefDialogHandler.FileDialogMode,
                title: String?, defaultFilePath: String?, acceptFilters: Vector<String>,
                acceptExtensions: Vector<String>, acceptDescriptions: Vector<String>, callback: CefFileDialogCallback,
            ): Boolean {
                if (mode != CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN &&
                    mode != CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE) return false
                val request = BrowserFileUploadRequest(
                    acceptFilters.toList(), mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE,
                    uploadCache,
                    browse = { pending ->
                        val chooser = JFileChooser().apply {
                            if (!title.isNullOrBlank()) dialogTitle = title
                            isMultiSelectionEnabled = pending.multiple
                            val extensions = acceptExtensions.flatMap { it.split(';', ',') }
                                .map { it.trim().removePrefix("*.").removePrefix(".") }
                                .filter { it.matches(Regex("[a-zA-Z0-9]+")) }.distinct()
                            if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(
                                acceptDescriptions.joinToString().ifBlank { acceptFilters.joinToString() }, *extensions.toTypedArray())
                        }
                        if (chooser.showOpenDialog(container) == JFileChooser.APPROVE_OPTION) {
                            pending.selectFiles(if (pending.multiple) chooser.selectedFiles.toList() else listOf(chooser.selectedFile))
                        } else pending.cancel()
                    },
                    deliver = { files ->
                        if (files.isNullOrEmpty()) callback.Cancel()
                        else callback.Continue(Vector(files.map { it.absolutePath }))
                    },
                )
                request.onFinished = { uploads.remove(request) }
                uploads.add(request)
                SwingUtilities.invokeLater {
                    if (disposed) request.cancel() else state.offerFileUpload(request)
                }
                return true
            }
        })
        cefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest,
                userGesture: Boolean, isRedirect: Boolean,
            ): Boolean {
                if (!frame.isMain || request.method != "GET" || browserResourceFileName(request.url) == null) return false
                val url = request.url
                publish { browser.startDownload(url) }
                return true
            }
        })
        cefClient.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem,
                suggestedName: String, callback: CefBeforeDownloadCallback,
            ): Boolean {
                val fileName = browserResourceFileName(item.url, item.contentDisposition, suggestedName) ?: return false
                val id = item.id
                val expected = item.totalBytes.takeIf { it > 0 }
                val transfer = CefModTransfer(callback).also { it.control = downloadCallbacks[id] }
                downloads[id] = transfer
                publish { state.offerModDownload(BrowserModDownload(fileName, expected, transfer)) }
                if (disposed) transfer.cancel()
                // 持有 callback，直到确认才 Continue；不使用 Chromium 默认保存目录或保存对话框。
                return true
            }

            override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) {
                val id = item.id
                if (!item.isValid) return
                val terminal = item.isComplete || item.isCanceled || !item.isInProgress
                if (!terminal) downloadCallbacks[id] = callback
                downloads[id]?.let { transfer ->
                    transfer.update(callback, item.receivedBytes, item.totalBytes.takeIf { it > 0 },
                        item.isComplete, item.isCanceled, item.isInProgress, item.fullPath)
                    if (terminal) downloads.remove(id)
                }
                if (terminal) downloadCallbacks.remove(id)
            }
        })
        cefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                if (frame.isMain) publish { state.url = url }
            }
        })
        cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                browser: CefBrowser,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) = publish {
                state.isLoading = isLoading
                state.canGoBack = canGoBack
                state.canGoForward = canGoForward
                state.progress = if (isLoading) null else 1f
            }

            override fun onLoadStart(
                browser: CefBrowser,
                frame: CefFrame,
                transitionType: CefRequest.TransitionType,
            ) {
                if (frame.isMain) publish {
                    mainFrameLoaded = false
                    presentationGeneration++
                    pendingPresentation = null
                    state.error = null
                    state.pageStarted()
                }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && httpStatusCode >= 400) {
                    publish { state.error = "HTTP $httpStatusCode" }
                } else if (frame.isMain && httpStatusCode < 400 && frame.url != "about:blank") {
                    val loadedUrl = frame.url
                    publish {
                        mainFrameLoaded = true
                        if (httpStatusCode in 200..299) state.pageLoaded(loadedUrl)
                        showContentIfReady()
                    }
                }
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String,
            ) {
                if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    publish {
                        state.isLoading = false
                        state.error = "$errorText ($failedUrl)"
                    }
                }
            }
        })
        cefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                browser: CefBrowser,
                frame: CefFrame,
                targetUrl: String,
                targetFrameName: String,
            ): Boolean {
                if (targetUrl.isNotBlank()) publish { browser.loadURL(targetUrl) }
                return true
            }

            // 关闭网页时由启动器处理生命周期，避免 Chromium 关闭 Swing 主窗口。
            override fun doClose(browser: CefBrowser): Boolean = true
        })
        val cefBrowser = cefClient.createBrowser(state.initialUrl, false, false)
        browser = cefBrowser
        setBackground(cefBrowser.uiComponent, background)
        container?.add(cefBrowser.uiComponent, BorderLayout.CENTER)
        container?.revalidate()
        container?.repaint()
        cefBrowser.createImmediately()
        showContentIfReady()
    }

    private fun publish(action: () -> Unit) {
        SwingUtilities.invokeLater { if (!disposed) action() }
    }

    override fun goBack() { browser?.goBack() }
    override fun goForward() { browser?.goForward() }
    override fun loadUrl(url: String) {
        mainFrameLoaded = false
        presentationGeneration++
        pendingPresentation = null
        state.isLoading = true
        browser?.loadURL(url)
    }
    override fun submitForumHandoff(trustedUrl: String, handoff: String?) {
        browser?.takeIf { it.url == trustedUrl }?.executeJavaScript(forumHandoffScript(trustedUrl, handoff), trustedUrl, 0)
    }

    override fun reload() {
        mainFrameLoaded = false
        presentationGeneration++
        pendingPresentation = null
        state.isLoading = true
        browser?.reload() ?: initialize()
    }

    fun dispose() {
        disposed = true
        uploads.toList().forEach { it.cancel() }
        Thread({ uploadCache.close() }, "browser-upload-cleanup").apply { isDaemon = true; start() }
        downloads.values.forEach { it.cancel() }
        browser?.close(true)
        client?.dispose()
        browser = null
        client = null
        container?.removeAll()
        container = null
        presentationGeneration++
        pendingPresentation = null
    }
}

/** Chromium 自己传输文件，保留 Cookie、POST 下载和 blob 下载的浏览器会话。 */
private class CefModTransfer(private val before: CefBeforeDownloadCallback) : BrowserModTransfer {
    private val completion = CompletableDeferred<Unit>()
    @Volatile var control: CefDownloadItemCallback? = null
    @Volatile private var cancelled = false
    @Volatile private var target: File? = null
    @Volatile private var progress: ((Long, Long?) -> Unit)? = null

    override suspend fun download(target: File, onProgress: (Long, Long?) -> Unit) {
        if (cancelled) throw CancellationException("Download cancelled")
        this.target = target
        progress = onProgress
        before.Continue(target.absolutePath, false)
        try { completion.await() }
        catch (cancelled: CancellationException) {
            cancel()
            // 等 Chromium 停写再由共享层清理 .part；不把取消后的文件提交为模组。
            withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { completion.await() } } }
            throw cancelled
        } finally { progress = null }
    }

    override fun cancel() {
        cancelled = true
        control?.cancel()
    }

    fun update(callback: CefDownloadItemCallback, bytes: Long, total: Long?, complete: Boolean,
        nativeCancelled: Boolean, inProgress: Boolean, path: String,
    ) {
        control = callback
        if (cancelled && !complete) callback.cancel()
        progress?.invoke(bytes, total)
        when {
            complete -> {
                if (cancelled) completion.completeExceptionally(CancellationException("Download cancelled"))
                else if (target?.absoluteFile != File(path).absoluteFile) {
                    completion.completeExceptionally(IOException("Unexpected download destination"))
                } else completion.complete(Unit)
            }
            nativeCancelled -> completion.completeExceptionally(CancellationException("Download cancelled"))
            !inProgress -> completion.completeExceptionally(IOException("Mod download interrupted"))
        }
    }
}
