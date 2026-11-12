/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.core.Initialization
import io.github.rwpp.core.ListDetectorPlayers
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.account.RoomInvite
import io.github.rwpp.rwpp_core.generated.resources.title
import io.github.rwpp.ui.UI.showQuestion
import io.github.rwpp.ui.UI.showWarning
import io.github.rwpp.ui.color.getTeamColor
import kotlinx.coroutines.Dispatchers

object UI : Initialization, IUserInterface {
    internal var backgroundTransparency by mutableStateOf(appKoin.get<Settings>().backgroundTransparency)
    internal var selectedColorSchemeName by mutableStateOf(appKoin.get<Settings>().selectedTheme ?: "RWPP")
    var question by mutableStateOf<Question?>(null)
        internal set
    var warning by mutableStateOf<Warning?>(null)
        internal set
    var dialogWidget by mutableStateOf<Widget?>(null)
        internal set
    var chatMessages by mutableStateOf(AnnotatedString(""))
        internal set

    // ---- 页面级导航开关：单一事实来源是 launcherPage（见 LauncherPage.kt），以下为兼容代理 ----
    var showMissionView by pageBinding(LauncherPage.Mission)
    var showMultiplayerView by pageBinding(LauncherPage.Multiplayer)
    var showReplayView by pageBinding(LauncherPage.Replay)
    var showSettingsView by pageBinding(LauncherPage.Settings)
    var showModsView by pageBinding(LauncherPage.Mods)
    /**
     * 打开「模组与地图」页时的初始 tab；消费一次后清空。
     * 供 Android `.tmx` 深链等场景直达地图管理。
     */
    var pendingModsMapsTab by mutableStateOf<ModsMapsTab?>(null)
    var showRoomView by pageBinding(LauncherPage.Room)
    var showExtensionView by pageBinding(LauncherPage.Extensions)
    /** 主题美术包管理页。 */
    var showThemesView by pageBinding(LauncherPage.Themes)
    var showResourceBrowser by pageBinding(LauncherPage.ResourceBrowser)
    var showOpenSourceInfoView by pageBinding(LauncherPage.OpenSourceInfo)
    var showSinglePlayerView by pageBinding(LauncherPage.SinglePlayer)
    var showSurvivalView by pageBinding(LauncherPage.Survival)
    // ---- 上层叠加页：可合法叠加在房间页之上（房间内就地登录/发私信），不参与页面级互斥 ----
    var showAccountView by mutableStateOf(false)
    /** 好友主页（微信式：左列表右聊天）。独立于用户主页的顶级页面。 */
    var showFriendsView by mutableStateOf(false)

    /**
     * 好友聊天中房间邀请卡片点击「立即加入」后暂存的邀请，
     * 由 MultiplayerView 消费（置回 null）并触发既有 LoadingView/directJoinServer 加入链路。
     */
    var pendingInviteJoin by mutableStateOf<RoomInvite?>(null)

    /**
     * 收到好友房间邀请时屏幕右侧弹出的悬浮卡片（10 秒倒计时自动忽略）。
     * 由 FriendsSession.refreshLists 检测未读邀请私信后置位；
     * 等待房间/对局内不弹（房间内无法接受其他房间的邀请）。
     */
    var incomingInviteNotification by mutableStateOf<RoomInviteNotification?>(null)

    /**
     * 模组重载期间堆耗尽（OutOfMemory）标志。置位后本进程内不再允许模组重载——
     * 堆已被部分解析结果占满，反复重载只会崩溃。进程重启自然清除。
     */
    var modReloadMemoryExhausted: Boolean
        get() = io.github.rwpp.game.GameStartSafety.memoryExhausted
        set(value) { if (value) io.github.rwpp.game.GameStartSafety.markMemoryExhausted() }

    var roomSelectedPlayer by mutableStateOf<Player?>(null)
        internal set
    var receivingNetworkDialogTitle by mutableStateOf("")
    var showNetworkDialog by mutableStateOf(false)
    // ---- 房主 MOD 同步下载弹窗的结构化进度状态 ----
    /** 当前正在下载的 mod 名（空串表示尚未开始/已结束）。 */
    var receivingModName by mutableStateOf("")
    /** 当前 mod 下载进度 0f..1f（已收字节 / 总字节）。 */
    var receivingModProgress by mutableStateOf(0f)
    /** 当前 mod 已收字节数（用于展示，如 "1.20MB"）。 */
    var receivingModReceivedBytes by mutableStateOf(0L)
    /** 当前 mod 总字节数。 */
    var receivingModTotalBytes by mutableStateOf(0L)
    /** 本次房间要求下载的 mod 总数（用于 "(2/5)" 徽章）。 */
    var receivingModTotalCount by mutableStateOf(0)
    /** 已下载完成的 mod 数（含当前正在下载的那个，从 1 开始）。 */
    var receivingModDoneCount by mutableStateOf(0)
    /** 当前模组下载瞬时速率（字节/秒），供房间内进度条展示；0 表示尚未采样。 */
    var receivingModSpeedBps by mutableStateOf(0L)
    /** 主界面公告/更新入口共享的最新版本信息。 */
    var latestVersionProfile by mutableStateOf<LatestVersionProfile?>(null)
    var UiProvider: UIProvider = UIProvider()

