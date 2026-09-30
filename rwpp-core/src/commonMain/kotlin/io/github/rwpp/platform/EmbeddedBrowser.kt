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

@Stable
class EmbeddedBrowserState(val initialUrl: String) {
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

    fun goBack(): Boolean {
        val activeController = controller ?: return false
        if (!canGoBack) return false
        activeController.goBack()
        return true
    }

    fun goForward() {
        if (canGoForward) controller?.goForward()
    }

    fun goHome() {
        controller?.loadUrl(initialUrl)
    }

    fun reload() {
        controller?.reload()
    }
}

internal interface EmbeddedBrowserController {
    fun goBack()
    fun goForward()
    fun loadUrl(url: String)
    fun reload()
}

@Composable
expect fun EmbeddedBrowser(state: EmbeddedBrowserState, modifier: Modifier = Modifier)
