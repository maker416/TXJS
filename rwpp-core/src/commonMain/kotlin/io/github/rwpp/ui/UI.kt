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
import androidx.compose.foundation.layout.Spacer
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
    var showSinglePlayerView by mutableStateOf(false)

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
        singlePlayer: () -> Unit,
        settings: () -> Unit,
        mods: () -> Unit,
        extension: () -> Unit,
        resourceBrowser: () -> Unit,
        openSourceInfo: () -> Unit
    ) {
        val windowManager = LocalWindowManager.current
        val buttonSpacing = when (windowManager) {
            WindowManager.Small -> 8.dp
            WindowManager.Middle -> 10.dp
            WindowManager.Large -> 12.dp
        }
        val gridSpacing = when (windowManager) {
            WindowManager.Small -> 6.dp
            WindowManager.Middle -> 8.dp
            WindowManager.Large -> 10.dp
        }
        val versionStyle = when (windowManager) {
            WindowManager.Small, WindowManager.Middle -> MaterialTheme.typography.bodySmall
            WindowManager.Large -> MaterialTheme.typography.bodyLarge
        }
        val extraItems: List<@Composable () -> Unit> = extraMenuList.map { menu ->
            { MainMenuAction(menu.title, menu.onClick) }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val titleAreaHeight = maxHeight * 0.32f
            val titleWidth = when (windowManager) {
                WindowManager.Small -> maxWidth * 0.70f
                WindowManager.Middle -> maxWidth * 0.60f
                WindowManager.Large -> maxWidth * 0.52f
            }
            val contentMaxWidth = when (windowManager) {
                WindowManager.Small -> maxWidth * 0.85f
                WindowManager.Middle -> maxWidth * 0.75f
                WindowManager.Large -> maxWidth * 0.65f
            }.coerceAtMost(520.dp)

            Text(
                "$coreVersion (app $projectVersion)",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 10.dp),
                style = versionStyle,
                color = Color.White
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Title area
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

                // Menu buttons container - reduced spacing to fit all buttons
                Column(
                    modifier = Modifier.widthIn(max = contentMaxWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(buttonSpacing)
                ) {
                    // Full-width primary buttons
                    MainMenuAction(
                        readI18n("menu.singlePlayerGame"),
                        onClick = singlePlayer,
                        isFullWidth = true
                    )
                    MainMenuAction(
                        readI18n("menu.multiplayer"),
                        onClick = multiplayer,
                        isFullWidth = true
                    )

                    // Two-column grid for secondary buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                    ) {
                        MainMenuAction(
                            readI18n("menu.settings"),
                            onClick = settings,
                            modifier = Modifier.weight(1f)
                        )
                        MainMenuAction(
                            readI18n("menu.mods"),
                            onClick = mods,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                    ) {
                        MainMenuAction(
                            readI18n("browser.resourceBrowser"),
                            onClick = resourceBrowser,
                            modifier = Modifier.weight(1f)
                        )
                        MainMenuAction(
                            readI18n("menu.openSourceInfo"),
                            onClick = openSourceInfo,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Extra items in grid (if any)
                    extraItems.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                        ) {
                            rowItems.forEach { item ->
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    item()
                                }
                            }
                            // Fill empty slot if odd number
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Exit button positioned at bottom-left corner
            val appContext = koinInject<AppContext>()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 12.dp)
            ) {
                ExitButton(
                    onClick = { appContext.exit() },
                    label = readI18n("menu.exit")
                )
            }
        }
    }

    @Composable
    private fun ExitButton(
        onClick: () -> Unit,
        label: String
    ) {
        val windowManager = LocalWindowManager.current
        val cornerRadius = when (windowManager) {
            WindowManager.Small -> 10.dp
            WindowManager.Middle -> 12.dp
            WindowManager.Large -> 14.dp
        }
        
        Surface(
            color = Color(0x3A1A1A1A),
            contentColor = Color.White,
            shape = RoundedCornerShape(cornerRadius),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    private fun MainMenuAction(
        content: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        isFullWidth: Boolean = false,
        isEmphasized: Boolean = true
    ) {
        val windowManager = LocalWindowManager.current
        val buttonHeight = when (windowManager) {
            WindowManager.Small -> 36.dp
            WindowManager.Middle -> 40.dp
            WindowManager.Large -> 44.dp
        }
        val cornerRadius = when (windowManager) {
            WindowManager.Small -> 16.dp
            WindowManager.Middle -> 18.dp
            WindowManager.Large -> 20.dp
        }
        
        // Aesthetic improvements: more transparent background, subtler border
        val backgroundColor = if (isEmphasized) {
            Color(0x5A1A1A1A)
        } else {
            Color(0x4A2A2A2A)
        }
        val borderColor = if (isEmphasized) {
            Color.White.copy(alpha = 0.55f)
        } else {
            Color.White.copy(alpha = 0.35f)
        }
        
        Surface(
            color = backgroundColor,
            contentColor = Color.White,
            shape = RoundedCornerShape(cornerRadius),
            border = BorderStroke(1.5.dp, borderColor),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = if (isFullWidth) {
                modifier.fillMaxWidth()
            } else {
                modifier
            },
            onClick = onClick
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = buttonHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

