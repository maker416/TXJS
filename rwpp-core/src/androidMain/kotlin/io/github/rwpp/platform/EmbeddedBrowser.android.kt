/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rwpp.appKoin
import io.github.rwpp.net.Net
import io.github.rwpp.net.BrowserModHttpTransfer
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.browserModFileName
import io.github.rwpp.net.browser.BrowserModUploadCache
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uploadCache = remember(context, state) { BrowserModUploadCache(context.cacheDir) }
    var pickerRequest by remember(state) { mutableStateOf<BrowserFileUploadRequest?>(null) }
    var pickerCallback by remember(state) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pickerRequest
        val callback = pickerCallback
        pickerRequest = null
        pickerCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?.filter { it.scheme == "content" && it.authority != "${context.packageName}.fileprovider" }
            ?.toTypedArray()?.takeIf { it.isNotEmpty() }
        pending?.finishNative { callback?.onReceiveValue(uris) }
    }
    val webView = remember(context, state) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(false)
            fun offerMod(downloadUrl: String, name: String, agent: String?, length: Long?) {
                state.isLoading = false
                state.offerModDownload(BrowserModDownload(name, length,
                    BrowserModHttpTransfer(appKoin.get<Net>().client, downloadUrl, agent, url) { target ->
                        CookieManager.getInstance().getCookie(target)
                    }))
            }
            setDownloadListener { downloadUrl, userAgent, disposition, _, length ->
                browserModFileName(downloadUrl, disposition)?.let { name ->
                    offerMod(downloadUrl, name, userAgent, length.takeIf { it > 0 })
                }
            }

            fun updateNavigation() {
                state.url = url ?: state.initialUrl
                state.canGoBack = canGoBack()
                state.canGoForward = canGoForward()
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || request.method != "GET") return false
                    val downloadUrl = request.url.toString()
                    val name = browserModFileName(downloadUrl) ?: return false
                    offerMod(downloadUrl, name, settings.userAgentString, null)
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val name = browserModFileName(url) ?: return false
                    offerMod(url, name, settings.userAgentString, null)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    state.pageStarted()
                    state.error = null
                    state.isLoading = true
                    state.progress = 0f
                    updateNavigation()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    state.isLoading = false
                    state.progress = 1f
                    updateNavigation()
                    state.pageLoaded(view.url.orEmpty())
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    updateNavigation()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        state.error = error.description.toString()
                        state.isLoading = false
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame) {
                        state.error = "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}"
                        state.isLoading = false
                    }
                }
            }
            // 不启用多窗口：普通链接和 target=_blank 链接均留在当前浏览器中。
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    if (pickerRequest != null || params.mode == FileChooserParams.MODE_SAVE) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    val request = BrowserFileUploadRequest(
                        params.acceptTypes.toList(), params.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                        uploadCache,
                        browse = { pending ->
                            pickerRequest = pending
                            pickerCallback = callback
                            try { filePicker.launch(params.createIntent()) }
                            catch (_: android.content.ActivityNotFoundException) {
                                pickerRequest = null
                                pickerCallback = null
                                pending.cancel()
                                state.error = io.github.rwpp.i18n.readI18n("browser.filePickerUnavailable")
                            }
                        },
                        deliver = { files ->
                            callback.onReceiveValue(files?.map {
                                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                            }?.toTypedArray())
                        },
                    )
                    state.offerFileUpload(request)
                    return true
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    state.progress = newProgress / 100f
                }
            }
        }
    }
    DisposableEffect(webView) {
        state.controller = object : EmbeddedBrowserController {
            override fun goBack() { webView.goBack() }
            override fun goForward() { webView.goForward() }
            override fun loadUrl(url: String) { webView.loadUrl(url) }
            override fun reload() { webView.reload() }
            override fun executeJavaScript(script: String, trustedUrl: String) {
                if (webView.url == trustedUrl) webView.evaluateJavascript(script, null)
            }
        }
        webView.loadUrl(state.initialUrl)
        onDispose {
            state.fileUpload?.cancel()
            pickerRequest?.cancel()
            pickerRequest = null
            pickerCallback = null
            Thread({ uploadCache.close() }, "browser-upload-cleanup").apply { isDaemon = true; start() }
            state.controller = null
            webView.stopLoading()
            webView.setDownloadListener(null)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    DisposableEffect(webView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}
