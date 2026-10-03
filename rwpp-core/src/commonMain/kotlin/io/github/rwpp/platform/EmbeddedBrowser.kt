/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.rwpp.net.browser.BrowserModDownload
import com.eclipsesource.json.Json

@Stable
class EmbeddedBrowserState(val initialUrl: String, private val homeUrl: String = initialUrl) {
    var url by mutableStateOf(initialUrl)
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set
    var isLoading by mutableStateOf(true)
        internal set
    var progress by mutableStateOf<Float?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    internal var controller: EmbeddedBrowserController? = null
        set(value) {
            field = value
            if (value != null) loadedUrl?.let(::pageLoaded)
        }
    var clientLoginError by mutableStateOf<String?>(null)
        private set
    var clientAuthenticating by mutableStateOf(false)
        internal set
    internal var retryClientLogin: (() -> Unit)? = null
    private var loadedUrl: String? = null
    private var pendingClientLogin: Pair<String, String?>? = null
    private var submittedClientPage: String? = null

    internal fun pageStarted() { loadedUrl = null; submittedClientPage = null; fileUpload?.cancel() }

    internal fun beginClientLogin() {
        clientLoginError = null
        error = null
        pendingClientLogin = null
        clientAuthenticating = true
        isLoading = true
    }

    internal fun failClientLogin(message: String) {
        // Page initialization/loading may clear navigation errors after native preparation failed.
        clientLoginError = message
        isLoading = false
    }

    internal fun pageLoaded(currentUrl: String) {
        loadedUrl = currentUrl
        val pending = pendingClientLogin
        if (pending != null && currentUrl == pending.first && error == null) {
            val activeController = controller ?: return
            // Both native platforms recheck the current main-frame URL before executing.
            val page = Json.value(pending.first).toString()
            val handoff = pending.second?.let { Json.value(it).toString() } ?: "null"
            activeController.executeJavaScript("if(location.href===$page&&typeof window.rwForumClient==='function'){window.rwForumClient($handoff);}", pending.first)
            pendingClientLogin = null
            submittedClientPage = currentUrl
        } else if (pending == null && currentUrl.trimEnd('/') == homeUrl.trimEnd('/')) {
            clientAuthenticating = false
        }
    }

    internal fun submitClientLogin(bootstrap: String, handoff: String?) {
        require(handoff == null || handoff.matches(Regex("[a-f0-9]{64}")))
        clientAuthenticating = true
        pendingClientLogin = bootstrap to handoff
        if (submittedClientPage == bootstrap) {
            loadedUrl = null
            controller?.loadUrl(bootstrap)
        } else if (loadedUrl == bootstrap) pageLoaded(bootstrap)
        else if (url != bootstrap) controller?.loadUrl(bootstrap)
    }
    var modDownload by mutableStateOf<BrowserModDownload?>(null)
        internal set

    internal fun offerModDownload(download: BrowserModDownload) {
        if (modDownload != null) download.transfer.cancel() else modDownload = download
    }

    internal var fileUpload by mutableStateOf<BrowserFileUploadRequest?>(null)
        private set

    internal fun offerFileUpload(request: BrowserFileUploadRequest) {
        fileUpload?.cancel()
        val previous = request.onFinished
        request.onFinished = { previous(); if (fileUpload === request) fileUpload = null }
        fileUpload = request
    }

    fun goBack(): Boolean {
        if (clientAuthenticating) return false
        val activeController = controller ?: return false
        if (!canGoBack) return false
        activeController.goBack()
        return true
    }

    fun goForward() {
        if (!clientAuthenticating && canGoForward) controller?.goForward()
    }

    fun goHome() {
        if (!clientAuthenticating) controller?.loadUrl(homeUrl)
    }

    fun reload() {
        if ((error != null || clientLoginError != null) && retryClientLogin != null) retryClientLogin?.invoke()
        else if (!clientAuthenticating) controller?.reload()
    }

    internal fun openAsGuest() { clientLoginError = null; error = null; submitClientLogin(initialUrl, null) }
}

internal interface EmbeddedBrowserController {
    fun goBack()
    fun goForward()
    fun loadUrl(url: String)
    fun reload()
    fun executeJavaScript(script: String, trustedUrl: String)
}

@Composable
expect fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier = Modifier)
