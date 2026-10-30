/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** 稳定的原因标识；文案仅在显示时从 connectionError 独立资源表读取。 */
enum class ConnectionFailureReason(val key: String) {
    UNKNOWN("unknown"), SERVER_REJECTED("serverRejected"), TIMEOUT("timeout"),
    INVALID_ADDRESS("invalidAddress"), INVALID_PORT("invalidPort"), DNS("dns"),
    REFUSED("refused"), UNREACHABLE("unreachable"), MISSING_MODS("missingMods"),
    MISSING_UNITS("missingUnits"), DATA_MISMATCH("dataMismatch"), SYNC_FAILED("syncFailed"),
    MANIFEST_CHANGED("manifestChanged"), CHECKSUM_MISMATCH("checksumMismatch"),
    RELOAD_FAILED("reloadFailed"), RECONNECT_FAILED("reconnectFailed"),
    NO_ROOM("noRoom"), NOT_LOBBY("notLobby"), PREPARING("preparing"), NOT_FOUND("notFound"),
    BUSY("busy"), BAD_REQUEST("badRequest"), INTERNAL_ERROR("internalError"),
    USERNAME_TOO_LONG("usernameTooLong"), USERNAME_TOO_SHORT("usernameTooShort"),
    BANNED("banned"), CLIENT_OUTDATED("clientOutdated"), SERVER_OUTDATED("serverOutdated"),
    CORE_UNITS_MISMATCH("coreUnitsMismatch"), CLIENT_INTEGRITY("clientIntegrity"),
    ROOM_LOCKED("roomLocked"), GAME_STARTED("gameStarted"), ROOM_FULL("roomFull"),
    PASSWORD_REQUIRED("passwordRequired"), WRONG_PASSWORD("wrongPassword"),
    MASTER_QUERY_FAILED("masterQueryFailed"), KICKED("kicked"),
}

enum class ConnectionFailureStage { CONNECT, SYNC_MANIFEST, SYNC_DOWNLOAD, SYNC_APPLY, SYNC_RECONNECT, SERVER }

data class ConnectionFailure(
    val reason: ConnectionFailureReason,
    val stage: ConnectionFailureStage = ConnectionFailureStage.CONNECT,
    val details: String = "",
    val parameters: List<String> = emptyList(),
)

data class ConnectionFailureText(val title: String, val explanation: String, val suggestion: String)

fun ConnectionFailure.localized(): ConnectionFailureText {
    val path = "connectionError.${reason.key}"
    val args = parameters.toTypedArray()
    val explanation = readI18n("$path.explanation", arg = args)
    val context = if (parameters.isEmpty()) "" else "\n" + when {
        reason == ConnectionFailureReason.TIMEOUT && parameters.size == 3 ->
            readI18n("connectionError.timeoutTarget", arg = args)
        else -> readI18n("connectionError.context", arg = arrayOf(parameters.joinToString(", ")))
    }
    return ConnectionFailureText(
        readI18n("$path.title", arg = args),
        explanation + context,
        readI18n("$path.suggestion", arg = args),
    )
}

/** cause 链先排除取消，再优先使用异常类型；不把服务器任意原文当作分类关键词。 */
fun connectionFailure(
    error: Throwable,
    stage: ConnectionFailureStage = ConnectionFailureStage.CONNECT,
): ConnectionFailure? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.toList()
    if (causes.any { it is CancellationException }) return null
    val details = causes.map { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
        .distinct().joinToString("\n")
    causes.forEach { cause ->
        val reason = when (cause) {
            is DirectModSyncServerException -> ConnectionFailureReason.valueOf(cause.code.name)
            is SocketTimeoutException, is TimeoutException -> ConnectionFailureReason.TIMEOUT
            is UnknownHostException, is UnresolvedAddressException -> ConnectionFailureReason.DNS
            is NoRouteToHostException -> ConnectionFailureReason.UNREACHABLE
            is ConnectException -> when (cause.message) {
                "Connection refused", "Connection refused: connect" -> ConnectionFailureReason.REFUSED
                "Network is unreachable", "Network is unreachable: connect", "No route to host" -> ConnectionFailureReason.UNREACHABLE
                "Connection timed out", "Connection timed out: connect" -> ConnectionFailureReason.TIMEOUT
                else -> null
            }
            else -> null
        }
        if (reason != null) return ConnectionFailure(reason, stage, details)
    }
    causes.forEach { cause ->
        val classified = connectionFailure(cause.message.orEmpty(), stage)
        if (classified.reason != fallbackReason(stage)) return classified.copy(details = details)
    }
    return ConnectionFailure(fallbackReason(stage), stage, details)
}

