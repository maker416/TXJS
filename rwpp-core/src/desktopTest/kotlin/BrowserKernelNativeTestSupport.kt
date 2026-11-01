/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import com.sun.net.httpserver.HttpServer
import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.net.browser.*
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress

/** Real JCEF tests use the exact exported package over HTTP, never an upstream download. */
internal fun prepareDesktopKernelForTest() {
    val environment = DesktopKernelEnvironment.value
    if (BrowserKernelRuntime.directory(environment.requirement.id) != null) return
    val manager = BrowserKernelManager(environment.root, OkHttpClient())
    val local = manager.installed(environment.requirement)
    if (local != null) { BrowserKernelRuntime.bind(environment.requirement.id, local); return }
    val assets = File(requireNotNull(System.getProperty("rwjs.test.kernelAssets")))
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            val file = File(assets, name)
            if (name.contains('/') || !file.isFile) { exchange.sendResponseHeaders(404, -1); exchange.close() }
            else {
                exchange.sendResponseHeaders(200, file.length())
                exchange.responseBody.use { output -> file.inputStream().use { it.copyTo(output) } }
                exchange.close()
            }
        }
        start()
    }
    try {
        val kernel = manager.install(environment.requirement, BrowserKernelSource { _, name, _ -> "http://127.0.0.1:${server.address.port}/$name" }, UpdateDownloadSession())
        BrowserKernelRuntime.bind(environment.requirement.id, kernel)
    } finally { server.stop(0) }
}
