/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.core.Initialization
import io.github.rwpp.coreVersion
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.game.units.GameUnit
import io.github.rwpp.game.units.MovementType
import io.github.rwpp.game.world.World
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.projectVersion
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.title
import io.github.rwpp.ui.UI.showQuestion
import io.github.rwpp.ui.UI.showWarning
import io.github.rwpp.ui.color.getTeamColor
import io.github.rwpp.widget.WindowManager
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

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

    var showMissionView by mutableStateOf(false)
    var showMultiplayerView by mutableStateOf(false)
    var showReplayView by mutableStateOf(false)
    var showSettingsView by mutableStateOf(false)
    var showModsView by mutableStateOf(false)
    var showRoomView by mutableStateOf(false)
    var showExtensionView by mutableStateOf(false)
    var showResourceBrowser by mutableStateOf(false)
    var showOpenSourceInfoView by mutableStateOf(false)

    private var pendingAutoPublishQRoom = false

    var roomSelectedPlayer by mutableStateOf<Player?>(null)
        internal set
    var receivingNetworkDialogTitle by mutableStateOf("")
    var showNetworkDialog by mutableStateOf(false)
    var UiProvider: UIProvider = UIProvider()

    private val relayRegex = Regex("""R\d+""")

    fun requestAutoPublishQRoom() {
        synchronized(UI) {
            pendingAutoPublishQRoom = true
        }
    }

    fun clearAutoPublishQRoom() {
        synchronized(UI) {
            pendingAutoPublishQRoom = false
        }
    }

    fun consumeAutoPublishQRoom(): Boolean =
        synchronized(UI) {
            val pending = pendingAutoPublishQRoom
            pendingAutoPublishQRoom = false
            pending
        }

    override fun showWarning(reason: String, isKicked: Boolean) {
        synchronized(UI) {
            warning = Warning(reason, isKicked)
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

    fun onReceiveChatMessage(sender: String,  message: String, color: Int) {
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
    class Warning(val reason: String, val isKicked: Boolean = false)

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
        mission: () -> Unit,
        skirmish: () -> Unit,
        settings: () -> Unit,
        mods: () -> Unit,
        sandbox: () -> Unit,
        extension: () -> Unit,
        replay: () -> Unit,
        resourceBrowser: () -> Unit,
        openSourceInfo: () -> Unit
    ) {
        val windowManager = LocalWindowManager.current
        val standardMenuItems: List<@Composable () -> Unit> = listOf(
            { MainMenuAction(readI18n("menu.mission"), mission) },
            { MainMenuAction(readI18n("menus.singlePlayer.skirmish", I18nType.RW), skirmish) },
            { MainMenuAction(readI18n("menu.multiplayer"), multiplayer) },
            { MainMenuAction(readI18n("menu.mods"), mods) },
            { MainMenuAction(readI18n("menu.sandbox"), sandbox) },
            { MainMenuAction(readI18n("menu.settings"), settings) },
            { MainMenuAction(readI18n("browser.resourceBrowser"), resourceBrowser) },
            { MainMenuAction(readI18n("menu.replay"), replay) },
            { MainMenuAction(readI18n("menu.openSourceInfo"), openSourceInfo) },
            {
                val appContext = koinInject<AppContext>()
                MainMenuAction(readI18n("menu.exit")) { appContext.exit() }
            },
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val titleAreaHeight = maxHeight * 0.39f
            val titleWidth = when (windowManager) {
                WindowManager.Small -> maxWidth * 0.64f
                WindowManager.Middle -> maxWidth * 0.56f
                WindowManager.Large -> maxWidth * 0.50f
            }
            val buttonSpacing = when (windowManager) {
                WindowManager.Small -> 8.dp
                WindowManager.Middle -> 9.dp
                WindowManager.Large -> 10.dp
            }
            val menuScrollState = rememberLazyListState()
            val versionStyle = when (windowManager) {
                WindowManager.Small, WindowManager.Middle -> MaterialTheme.typography.bodySmall
                WindowManager.Large -> MaterialTheme.typography.bodyLarge
            }
            val extraItems: List<@Composable () -> Unit> = extraMenuList.map { menu ->
                { MainMenuAction(menu.title, menu.onClick) }
            }
            val allItems = standardMenuItems + extraItems

            Text(
                "$projectVersion (core $coreVersion)",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 10.dp),
                style = versionStyle,
                color = Color.White
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = menuScrollState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(buttonSpacing),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(titleAreaHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.title),
                            contentDescription = "Menu",
                            modifier = Modifier
                                .width(titleWidth)
                                .height(titleAreaHeight),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                items(allItems.size) { index ->
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        allItems[index]()
                    }
                }
            }
        }
    }

    @Composable
    private fun MainMenuAction(
        content: String,
        onClick: () -> Unit
    ) {
        val windowManager = LocalWindowManager.current
        val buttonHeight = when (windowManager) {
            WindowManager.Small -> 34.dp
            WindowManager.Middle -> 38.dp
            WindowManager.Large -> 42.dp
        }
        val buttonWidthModifier = when (windowManager) {
            WindowManager.Small -> Modifier.widthIn(min = 88.dp, max = 138.dp)
            WindowManager.Middle -> Modifier.widthIn(min = 96.dp, max = 152.dp)
            WindowManager.Large -> Modifier.widthIn(min = 104.dp, max = 168.dp)
        }
        Surface(
            color = Color(0x7A2A2A2A),
            contentColor = Color.White,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.72f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = buttonWidthModifier,
            onClick = onClick
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = buttonHeight)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    content,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    open fun InGameComposeContent() {
        val settings = koinInject<Settings>()
        if (settings.enableQuickSelectMenu) {
            TinyQuickSelectMenu()
        }
    }
    @Composable
    fun TinyQuickSelectMenu() {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val scaleFactor = when {
                maxHeight > 1200.dp -> 2.5f
                maxHeight > 600.dp -> 1.5f
                else -> 1.0f
            }

            Column(
                modifier = Modifier
                    .padding(8.dp * scaleFactor)
                    .offset(x = if (scaleFactor == 1.0f) (-40).dp else 0.dp)
                    .scale(scaleFactor)
                    .width(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "快速选择",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                val game = koinInject<Game>()
                val world = game.world
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MiniSelectButton("全", Color(0xFF9C27B0)) {
                        selectUnitByMovementType(
                            world,
                            game,
                            null
                        )
                    }
                    MiniSelectButton("建", Color(0xFF795548)) {
                        selectUnitByMovementType(
                            world,
                            game,
                            setOf(MovementType.NONE)
                        )
                    }
                    MiniSelectButton("海", Color(0xFF1E88E5)) {
                        selectUnitByMovementType(world, game,
                            setOf(
                                MovementType.WATER,
                                MovementType.OVER_CLIFF_WATER,
                                MovementType.HOVER
                            )
                        )
                    }
                    MiniSelectButton("空", Color(0xFF4FC3F7)) {
                        selectUnitByMovementType(world, game, setOf(MovementType.AIR))
                    }
                    MiniSelectButton("陆", Color(0xFF43A047)) {
                        selectUnitByMovementType(world, game,
                            setOf(
                                MovementType.LAND,
                                MovementType.HOVER,
                                MovementType.OVER_CLIFF,
                                MovementType.OVER_CLIFF_WATER
                            )
                        )
                    }
                }
            }
        }
    }

    private fun selectUnitByMovementType(world: World, game: Game, typeSet: Set<MovementType>?) {
        world.clearSelectedUnits()
        world.getAllObject().forEach {
            if (it is GameUnit &&
                !it.isDead &&
                !it.type.isBuilder &&
                (typeSet?.contains(it.type.movementType) ?:
                (it.type.movementType != MovementType.NONE)) &&
                it.player == game.gameRoom.localPlayer
            ) world.selectUnit(it)
        }
    }

    @Composable
    fun MiniSelectButton(label: String, color: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.7f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    class Menu(
        val title: String,
        val iconModel: Any?,
        val onClick: () -> Unit
    )
}