fun connectionFailure(
    message: String,
    stage: ConnectionFailureStage = ConnectionFailureStage.CONNECT,
): ConnectionFailure {
    var native = message.trim()
    // 只剥离引擎已知包装；任意用户/服务器字符串不会通过 contains 猜测为网络错误。
    native = native.removePrefix("Connection failed: ")
        .removePrefix("Failed to connect to host - ").removePrefix("Kicked: ")
    val timeout = connectTimeoutMessage.matchEntire(message.trim())
    val reason = if (timeout != null) ConnectionFailureReason.TIMEOUT else when (native) {
        "Connection refused", "Connection refused: connect" -> ConnectionFailureReason.REFUSED
        "Connection timed out", "Connection timed out: connect", "Read timed out", "connect timed out" -> ConnectionFailureReason.TIMEOUT
        "Network is unreachable", "Network is unreachable: connect", "No route to host", "You do not have a network connection" -> ConnectionFailureReason.UNREACHABLE
        "DNS lookup failed, check your internet connection" -> ConnectionFailureReason.DNS
        "Bad server connect string" -> ConnectionFailureReason.INVALID_ADDRESS
        "Your username is too long" -> ConnectionFailureReason.USERNAME_TOO_LONG
        "Your username is too short" -> ConnectionFailureReason.USERNAME_TOO_SHORT
        "Temporarily banned due to recent kick" -> ConnectionFailureReason.BANNED
        "Your core units are different to the server's core units. Game can not be synchronized" -> ConnectionFailureReason.CORE_UNITS_MISMATCH
        "Your 'Rusted Warfare' client is different to the server. Game can not be synchronized." -> ConnectionFailureReason.CLIENT_INTEGRITY
        "Room is locked. New players cannot join this server." -> ConnectionFailureReason.ROOM_LOCKED
        "A game has already been started on this server" -> ConnectionFailureReason.GAME_STARTED
        "No free slots on server", "No free spectator slots" -> ConnectionFailureReason.ROOM_FULL
        "This server requires a password but no password was provided", "Missing password", "No password entered" -> ConnectionFailureReason.PASSWORD_REQUIRED
        "Incorrect password.", "Incorrect password", "Wrong password" -> ConnectionFailureReason.WRONG_PASSWORD
        "Failed to get server connection data - unknown", "Unexpected header from master server",
        "Unexpected return from master server", "Unexpected end of response", "Request missing required fields" -> ConnectionFailureReason.MASTER_QUERY_FAILED
        "Kicked", "Kicked by host" -> ConnectionFailureReason.KICKED
        "Missing 1 mod." -> ConnectionFailureReason.MISSING_MODS
        "Different mod data.", "Local mod set does not match direct manifest after reload" -> ConnectionFailureReason.DATA_MISMATCH
        "Direct room manifest changed during download" -> ConnectionFailureReason.MANIFEST_CHANGED
        "Direct room is not in lobby" -> ConnectionFailureReason.NOT_LOBBY
        else -> fixedTemplateReason(native) ?: fallbackReason(stage)
    }
    val parameters = timeout?.groupValues?.drop(1) ?: when (reason) {
        ConnectionFailureReason.CLIENT_OUTDATED -> listOf(native.substringAfter("please update to v"))
        ConnectionFailureReason.SERVER_OUTDATED -> listOf(native.substringAfter("Server is on: v"))
        ConnectionFailureReason.MISSING_UNITS -> listOf(native.removePrefix("Missing unit:").trim()).filter(String::isNotEmpty)
        ConnectionFailureReason.MISSING_MODS -> when {
            native.startsWith("Missing mod: '") -> listOf(native.removePrefix("Missing mod: '").substringBefore("'"))
            else -> listOf(native.removePrefix("Missing ").substringBefore(" mod"))
        }
        ConnectionFailureReason.DATA_MISMATCH -> if (native.startsWith("Different mod data for: "))
            listOf(native.removePrefix("Different mod data for: ")) else emptyList()
        else -> emptyList()
    }
    return ConnectionFailure(reason, stage, message, parameters)
}

private fun fallbackReason(stage: ConnectionFailureStage): ConnectionFailureReason = when (stage) {
    ConnectionFailureStage.CONNECT -> ConnectionFailureReason.UNKNOWN
    ConnectionFailureStage.SERVER -> ConnectionFailureReason.SERVER_REJECTED
    ConnectionFailureStage.SYNC_APPLY -> ConnectionFailureReason.RELOAD_FAILED
    ConnectionFailureStage.SYNC_RECONNECT -> ConnectionFailureReason.RECONNECT_FAILED
    ConnectionFailureStage.SYNC_MANIFEST, ConnectionFailureStage.SYNC_DOWNLOAD -> ConnectionFailureReason.SYNC_FAILED
}

/** 与原生固定前缀/后缀一致的插值模板，不按诸如 timeout、mod 等泛关键词匹配。 */
private fun fixedTemplateReason(message: String): ConnectionFailureReason? = when {
    Regex("Game is out of date, please update to v\\S+").matches(message) -> ConnectionFailureReason.CLIENT_OUTDATED
    Regex("Your client is newer then the server. Server is on: v\\S+").matches(message) -> ConnectionFailureReason.SERVER_OUTDATED
    Regex("Connection banned for [0-9]+(?:\\.[0-9]+)? seconds").matches(message) -> ConnectionFailureReason.BANNED
    Regex("Missing [0-9]+ mods\\.").matches(message) || message.startsWith("Missing mod: '") -> ConnectionFailureReason.MISSING_MODS
    message.startsWith("Missing unit:") -> ConnectionFailureReason.MISSING_UNITS
    message.startsWith("Different mod data for: ") -> ConnectionFailureReason.DATA_MISMATCH
    Regex("port out of range:-?[0-9]+").matches(message) || Regex("Port number out of range: -?[0-9]+").matches(message) -> ConnectionFailureReason.INVALID_PORT
    Regex("Direct unit checksum mismatch \\(local -?[0-9]+ / host -?[0-9]+\\)").matches(message) -> ConnectionFailureReason.CHECKSUM_MISMATCH
    message == "Direct room is not ready: preparing" -> ConnectionFailureReason.PREPARING
    else -> null
}

private val connectTimeoutMessage = Regex(
    """(?:Connection failed: )?Failed to connect to host - failed to connect to /?(\S+) \(port (\d+)\)(?: from /?\S+ \(port \d+\))? after (\d+)ms\.?""",
    RegexOption.IGNORE_CASE,
)

fun joinFailureMessage(message: String): String {
    val match = connectTimeoutMessage.matchEntire(message) ?: return message
    val (host, port, timeoutMs) = match.destructured
    return readI18n("multiplayer.joinConnectTimeout", arg = arrayOf(host, port, timeoutMs))
}
