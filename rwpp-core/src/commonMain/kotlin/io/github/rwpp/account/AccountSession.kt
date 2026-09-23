/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.resolveAccountApiUrl
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.game.Game
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountErrorCode
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.EmailCodePurpose
import io.github.rwpp.net.account.PointBalance
import io.github.rwpp.net.account.PointLedgersResponse
import io.github.rwpp.net.account.PresenceSettings
import io.github.rwpp.net.account.RegisterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * RWJS 统一账号会话，对接 `/api/v1` 用户通道。
 *
 * 登录态以服务端 JWT 为准，写入 [AccountPreferences]，不碰 BBS cookie。
 * [networkEnabled] 为 false 时只走预览快照（截图 / 无引擎宿主），不发 HTTP。
 */
object AccountSession : KoinComponent {
    var loggedIn: Boolean by mutableStateOf(false)
        private set

    var user: AccountUser? by mutableStateOf(null)
        private set

    var token: String by mutableStateOf("")
        private set

    val username: String get() = user?.username.orEmpty()

    val displayName: String get() = user?.nickname?.ifBlank { username } ?: username

    var lastUsername: String by mutableStateOf("")
        private set

    var profileError: String by mutableStateOf("")
        private set

    var restoring: Boolean by mutableStateOf(false)
        private set

    /** 自己的在线状态可见性设置（文档 6.23），登录后由账号页拉取。 */
    var presenceSettings: PresenceSettings? by mutableStateOf(null)
        private set

    /** 积分余额列表（文档 6.9），登录后由账号页拉取。 */
    var points: List<PointBalance> by mutableStateOf(emptyList())
        private set

    /** 头像版本号：自己上传 / 删除头像后自增，驱动 Coil 缓存失效。 */
    var avatarVersion: Int by mutableIntStateOf(0)
        private set

    /** 截图 / 组合测试关闭真请求。 */
    var networkEnabled: Boolean = true

    @Volatile
    internal var clientOverride: AccountApiClient? = null

    @Volatile
    internal var httpOverride: OkHttpClient? = null

    /** Debug / 截图宿主在未走全局 Koin 时注入客户端。 */
    fun bindClient(client: AccountApiClient, http: OkHttpClient? = null) {
        clientOverride = client
        httpOverride = http
    }

    fun client(): AccountApiClient {
        clientOverride?.let { return it }
        val prefs = prefsOrNull()
        return AccountApiClient(
            baseUrl = resolveAccountApiUrl(prefs?.apiUrl.orEmpty()),
            appKey = resolveAccountAppKey(prefs?.appKey.orEmpty()),
            http = httpOverride ?: get<Net>().client,
        )
    }

    fun requireToken(): String {
        val t = token
        if (t.isBlank()) throw AccountApiException(AccountErrorCode.UNAUTHORIZED, "missing bearer token", 401)
        return t
    }

    suspend fun restoreIfNeeded() {
        if (!networkEnabled || loggedIn) return
        val prefs = prefsOrNull() ?: return
        lastUsername = prefs.lastUsername
        val saved = prefs.token
        if (saved.isBlank()) return
        restoring = true
        profileError = ""
        try {
            val me = withContext(Dispatchers.IO) { client().me(saved) }
            applySession(saved, me, persist = false)
        } catch (e: AccountApiException) {
            if (e.code == AccountErrorCode.UNAUTHORIZED ||
                e.code == AccountErrorCode.USER_DISABLED ||
                e.code == AccountErrorCode.NOT_FOUND
            ) {
                clearSession(persist = true)
            } else {
                logger.warn("恢复账号会话失败：{} {}", e.code, e.message)
            }
        } catch (e: Exception) {
            logger.warn("恢复账号会话失败：{}", e.message)
        } finally {
            restoring = false
        }
    }

    suspend fun login(username: String, password: String) {
        val resp = withContext(Dispatchers.IO) { client().login(username, password) }
        applySession(resp.token, resp.user, persist = true)
    }

    suspend fun register(req: RegisterRequest, password: String) {
        withContext(Dispatchers.IO) {
            client().register(req)
            val resp = client().login(req.username, password)
            applySession(resp.token, resp.user, persist = true)
        }
    }

    suspend fun sendRegisterCode(email: String) {
        withContext(Dispatchers.IO) {
            client().sendEmailCode(email, EmailCodePurpose.REGISTER)
        }
    }

    suspend fun sendResetCode(email: String) {
        withContext(Dispatchers.IO) {
            client().sendEmailCode(email, EmailCodePurpose.RESET_PASSWORD)
        }
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        withContext(Dispatchers.IO) {
            client().resetPassword(email, code, newPassword)
        }
    }

    suspend fun refreshProfile() {
        val me = withContext(Dispatchers.IO) { client().me(requireToken()) }
        applySession(token, me, persist = true)
        profileError = ""
    }

