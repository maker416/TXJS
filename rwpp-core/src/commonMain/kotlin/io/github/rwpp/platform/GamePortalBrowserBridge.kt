/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import io.github.rwpp.net.games.GamePortalException
import io.github.rwpp.net.games.onlineGamePortalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** 业务层持有账号快照；浏览器层仅传结构化请求，不接触 UAS Token 或开发者 Secret。 */
interface GamePortalBridge {
    val origin: String
    val generation: String
    fun isActive(): Boolean
    fun onDocumentChanged() = Unit
    suspend fun request(method: String, params: JsonObject): JsonObject
}

class GamePortalBridgeException(val code: String, override val message: String) : Exception(message)

/** 网页和客户端目录使用同一套来源规则，协议、主机和端口都必须匹配。 */
internal fun gamePortalOrigin(value: String): String? = onlineGamePortalOrigin(value)

/** 每次真实主文档导航作废旧请求；SPA 的 fragment/history 不额外建立授权文档。 */
internal class GamePortalBrowserDispatcher(private val binding: GamePortalBridge) {
    private val document = AtomicLong(0)
    private val expectedOrigin = requireNotNull(gamePortalOrigin(binding.origin))
    private val bindingGeneration = binding.generation
    @Volatile private var currentOrigin: String? = null
    @Volatile private var revoked = false
    private val acceptedIDs = HashSet<String>()

    fun beginDocument(url: String): Long {
        synchronized(acceptedIDs) { acceptedIDs.clear() }
        currentOrigin = gamePortalOrigin(url)
        val epoch = document.incrementAndGet()
        binding.onDocumentChanged()
        return epoch
    }
    fun epoch(): Long = document.get()
    fun trusted(url: String?, isMain: Boolean, epoch: Long = epoch()): Boolean =
        !revoked && isMain && epoch == document.get() && currentOrigin == expectedOrigin &&
            url != null && gamePortalOrigin(url) == expectedOrigin && binding.generation == bindingGeneration && binding.isActive()

    fun revoke() { revoked = true; currentOrigin = null; document.incrementAndGet(); synchronized(acceptedIDs) { acceptedIDs.clear() } }

    suspend fun dispatch(raw: String, url: String, isMain: Boolean, epoch: Long): String? {
        if (!trusted(url, isMain, epoch) || raw.length > 16384) return null
        val input = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val id = (input["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (!id.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) return null
        fun failure(code: String, message: String): String = buildJsonObject {
            put("v", 1); put("id", id); put("ok", false)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }.toString()
        val method = (input["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return failure("invalid_request", "Invalid bridge request")
        val params = input["params"] as? JsonObject ?: return failure("invalid_request", "Invalid bridge parameters")
        if ((input["v"] as? JsonPrimitive)?.intOrNull != 1 || input.keys.any { it !in setOf("v", "id", "method", "params") }) return failure("invalid_request", "Invalid bridge protocol")
        val allowedFields = when (method) {
            "getPlayer", "getServers" -> emptySet()
            "authorize" -> setOf("nonce")
            "joinServer" -> setOf("server_id", "request_id")
            "cancelJoin" -> setOf("request_id")
            else -> return failure("unsupported_method", "Unsupported game portal method")
        }
        if (params.keys.any { it !in allowedFields }) return failure("invalid_request", "Unexpected bridge parameter")
        if (method == "authorize") {
            val nonce = (params["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
            if (!nonce.matches(Regex("[A-Za-z0-9_-]{32,128}"))) return failure("invalid_nonce", "Invalid website nonce")
        }
        val accepted = synchronized(acceptedIDs) { acceptedIDs.size < 1024 && acceptedIDs.add(id) }
        if (!accepted) return failure("duplicate_request", "Duplicate or excessive bridge request")
        val reply = try {
            val result = binding.request(method, params)
            buildJsonObject { put("v", 1); put("id", id); put("ok", true); put("result", result) }.toString()
        } catch (cancelled: CancellationException) { return null }
        catch (error: GamePortalException) { failure(error.code.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) } ?: "request_failed", "The game portal request could not be completed") }
        catch (error: GamePortalBridgeException) { failure(error.code.take(80), error.message.take(256)) }
        catch (_: Exception) { failure("request_failed", "The game portal request could not be completed") }
        return reply.takeIf { trusted(url, isMain, epoch) }
    }
}

internal object GamePortalBrowserSDK {
    val source: String by lazy {
        requireNotNull(GamePortalBrowserSDK::class.java.getResourceAsStream("/game-portal-sdk.js")) { "Missing game portal SDK" }
            .bufferedReader().use { it.readText() }
    }
}
