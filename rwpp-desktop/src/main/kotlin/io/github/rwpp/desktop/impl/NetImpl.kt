/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.DEFAULT_ROOM_LIST_API_URLS
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.core.Initialization
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.desktop.asGamePacket
import io.github.rwpp.impl.BaseNetImpl
import io.github.rwpp.net.Net
import io.github.rwpp.net.Packet
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.awt.Desktop
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

@Single(binds = [Net::class, Initialization::class])
class NetImpl : BaseNetImpl() {
    override fun init() {
        super.init()
        val prefs = get<MultiplayerPreferences>()
        if (prefs.roomListApiUrls != DEFAULT_ROOM_LIST_API_URLS) {
            prefs.roomListApiUrls = DEFAULT_ROOM_LIST_API_URLS
            get<ConfigIO>().saveConfig(prefs)
        }
    }

    // 桌面端绕过系统代理，避免直连列表服务器时 Connect timed out（curl 可达但 OkHttp 走代理失败）
    override val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    override fun sendPacketToServer(packet: Packet) {
        GameEngine.B().bX.f(packet.asGamePacket())
    }

    override fun sendPacketToClients(packet: Packet) {
        GameEngine.B().bX.g(packet.asGamePacket())
    }

    override fun openUriInBrowser(uri: String) {
        Desktop.getDesktop().browse(URI(uri))
    }
}