    suspend fun changeNickname(nickname: String) {
        val me = withContext(Dispatchers.IO) { client().changeNickname(requireToken(), nickname) }
        applySession(token, me, persist = true)
    }

    suspend fun refreshPresenceSettings() {
        val settings = withContext(Dispatchers.IO) { client().getPresenceSettings(requireToken()) }
        presenceSettings = settings
    }

    suspend fun updatePresenceSettings(hideFromStrangers: Boolean?, hideFromFriends: Boolean?) {
        val settings = withContext(Dispatchers.IO) {
            client().updatePresenceSettings(requireToken(), hideFromStrangers, hideFromFriends)
        }
        presenceSettings = settings
    }

    suspend fun refreshPoints() {
        points = withContext(Dispatchers.IO) { client().listPoints(requireToken()) }
    }

    /** 积分流水按需分页拉取（文档 6.10），不常驻会话状态。 */
    suspend fun fetchPointLedgers(
        page: Int? = null,
        pageSize: Int? = null,
        pointTypeId: Long? = null,
    ): PointLedgersResponse = withContext(Dispatchers.IO) {
        client().listPointLedgers(requireToken(), page, pageSize, pointTypeId)
    }

    suspend fun sendChangeEmailCode(email: String) {
        withContext(Dispatchers.IO) {
            client().sendChangeEmailCode(requireToken(), email)
        }
    }

    /**
     * 确认更换绑定邮箱。成功后服务端使该身份所有 Token 立即失效（文档 6.7），
     * 本地同步清会话，调用方应引导重新登录。
     */
    suspend fun changeEmail(email: String, code: String) {
        withContext(Dispatchers.IO) {
            client().changeEmail(requireToken(), email, code)
        }
        clearSession(persist = true)
        FriendsSession.clear()
    }

    /** 上传头像（≤1MiB 的 JPEG/PNG），成功后刷新 hasAvatar 并抬 [avatarVersion] 使缓存失效。 */
    suspend fun uploadAvatar(bytes: ByteArray, fileName: String, contentType: String) {
        val hasAvatar = withContext(Dispatchers.IO) {
            client().uploadAvatar(requireToken(), bytes, fileName, contentType)
        }
        user = user?.copy(hasAvatar = hasAvatar)
        avatarVersion++
    }

    suspend fun deleteAvatar() {
        val hasAvatar = withContext(Dispatchers.IO) { client().deleteAvatar(requireToken()) }
        user = user?.copy(hasAvatar = hasAvatar)
        avatarVersion++
    }

    suspend fun logout() {
        val current = token
        try {
            if (networkEnabled && current.isNotBlank()) {
                withContext(Dispatchers.IO) { client().logout(current) }
            }
        } catch (e: Exception) {
            logger.warn("登出请求失败，仍清除本机会话：{}", e.message)
        }
        clearSession(persist = true)
        FriendsSession.clear()
    }

    fun applyPreview(account: AccountUser, previewToken: String = "preview") {
        networkEnabled = false
        applySession(previewToken, account, persist = false)
    }

    fun applyLoggedOutPreview() {
        networkEnabled = false
        clearSession(persist = false)
    }

    fun resetForTests() {
        networkEnabled = true
        clientOverride = null
        httpOverride = null
        clearSession(persist = false)
        lastUsername = ""
        profileError = ""
        restoring = false
    }

    private fun applySession(newToken: String, newUser: AccountUser, persist: Boolean) {
        token = newToken
        user = newUser
        loggedIn = true
        lastUsername = newUser.username
        profileError = ""
        if (persist) {
            prefsOrNull()?.let { prefs ->
                prefs.token = newToken
                prefs.lastUsername = newUser.username
                savePrefs(prefs)
            }
        }
        syncMultiplayerName()
    }

    /** 登录态下多人房间昵称固定绑定为账号昵称（登录 / 改昵称 / 恢复会话后即时生效）。 */
    private fun syncMultiplayerName() {
        val name = displayName
        if (name.isBlank()) return
        runCatching { get<ConfigIO>().setGameConfig("lastNetworkPlayerName", name) }
        runCatching { get<Game>().setUserName(name) }
    }

    private fun clearSession(persist: Boolean) {
        token = ""
        user = null
        loggedIn = false
        profileError = ""
        presenceSettings = null
        points = emptyList()
        if (persist) {
            prefsOrNull()?.let { prefs ->
                prefs.token = ""
                savePrefs(prefs)
            }
        }
    }

    private fun prefsOrNull(): AccountPreferences? =
        runCatching { get<AccountPreferences>() }.getOrNull()

    private fun savePrefs(prefs: AccountPreferences) {
        runCatching { get<ConfigIO>().saveConfig(prefs) }
    }
}
