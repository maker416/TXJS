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
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserModTransfer
import io.github.rwpp.net.browser.browserModFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Composable
actual fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val browser = remember(state) { DesktopBrowserController(state) }
    DisposableEffect(browser) {
        state.controller = browser
        onDispose {
            state.controller = null
            browser.dispose()
        }
    }
    SwingPanel(factory = browser::createPanel, modifier = modifier)
}

/** Chromium 的安装/初始化在后台执行；整个应用共用 runtime，每次打开页面创建独立 client。 */
private object DesktopBrowserRuntime {
    private var app: CefApp? = null

    @Synchronized
    fun getApplication(): CefApp {
        app?.let { return it }
        val profileRoot = File(System.getProperty("user.home"), ".rwjs/browser")
        val builder = CefAppBuilder().apply {
            setInstallDir(File(profileRoot, "runtime-${CefBuildInfo.fromClasspath().releaseTag}"))
            cefSettings.windowless_rendering_enabled = false
            cefSettings.cache_path = File(profileRoot, "cache").absolutePath
            cefSettings.persist_session_cookies = true
            // runtime 随桌面包提供，首次打开仅解压，无需在线下载。
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
) : EmbeddedBrowserController {
    @Volatile
    private var disposed = false
    private val initializing = AtomicBoolean(false)
    private var container: JPanel? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private val downloads = ConcurrentHashMap<Int, CefModTransfer>()
    private val downloadCallbacks = ConcurrentHashMap<Int, CefDownloadItemCallback>()

    fun createPanel(): JPanel = JPanel(BorderLayout()).also {
        container = it
        initialize()
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
        cefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest,
                userGesture: Boolean, isRedirect: Boolean,
            ): Boolean {
                if (!frame.isMain || request.method != "GET" || browserModFileName(request.url) == null) return false
                val url = request.url
                publish { browser.startDownload(url) }
                return true
            }
        })
        cefClient.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem,
                suggestedName: String, callback: CefBeforeDownloadCallback,
            ): Boolean {
                val fileName = browserModFileName(item.url, item.contentDisposition, suggestedName) ?: return false
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
                if (frame.isMain) publish { state.error = null }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && httpStatusCode >= 400) {
                    publish { state.error = "HTTP $httpStatusCode" }
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
        container?.add(cefBrowser.uiComponent, BorderLayout.CENTER)
        container?.revalidate()
        container?.repaint()
        cefBrowser.createImmediately()
    }

    private fun publish(action: () -> Unit) {
        SwingUtilities.invokeLater { if (!disposed) action() }
    }

    override fun goBack() { browser?.goBack() }
    override fun goForward() { browser?.goForward() }
    override fun loadUrl(url: String) { browser?.loadURL(url) }

    override fun reload() {
        browser?.reload() ?: initialize()
    }

    fun dispose() {
        disposed = true
        downloads.values.forEach { it.cancel() }
        browser?.close(true)
        client?.dispose()
        browser = null
        client = null
        container?.removeAll()
        container = null
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
