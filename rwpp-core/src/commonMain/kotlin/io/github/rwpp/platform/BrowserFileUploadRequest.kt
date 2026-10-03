/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import io.github.rwpp.net.browser.BrowserModUploadCache
import io.github.rwpp.net.browser.BrowserUploadAccept
import io.github.rwpp.net.browser.browserModUploadName
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal class BrowserFileUploadRequest(
    acceptTypes: List<String>,
    val multiple: Boolean,
    val cache: BrowserModUploadCache,
    private val browse: (BrowserFileUploadRequest) -> Unit,
    private val deliver: (List<File>?) -> Unit,
) {
    val accept = BrowserUploadAccept(acceptTypes)
    private val completed = AtomicBoolean(false)
    private val browsing = AtomicBoolean(false)
    internal var onFinished: () -> Unit = {}
    fun uploadName(source: File, modTitle: String? = null): String? {
        val name = browserModUploadName(source, modTitle)
        if (accept.accepts(name)) return name
        if (source.isDirectory || source.extension.equals("rwmod", true)) {
            val zipName = name.substringBeforeLast('.') + ".zip"
            if (accept.accepts(zipName)) return zipName
        }
        return null
    }
    fun browseFiles() { if (!completed.get() && browsing.compareAndSet(false, true)) browse(this) }
    fun checkActive() { if (completed.get()) throw CancellationException("File selection cancelled") }
    fun selectFiles(files: List<File>) { finishNative { deliver(if (multiple) files else files.take(1)) } }
    fun cancel() { finishNative { deliver(null) } }
    // 原生选择器结果、页面导航和浏览器销毁可能同时到达；每个原生回调只完成一次。
    fun finishNative(action: () -> Unit) {
        if (completed.compareAndSet(false, true)) {
            try { action() } finally { onFinished() }
        }
    }
}
