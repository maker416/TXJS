/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp

import io.github.rwpp.command.CommandHandler
import io.github.rwpp.command.CommandHandler.Command
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.rwpp_core_api.BuildConfig
import org.koin.core.Koin
import org.slf4j.Logger
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The version of the project.
 */
const val projectVersion = "v" + BuildConfig.VERSION

/**
 * The version of the game core.
 */
const val coreVersion = "v1.15"

/**
 * The protocol version.
 * The clients which have different protocol version can not join to each other
 */
const val protocolVersion = 5

/**
 * 1.15 -> 176
 */
const val gameVersion: Int = 176

/**
 * Koin init flag.
 */
var koinInit = false

/**
 * global koin module.
 */
lateinit var appKoin: Koin

/** 开房欢迎语，随界面语言切换（{0}=projectVersion, {1}=coreVersion）。 */
fun welcomeMessage(): String = readI18n("multiplayer.welcomeMessage", io.github.rwpp.i18n.I18nType.RWPP, projectVersion, coreVersion)

const val packageName = "io.github.rwpp"

/**
 * global logger.
 */
lateinit var logger: Logger

/**
 * global command handler.
 */
val commands = CommandHandler("/").apply {
    register<Player>("help", "[page]", "Lists all commands.") { args, player ->
        val room = appKoin.get<Game>().gameRoom
        if (args.isNotEmpty() && args[0].toIntOrNull() == null) {
            room.sendMessageToPlayer(player, "RWPP", "'page' must be a number.")
            return@register
        }
        val commandsPerPage = 6
        var page = if (args.isNotEmpty()) args[0].toInt() else 1
        val pages = ceil(commandList.size.toDouble() / commandsPerPage).roundToInt()

        page--

        if (page !in 0..<pages) {
            room.sendMessageToPlayer(player, "RWPP", "'page' must be a number between 1 and $pages.")
            return@register
        }

        val result = StringBuilder()
        result.append("--- Commands Page ${(page + 1)}/${pages} ---\n")

        for (i in commandsPerPage * page..<(commandsPerPage * (page + 1)).coerceAtMost(commandList.size)) {
            val command: Command = commandList[i]
            result.append("- /").append(command.text).append(" ").append(command.paramText)
                .append(" - ").append(command.description).append("\n")

        }
        room.sendMessageToPlayer(player, "RWPP", result.toString())
    }
}


val extensionPath by lazy {
    appKoin.get<AppContext>().externalStoragePath("extension/")
}

val resourceOutputDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("resource_generated/")
}

val resOutputDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("resource_generated/res/")
}

val mapDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("maps/")
}

/**
 * 引擎实际扫描的自定义地图目录。
 *
 * - Desktop：`mods/maps/`（与房间选图前缀、启动清理一致）
 * - Android：与 [mapDir] 相同（`rustedWarfare/maps/`）
 *
 * 导入 / 管理 / 资源下载应写入此目录，否则 Desktop 上写入 [mapDir] 后列表扫不到。
 */
val customMapDir by lazy {
    val ctx = appKoin.get<AppContext>()
    if (ctx.isDesktop()) {
        ctx.externalStoragePath("mods/maps/")
    } else {
        mapDir
    }
}

val modDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("units/")
}

/**
 * 对局存档目录（`.rwsave`）。
 *
 * - Desktop：`<游戏根目录>/saves/`（与引擎相对路径 `saves` 一致）
 * - Android：`rustedWarfare/saves/`（对应引擎虚拟路径 `/SD/rustedWarfare/saves/`）
 */
val saveDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("saves/")
}

/**
 * 应用私有模组目录：用于存放网络同步过来的房主模组。
 *
 * Android 上位于 getExternalFilesDir/units/，非 root 设备上文件管理器无法访问，
 * 卸载 App 时随应用一起删除；桌面端回退到与 [modDir] 相同的路径。
 *
 * 引擎的模组扫描会同时加载 [modDir]（外部公共）和本目录（应用私有）两个位置的模组，
 * 见 [io.github.rwpp.android.impl.inject.FileLoaderInject]。
 */
val internalModDir by lazy {
    appKoin.get<AppContext>().internalStoragePath("units/")
}

val generatedLibDir by lazy {
    appKoin.get<AppContext>().generatedLibPath()
}