    private val relayRegex = Regex("""R\d+""")

    override fun showWarning(reason: String, isKicked: Boolean) {
        synchronized(UI) {
            warning = Warning(reason, isKicked)
        }
    }

    private var connectionFailureReceiver: ((ConnectionFailure) -> Boolean)? = null

    fun captureConnectionFailures(receiver: (ConnectionFailure) -> Boolean): () -> Unit {
        synchronized(UI) { connectionFailureReceiver = receiver }
        return { synchronized(UI) { if (connectionFailureReceiver === receiver) connectionFailureReceiver = null } }
    }

    fun showConnectionFailure(failure: ConnectionFailure, isKicked: Boolean = false) {
        synchronized(UI) {
            if (connectionFailureReceiver?.invoke(failure) == true) return
            warning = Warning(failure.details, isKicked, failure)
        }
    }

    override fun showQuestion(title: String, message: String, callback: (String?) -> Unit) {
        synchronized(UI) {
            question = Question(title, message, callback)
        }
    }

    override fun showDialog(widget: Widget) {
        synchronized(UI) {
            dialogWidget = widget
        }
    }

    fun onReceiveChatMessage(sender: String, message: String, color: Int, senderPlayer: Player? = null) {
        if (ListDetectorPlayers.hideChatMessage(sender, message)) return
        // 房间邀请策略控制消息：仅房主发送才生效——应用到本地并抑制显示（不进聊天记录）。
        // 注入层按昵称匹配发送者，同名成员可冒名房主，故额外要求该昵称在房内唯一；
        // 不信任的控制消息按普通聊天显示，让尝试暴露出来
        val trustedHostControl = senderPlayer != null && senderPlayer.isRoomHost &&
            runCatching {
                appKoin.get<Game>().gameRoom.getPlayers().count { it.name == sender } == 1
            }.getOrDefault(false)
        if (trustedHostControl && RoomInvitePolicy.handleControlMessage(message)) return
        val configIO = appKoin.get<ConfigIO>()
        synchronized(UI) {
            chatMessages =
                buildAnnotatedString {
                    if (sender == "RELAY_CN-ADMIN") {
                        val result = relayRegex.find(message)?.value

                        if (!result.isNullOrBlank()) {
                            configIO.setGameConfig("lastNetworkIP", result)
                        }
                    }

                    if (sender.isNotBlank()) {
                        withStyle(
                            style = SpanStyle(
                                color = Player.getTeamColor(color),
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append("$sender: ")
                        }
                    }

                    withStyle(style = SpanStyle(color = Color.White)) {
                        append(message)
                    }

                    append("\n")
                } + chatMessages
        }

    }

    /**
     * @see [showQuestion]
     */
    class Question(val title: String, val message: String, val callback: (String?) -> Unit)

    /**
     * @see [showWarning]
     */
    class Warning(
        val reason: String,
        val isKicked: Boolean = false,
        val connectionFailure: ConnectionFailure? = null,
    )

    override fun init() {
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(Dispatchers.Main.immediate, priority = EventPriority.MONITOR) {
            chatMessages = AnnotatedString("")
        }
    }
}

open class UIProvider {
    val extraMenuList = mutableListOf<Menu>()

    @Composable
    open fun MainMenu(
        multiplayer: () -> Unit,
        singlePlayer: () -> Unit,
        settings: () -> Unit,
        mods: () -> Unit,
        extension: () -> Unit,
        resourceBrowser: () -> Unit,
        esports: () -> Unit,
        openSourceInfo: () -> Unit,
        account: () -> Unit,
        friends: () -> Unit,
    ) {
        LauncherMainMenu(
            multiplayer = multiplayer, singlePlayer = singlePlayer,
            settings = settings, mods = mods,
            resources = { navigateTo(LauncherPage.ResourceCenter) },
            extension = extension, resourceBrowser = resourceBrowser, esports = esports,
            openSourceInfo = openSourceInfo, account = account, friends = friends,
        )
    }

    class Menu(
        val title: String,
        val iconModel: Any?,
        val onClick: () -> Unit,
    )
}
