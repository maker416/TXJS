/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("DuplicatedCode")

package io.github.rwpp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import com.eclipsesource.json.Json
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.config.*
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.event.events.JoinGameEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.data.RoomOption
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.gameVersion
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.logger
import io.github.rwpp.maxModSize
import io.github.rwpp.net.Net
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.sorted
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.readPainterByBytes
import io.github.rwpp.rwpp_core.generated.resources.*
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

/** Values are join order in the list API JSON object (e.g. {"1":"modA","2":"modB"}). */
private fun parseModNamesFromJson(modsJson: String): List<String> {
    if (modsJson.isBlank()) return emptyList()
    return runCatching {
        val obj = Json.parse(modsJson).asObject()
        obj.names()
            .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE })
            .mapNotNull { key ->
                val v = obj[key] ?: return@mapNotNull null
                if (v.isString) v.asString().trim().takeIf { it.isNotEmpty() } else null
            }
    }.getOrElse { emptyList() }
}

private fun mapVanillaVersionDisplay(raw: String, vanillaLabel: String): String =
    if (raw.equals("vanilla", ignoreCase = true)) vanillaLabel else raw

@Composable
private fun FilterSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .35f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun FilterSwitchRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!hint.isNullOrBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
private fun RoomAccessChip(text: String, hasPassword: Boolean) {
    val containerColor =
        if (hasPassword) MaterialTheme.colorScheme.tertiaryContainer
        else Color.Transparent
    val contentColor =
        if (hasPassword) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.outline
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(6.dp),
        border = if (!hasPassword) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun RoomLabelChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** For modded rows, show comma-separated mod names from [RoomDescription.mods] JSON; otherwise [RoomDescription.version] (vanilla → [vanillaLabel]). */
private fun roomListModsColumnText(desc: RoomDescription, vanillaLabel: String): String {
    val looksModded = desc.version.equals("modded", ignoreCase = true)
        || desc.version.contains("mod", ignoreCase = true)
        || desc.mods.isNotBlank()
    if (!looksModded) return mapVanillaVersionDisplay(desc.version, vanillaLabel)
    val names = parseModNamesFromJson(desc.mods)
    val joined = names.joinToString(", ").ifBlank { desc.version }
    return mapVanillaVersionDisplay(joined, vanillaLabel)
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter", "RememberReturnType")
@Composable
fun MultiplayerView(
    onExit: () -> Unit,
    onOpenRoomView: () -> Unit,
) {
    BackHandler(UI.showMultiplayerView, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("multiplayer").broadcastIn()
        }
    }

    val instance = koinInject<MultiplayerPreferences>()
    val settings = koinInject<Settings>()
    val configIO = koinInject<ConfigIO>()
    val game = koinInject<Game>()
    val net = koinInject<Net>()
    val blacklistsInstance = koinInject<Blacklists>()

    val refresh = remember { Channel<Unit>(1) }
    var isRefreshing by remember { mutableStateOf(false) }


    var updateServerConfig by remember { mutableStateOf(false) }
    val allServerData = remember {
        SnapshotStateList<ServerData>().apply {
            addAll(
                instance.allServerConfig.filter { it.type == ServerType.Server }.map { ServerData(it) }
            )
        }
    }
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        allServerData.add(to.index - 1, allServerData.removeAt(from.index - 1))
        instance.allServerConfig.add(to.index - 1, instance.allServerConfig.removeAt(from.index - 1))
    }

    var currentViewList by remember { mutableStateOf<List<RoomDescription>>(listOf()) }
    var throwable by remember { mutableStateOf<Throwable?>(null) }

    var userName by remember {
        val lastName = configIO.getGameConfig<String?>("lastNetworkPlayerName")
        mutableStateOf((lastName ?: "RWPP${(0..999).random()}").also { game.setUserName(it) })
    }

    val blacklists = remember { mutableStateListOf<Blacklist>().apply { addAll(blacklistsInstance.blacklists) } }


    var enableModFilter by remember { mutableStateOf(false) }
    var mapNameFilter by remember { mutableStateOf(instance.mapNameFilter) }
    var creatorNameFilter by remember { mutableStateOf(instance.creatorNameFilter) }
    var playerLimitRange by remember { mutableStateOf(instance.playerLimitRangeFrom..instance.playerLimitRangeTo) }
    var joinServerAddress by rememberSaveable { mutableStateOf(instance.joinServerAddress) }
    val showWelcomeMessage by remember { mutableStateOf(settings.showWelcomeMessage) }
    var roomLabelFilterSelection by remember {
        mutableStateOf(instance.roomLabelFilterSelection.toSet())
    }

    var serverAddress by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }

    var editingServerConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var showServerInfoConfig by remember { mutableStateOf(false) }
    var showRoomListApiSettings by remember { mutableStateOf(false) }

    var selectedRoomDescription by remember { mutableStateOf<RoomDescription?>(null) }
    var showJoinRequestDialog by remember { mutableStateOf(false) }
    var showWelcomeMessageAdmittingDialog by remember { mutableStateOf(showWelcomeMessage == null) }
    val scope = rememberCoroutineScope()

    remember(blacklists.size) {
        blacklistsInstance.blacklists = blacklists.toMutableList()
    }
    remember(mapNameFilter) { instance.mapNameFilter = mapNameFilter }
    remember(creatorNameFilter) { instance.creatorNameFilter = creatorNameFilter }
    remember(joinServerAddress) { instance.joinServerAddress = joinServerAddress }
    remember(playerLimitRange) {
        instance.playerLimitRangeFrom = playerLimitRange.first
        instance.playerLimitRangeTo = playerLimitRange.last
    }
    remember(roomLabelFilterSelection) {
        instance.roomLabelFilterSelection = roomLabelFilterSelection.toList()
    }

    DisposableEffect(Unit) {
        onDispose {
            game.setUserName(userName)
        }
    }

    WelcomeMessageAdmittingDialog(
        showWelcomeMessageAdmittingDialog
    ) {
        showWelcomeMessageAdmittingDialog = false
    }

    JoinServerRequestDialog(showJoinRequestDialog, { showJoinRequestDialog = false },
       selectedRoomDescription, blacklists
    ) { dismiss ->
        serverAddress = selectedRoomDescription!!.addressProvider()
        isConnecting = true
        dismiss()
    }

    LoadingView(isConnecting, onLoaded = { game.cancelJoinServer(); isConnecting = false }, cancellable = true) {
        if(serverAddress.isBlank()) {
            message("That server no longer exists")
            return@LoadingView false
        }

        message("connecting...")

        game.setUserName(userName)
        configIO.setGameConfig("lastNetworkIP", serverAddress)

        val result = game.directJoinServer(serverAddress, selectedRoomDescription?.uuid2, this)
        selectedRoomDescription = null
        if(result.isSuccess) {
            onExit()
            onOpenRoomView()
            JoinGameEvent(serverAddress).broadcastIn()
            true
        } else {
            message(result.exceptionOrNull()!!.message!!)
            false
        }
    }

    @Composable
    fun HostGameDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        onHost: () -> Unit,
    ) = AnimatedAlertDialog(
        visible, onDismissRequest = onDismissRequest
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .width(500.dp)
                .padding(10.dp)
                .autoClearFocus(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(75.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Host Game",
                            modifier = Modifier.padding(5.dp),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            LargeDividingLine { 0.dp }

            val modManager = koinInject<ModManager>()
            var enableMods by remember { mutableStateOf(false) }
            var hostByProtocol by remember { mutableStateOf(false) }
            var transferMod by remember { mutableStateOf(false) }
            val selectedRoomListHostProtocol = "RCN"
            val modSize by remember {
                mutableLongStateOf(
                    modManager.getAllMods()
                        .filter { it.isEnabled }
                        .sumOf { it.getSize() }
                )
            }

            remember(enableMods) {
                if (!enableMods) transferMod = false
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RWCheckbox(
                        enableMods,
                        onCheckedChange = { enableMods = !enableMods },
                        modifier = Modifier.padding(5.dp)
                    )
                    Text(
                        readI18n("multiplayer.enableMods"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }


                Row(verticalAlignment = Alignment.CenterVertically) {
                    RWCheckbox(
                        hostByProtocol,
                        onCheckedChange = { hostByProtocol = !hostByProtocol },
                        modifier = Modifier.padding(5.dp)
                    )
                    Text(
                        readI18n("multiplayer.hostByProtocol", I18nType.RWPP, selectedRoomListHostProtocol),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }


                Row(verticalAlignment = Alignment.CenterVertically) {
                    RWCheckbox(
                        transferMod,
                        onCheckedChange = { transferMod = !transferMod },
                        modifier = Modifier.padding(5.dp),
                        enabled = enableMods && modSize <= maxModSize
                    )
                    Text(
                        "${readI18n("multiplayer.transferMod")} ${
                            if (modSize > maxModSize) "(Disabled for total mods size: ${
                                SizeUtils.byteToMB(
                                    modSize
                                )
                            }MB > ${SizeUtils.byteToMB(maxModSize)}MB)" else ""
                        }",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }

                var password by remember { mutableStateOf("") }
                RWSingleOutlinedTextField(
                    readI18n("multiplayer.password"),
                    password,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
                    enabled = !hostByProtocol,
                ) { password = it }

                var maxPlayer: Int? by remember { mutableStateOf(10) }
                RWSingleOutlinedTextField(
                    readI18n("multiplayer.room.maxPlayer"),
                    maxPlayer?.toString() ?: "",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
                    enabled = hostByProtocol,
                    typeInNumberOnly = true,
                    typeInOnlyInteger = true,
                ) { maxPlayer = it.toIntOrNull()?.coerceAtMost(100) }

                var port by remember { mutableStateOf(configIO.getGameConfig<Int?>("networkPort")) }
                RWSingleOutlinedTextField(
                    readI18n("multiplayer.port"),
                    port?.toString() ?: "",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                    lengthLimitCount = 5,
                    typeInNumberOnly = true,
                    enabled = !hostByProtocol,
                ) {
                    port = it.toIntOrNull()
                    configIO.setGameConfig("networkPort", port ?: 5123)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RWTextButton(readI18n("multiplayer.hostPrivate"), modifier = Modifier.padding(5.dp)) {
                        dismiss()
                        game.gameRoom.option = RoomOption(transferMod, modSize.toInt())
                        if (hostByProtocol) {
                            serverAddress = net.roomListHostProtocol[selectedRoomListHostProtocol]!!(
                                maxPlayer?.coerceAtMost(100)?.coerceAtLeast(10) ?: 10, enableMods, false
                                    )
                            isConnecting = true
                        } else {
                            onHost()
                            game.hostStartWithPasswordAndMods(
                                false, password.ifBlank { null }, enableMods,
                            )
                        }
                    }
                    RWTextButton(readI18n("multiplayer.hostPublic"), modifier = Modifier.padding(5.dp)) {
                        dismiss()
                        game.gameRoom.option = RoomOption(transferMod, modSize.toInt())
                        if (hostByProtocol) {
                            serverAddress = net.roomListHostProtocol[selectedRoomListHostProtocol]!!(
                                maxPlayer?.coerceAtMost(100)?.coerceAtLeast(10) ?: 10,  enableMods,true
                                    )
                            isConnecting = true
                        } else {
                            onHost()
                            game.hostStartWithPasswordAndMods(
                                true, password.ifBlank { null }, enableMods,
                            )
                        }
                    }
                }
            }
        }
    }

    fun LazyListScope.RoomListAnimated(
        descriptions: List<RoomDescription>
    ) {
        items(
            count = descriptions.size,
            key = { descriptions[it].uuid }
        ) { index ->
            val desc = descriptions[index]
            val accentUpperCase = desc.isUpperCase && desc.gameVersion == gameVersion
            val rowFontWeight: FontWeight? = when {
                accentUpperCase -> FontWeight.Black
                desc.isUpperCase -> FontWeight.ExtraBold
                else -> null
            }
            val textColor: Color = when {
                desc.gameVersion != gameVersion -> MaterialTheme.colorScheme.onSurfaceVariant
                desc.isLocal -> Color(255, 127, 80)
                else -> MaterialTheme.colorScheme.onSurface
            }
            val modsText = roomListModsColumnText(desc, readI18n("multiplayer.roomList.vanillaDisplay"))
            val playersText = "${desc.playerCurrentCount ?: "?"}/${desc.playerMaxCount ?: "?"}"
            val accessText =
                if (desc.requiredPassword) readI18n("multiplayer.roomList.accessPassword")
                else readI18n("multiplayer.roomList.accessPublic")

            Card(
                onClick = {
                    selectedRoomDescription = desc
                    showJoinRequestDialog = true
                },
                modifier = Modifier
                    .then(if (koinInject<Settings>().enableAnimations) Modifier.animateItem() else Modifier)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (desc.label.isNotBlank()) {
                            RoomLabelChip(desc.label)
                        }
                        Text(
                            desc.creator,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            fontWeight = rowFontWeight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playersText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.8f),
                            fontWeight = rowFontWeight,
                        )
                        RoomAccessChip(accessText, desc.requiredPassword)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            desc.mapName.removeSuffix(".tmx"),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (modsText.length > 20) {
                            Box(modifier = Modifier.widthIn(max = 130.dp).horizontalScroll(rememberScrollState())) {
                                Text(
                                    modsText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        } else {
                            Text(
                                modsText,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AnimatedServerConfigInfo(
        visible: Boolean,
        serverConfig: ServerConfig?,
        onDismissRequest: () -> Unit,
    ) {
        AnimatedAlertDialog(
            visible,
            onDismissRequest = onDismissRequest
        ) { dismiss ->
            BorderCard(
                modifier = Modifier
                    .width(500.dp)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .autoClearFocus()
            ) {
                Box {
                    ExitButton(dismiss)
                    var url by remember(serverConfig, visible) { mutableStateOf(serverConfig?.ip ?: "") }
                    var name by remember(serverConfig, visible) { mutableStateOf(serverConfig?.name ?: "") }

                    Column(modifier = Modifier.padding(10.dp)) {
                        RWSingleOutlinedTextField(
                            "Name",
                            name,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        ) { name = it }

                        RWSingleOutlinedTextField(
                            "Url/Ip",
                            url,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        ) { url = it }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            TextButton(
                                onClick =
                                {
                                    if (serverConfig != null) {
                                        serverConfig.ip = url
                                        serverConfig.name = name
                                    } else {
                                        val config = ServerConfig(url, name, ServerType.Server)
                                        instance.allServerConfig.add(config)
                                        allServerData.add(ServerData(config))
                                    }

                                    updateServerConfig = !updateServerConfig
                                    onDismissRequest()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd),
                            ) { Text("Apply", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }

    fun LazyListScope.ServerList(
        serverDataList: List<ServerData>
    ) {
        items(
            serverDataList,
            key = { it.hashCode() }
        ) { serverData ->
            //val serverData = remember { serverDataList[index] }

            ReorderableItem(reorderableLazyListState, serverData.hashCode()) {

                val interactionSource = remember { MutableInteractionSource() }

                Card(
                    onClick = {
                        val ip = serverData.config.ip
                        serverAddress = ip
                        configIO.setGameConfig("lastNetworkIP", ip)
                        isConnecting = true
                    },
                    modifier = Modifier.then(if (koinInject<Settings>().enableAnimations)
                        Modifier.animateItem()
                    else Modifier)
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(5.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(0.9f)),
                    elevation =  CardDefaults.cardElevation(defaultElevation = 10.dp),
                    border = BorderStroke(2.dp,  MaterialTheme.colorScheme.surfaceContainer),
                    interactionSource = interactionSource,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Box {
                        Row(
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopEnd),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (serverData.config.editable) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.padding(5.dp).clickable {
                                    instance.allServerConfig.remove(serverData.config)
                                    allServerData.remove(serverData)
                                })

                                Icon(
                                    Icons.Default.Info,
                                    null,
                                    modifier = Modifier.padding(5.dp).clickable {
                                        editingServerConfig = serverData.config
                                        showServerInfoConfig = true
                                    }
                                )
                            }

                            IconButton(
                                modifier = Modifier.padding(5.dp, 5.dp, 20.dp, 5.dp).size(30.dp).draggableHandle(
                                    onDragStopped = {
                                        updateServerConfig = !updateServerConfig
                                    },
//                                    onDragStarted = {
//                                        ViewCompat.performHapticFeedback(
//                                            view,
//                                            HapticFeedbackConstantsCompat.GESTURE_START
//                                        )
//                                    },
//                                    onDragStopped = {
//                                        ViewCompat.performHapticFeedback(
//                                            view,
//                                            HapticFeedbackConstantsCompat.GESTURE_END
//                                        )
//                                    },
                                    interactionSource = interactionSource,
                                ),
                                onClick = {},
                            ) {
                                Icon(painter = painterResource(Res.drawable.drag_30), contentDescription = "Reorder")
                            }
                        }

                        Row(modifier = Modifier.align(Alignment.TopStart)) {
                            val iconPainter = remember {
                                runCatching {
                                    serverData.infoPacket?.iconBytes?.let { readPainterByBytes(it) }
                                }.getOrNull()
                            }

                            if (serverData.config.type == ServerType.Server) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        iconPainter ?: painterResource(Res.drawable.error_missingmap),
                                        null,
                                        modifier = Modifier.size(120.dp).padding(5.dp)
                                    )
                                    if (serverData.isLoading) CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            val info = remember(serverData.infoPacket) { serverData.infoPacket }
                            val playerCount = remember(info) { info?.run { "$currentPlayer / $maxPlayerSize" } ?: "" }
                            val version = remember(info) {
                                info?.run { version } ?: ""
                            }
                            val description = remember(info) {
                                info?.run { description } ?: ""
                            }
                            val mapName = remember(info) {
                                info?.run { mapName } ?: ""
                            }
//                    val ping = remember(info) {
//                        info?.run { ping.toString() } ?: ""
//                    }
                            val mods = remember(info) {
                                info?.run { mods } ?: ""
                            }

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {

                                val configName = remember(updateServerConfig) { serverData.config.name }

                                Text(
                                    buildAnnotatedString {
                                        append(info?.name ?: configName)
                                        withStyle(
                                            SpanStyle(
                                                color = Color.DarkGray,
                                                fontStyle = FontStyle.Italic
                                            )
                                        ) {
                                            if (version.isNotEmpty()) append("  Version: $version")
                                        }
                                    },
                                    modifier = Modifier.padding(3.dp),
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    buildString {
                                        appendLine("player: $playerCount")
                                        appendLine("playing map: $mapName")
                                        appendLine(description)
                                        if (mods.isNotBlank()) appendLine("enabled mods: $mods")
                                    },
                                    modifier = Modifier.padding(3.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }

                            Spacer(modifier = Modifier.size(10.dp))
                        }
                    }
                }
            }
        }
    }


    fun resetFilter() {
        enableModFilter = false
        playerLimitRange = 0..100
        mapNameFilter = ""
        creatorNameFilter = ""
        roomLabelFilterSelection = emptySet()
    }

    @Composable
    fun BlacklistTargetDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
    ) {
        var showBlacklistInfo by remember { mutableStateOf(false) }
        var infoSelectedIndex by remember { mutableStateOf(0) }
        var addMode by remember { mutableStateOf(false) }

        DisposableEffect(key1 = visible) {
            onDispose {
                if(!visible) {
                    showBlacklistInfo = false
                    addMode = false
                }
            }
        }

        AnimatedAlertDialog(
            visible = visible,
            onDismissRequest = { onDismissRequest() },
        ) { _ ->
            BorderCard(
                modifier = Modifier.fillMaxSize(GeneralProportion()),
            ) {
                AnimatedBlackList(
                    !showBlacklistInfo,
                    blacklists,
                    {
                        blacklists.removeAt(it)
                        infoSelectedIndex = 0
                    },
                    { index  ->
                        infoSelectedIndex = index
                        showBlacklistInfo = true
                    },
                    {
                        addMode = true
                        showBlacklistInfo = true
                    }
                )

                if(addMode) {
                    AnimatedBlacklistInfo(showBlacklistInfo, Blacklist("", ""), {
                        showBlacklistInfo = false
                        addMode = false
                    }) {
                        blacklists.add(it)
                    }
                } else {
                    AnimatedBlacklistInfo(showBlacklistInfo, blacklists.getOrNull(infoSelectedIndex), { showBlacklistInfo = false }) {
                        blacklists[infoSelectedIndex] = it
                    }
                }
            }
        }
    }

    @Composable
    fun JoinServerField() {
        RWSingleOutlinedTextField(
            label = readI18n("multiplayer.joinServer"),
            value = joinServerAddress,
            modifier = Modifier.width(400.dp).padding(10.dp),
            leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(30.dp)) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    null,
                    modifier = Modifier.clickable {
                        if(joinServerAddress.isNotBlank()) {
                            serverAddress = joinServerAddress
                            isConnecting = true
                        }
                    })
            },
            onValueChange =
            {
                joinServerAddress = it
                configIO.setGameConfig("lastNetworkIP", it)
            },
        )
    }

    @Composable
    fun RoomListApiSettingsDialog(
        visible: Boolean,
        initialUrls: String,
        onDismissRequest: () -> Unit,
        onSave: (String) -> Unit,
    ) {
        AnimatedAlertDialog(
            visible,
            onDismissRequest = onDismissRequest
        ) { dismiss ->
            BorderCard(
                modifier = Modifier
                    .width(560.dp)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .autoClearFocus()
            ) {
                Box {
                    ExitButton(dismiss)
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            readI18n("multiplayer.roomListApi.title"),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            readI18n("multiplayer.roomListApi.hint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        var text by remember { mutableStateOf(initialUrls) }
                        LaunchedEffect(visible, initialUrls) {
                            if (visible) text = initialUrls
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text(readI18n("multiplayer.roomListApi.fieldLabel")) },
                            colors = RWOutlinedTextColors,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismissRequest) {
                                Text(readI18n("multiplayer.roomListApi.cancel"))
                            }
                            TextButton(
                                onClick = { onSave(text.trim()) }
                            ) {
                                Text(readI18n("multiplayer.roomListApi.save"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FilterSurfaceDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit
    ) {
        AnimatedAlertDialog(
            visible, onDismissRequest = onDismissRequest
        ) { dismiss ->
            BorderCard(
                modifier = Modifier
                    .fillMaxSize(GeneralProportion())
                    .padding(10.dp)
                    .autoClearFocus(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.6f)
            ) {
                var showBlacklistDialog by remember { mutableStateOf(false) }
                BlacklistTargetDialog(showBlacklistDialog) { showBlacklistDialog = false }

                Box(modifier = Modifier.fillMaxSize()) {
                    ExitButton(dismiss)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            readI18n("common.filter"),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        FilterSectionCard(readI18n("multiplayer.filter.sectionSearch")) {
                            RWSingleOutlinedTextField(
                                readI18n("multiplayer.filter.gameMapNameFilter"),
                                mapNameFilter,
                                modifier = Modifier.fillMaxWidth()
                            ) { mapNameFilter = it }
                            RWSingleOutlinedTextField(
                                readI18n("multiplayer.filter.creatorNameFilter"),
                                creatorNameFilter,
                                modifier = Modifier.fillMaxWidth()
                            ) { creatorNameFilter = it }
                        }

                        FilterSectionCard(readI18n("multiplayer.filter.sectionRange")) {
                            var range by remember(playerLimitRange) { mutableStateOf(playerLimitRange) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    readI18n("multiplayer.filter.playerLimitRange"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${range.first} ~ ${range.last}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            RangeSlider(
                                valueRange = 0f..100f,
                                modifier = Modifier.fillMaxWidth(),
                                value = range.first.toFloat()..range.last.toFloat(),
                                colors = RWSliderColors,
                                onValueChange = { range = it.start.roundToInt()..it.endInclusive.roundToInt() },
                                onValueChangeFinished = { playerLimitRange = range }
                            )
                        }

                        FilterSectionCard(readI18n("multiplayer.filter.sectionDisplay")) {
                            FilterSwitchRow(
                                label = readI18n("multiplayer.filter.hideModdedRooms"),
                                hint = readI18n("multiplayer.filter.hideModdedRoomsHint"),
                                checked = enableModFilter,
                                onCheckedChange = { enableModFilter = it }
                            )
                        }

                        // --- 标签筛选区块 ---
                        var availableLabels by remember { mutableStateOf<List<String>?>(null) }
                        var labelsLoading by remember { mutableStateOf(false) }
                        var labelsFailed by remember { mutableStateOf(false) }

                        LaunchedEffect(visible, instance.roomListApiUrls) {
                            if (!visible) return@LaunchedEffect
                            availableLabels = null
                            labelsLoading = true
                            labelsFailed = false
                            val result = runCatching {
                                net.fetchRoomListLabels(instance.roomListApiUrls)
                            }
                            labelsLoading = false
                            result.onSuccess { list ->
                                availableLabels = list
                            }.onFailure {
                                labelsFailed = true
                            }
                        }

                        FilterSectionCard(readI18n("multiplayer.filter.sectionLabel")) {
                            when {
                                labelsLoading -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            readI18n("multiplayer.filter.labelLoading"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                labelsFailed || availableLabels == null -> {
                                    Text(
                                        if (labelsFailed)
                                            readI18n("multiplayer.filter.labelLoadFailed")
                                        else
                                            readI18n("multiplayer.filter.labelUnavailable"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                availableLabels!!.isEmpty() -> {
                                    Text(
                                        readI18n("multiplayer.filter.labelUnavailable"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                else -> {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        availableLabels!!.forEach { labelText ->
                                            val selected = labelText in roomLabelFilterSelection
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    roomLabelFilterSelection =
                                                        if (selected)
                                                            roomLabelFilterSelection - labelText
                                                        else
                                                            roomLabelFilterSelection + labelText
                                                },
                                                label = { Text(labelText) },
                                            )
                                        }
                                    }
                                    if (roomLabelFilterSelection.isNotEmpty()) {
                                        TextButton(
                                            onClick = { roomLabelFilterSelection = emptySet() },
                                            modifier = Modifier.padding(top = 2.dp),
                                        ) {
                                            Text(readI18n("multiplayer.filter.labelClearSelection"))
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { resetFilter() }) {
                                Text(readI18n("multiplayer.filter.reset"))
                            }
                            FilledTonalButton(onClick = { showBlacklistDialog = true }) {
                                Text(readI18n("multiplayer.filter.blacklist"))
                            }
                        }
                    }
                }
            }
        }
    }

    with(net) {
        LaunchedEffect(Unit) {
            refresh.send(Unit)
            for (u in refresh) {
                if (isRefreshing) continue
                throwable = null
                isRefreshing = true
                try {
                    val urls = instance.roomListApiUrls.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    val effectiveUrls =
                        if (urls.isNotEmpty()) urls else DEFAULT_ROOM_LIST_API_URLS.split(";").map { it.trim() }
                    currentViewList = getRoomListFromSourceUrl(effectiveUrls)
                    for (s in allServerData) {
                        launch(Dispatchers.IO) {
                            s.isLoading = true
                            s.infoPacket = runCatching {
                                s.config.getServerInfo()
                            }.onFailure {
                                logger.error(it.stackTraceToString())
                            }.getOrNull()
                            s.isLoading = false
                        }
                    }
                } catch (e: Throwable) {
                    throwable = e
                }

                isRefreshing = false
            }
        }
    }

    var hostDialogVisible by remember { mutableStateOf(false) }
    var filterSurfaceDialogVisible by remember { mutableStateOf(false) }

    FilterSurfaceDialog(filterSurfaceDialogVisible) { filterSurfaceDialogVisible = false }
    HostGameDialog(hostDialogVisible, { hostDialogVisible = false }) {
        onExit()
        onOpenRoomView()
        game.setUserName(userName)
    }


    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(10.dp)) {

                Row(modifier = Modifier.align(Alignment.Center)) {
                    RWTextButton(
                        readI18n("multiplayer.host"),
                        leadingIcon = { Icon(Icons.Default.Build, null, modifier = Modifier.size(30.dp)) },
                        modifier = Modifier.padding(10.dp)
                    ) { hostDialogVisible = true }

                    RWTextButton(
                        label = readI18n("multiplayer.joinLastGame"),
                        leadingIcon = { Icon(painter = painterResource(Res.drawable.replay_30), null, modifier = Modifier.size(30.dp)) },
                        modifier = Modifier.padding(10.dp)
                    ) {
                        val lastIp = configIO.getGameConfig<String?>("lastNetworkIP")
                        if (lastIp != null) {
                            serverAddress = lastIp
                            isConnecting = true
                        }
                    }
                }

                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    FloatingActionButton(
                        onClick = { editingServerConfig = null; showServerInfoConfig = true },
                        shape = CircleShape,
                        modifier = Modifier.padding(5.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.surfaceTint) }

                    Box {
                        val requester = remember { FocusRequester() }

                        LaunchedEffect(Unit) {
                            requester.requestFocus()
                        }

                        FloatingActionButton(
                            onClick = { if(!isRefreshing) scope.launch { refresh.trySend(Unit) } },
                            shape = CircleShape,
                            modifier = Modifier.padding(5.dp).focusRequester(requester),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            if(isRefreshing) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                            } else {
                                Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.surfaceTint)
                            }
                        }

                        val appContext = koinInject<AppContext>()

                        if (appContext.isDesktop()) {
                            Card(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(1.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                            ) {
                                Text("space", modifier = Modifier.padding(1.dp), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    ) {
        ExpandedCard {
            Box {
                ExitButton(onExit)
                Column {
                    Spacer(modifier = Modifier.height(30.dp))
                    val realList = remember(
                        currentViewList,
                        enableModFilter,
                        playerLimitRange,
                        mapNameFilter,
                        creatorNameFilter,
                        blacklists.size,
                        roomLabelFilterSelection,
                    ) {
                        currentViewList.filter { room ->
                            if (blacklists.any { it.uuid == room.uuid }) return@filter false

                            if (room.status.equals("ingame", ignoreCase = true)) return@filter false
                            if (enableModFilter) {
                                if (room.version.contains("mod", true) || room.mods.isNotBlank()) {
                                    return@filter false
                                }
                            }

                            if (roomLabelFilterSelection.isNotEmpty()) {
                                if (room.label.trim() !in roomLabelFilterSelection) return@filter false
                            }

                            if (mapNameFilter.isNotBlank()) {
                                return@filter room.mapName.contains(mapNameFilter, true)
                            }

                            if (creatorNameFilter.isNotBlank()) {
                                return@filter room.creator.contains(creatorNameFilter, true)
                            }

                            if (room.playerMaxCount != null) {
                                return@filter room.playerMaxCount in playerLimitRange || room.playerMaxCount!! > 100
                            }

                            true
                        }.sorted
                    }

                    AnimatedServerConfigInfo(
                        showServerInfoConfig,
                        editingServerConfig,
                    ) {
                        showServerInfoConfig = false
                        editingServerConfig = null
                    }

                    RoomListApiSettingsDialog(
                        visible = showRoomListApiSettings,
                        initialUrls = instance.roomListApiUrls,
                        onDismissRequest = { showRoomListApiSettings = false },
                        onSave = { urls ->
                            instance.roomListApiUrls = urls
                            configIO.saveConfig(instance)
                            showRoomListApiSettings = false
                            scope.launch { refresh.send(Unit) }
                        },
                    )

                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface
                    ) {
                        LazyColumnScrollbar(
                            listState = lazyListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = lazyListState
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            label = {
                                                Text(
                                                    readI18n("multiplayer.userName"),
                                                    fontFamily = MaterialTheme.typography.headlineMedium.fontFamily
                                                )
                                            },
                                            textStyle = MaterialTheme.typography.headlineLarge,
                                            colors = RWOutlinedTextColors,
                                            value = userName,
                                            enabled = true,
                                            singleLine = true,
                                            leadingIcon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(30.dp)) },
                                            modifier = Modifier.width(200.dp).padding(10.dp),
                                            onValueChange =
                                            {
                                                userName = it
                                            },
                                        )

                                        JoinServerField()

                                        RWIconButton(
                                            painterResource(Res.drawable.tune_30),
                                            modifier = Modifier.padding(5.dp),
                                            size = 50.dp
                                        ) { filterSurfaceDialogVisible = true }
                                        RWIconButton(
                                            Icons.Default.Settings,
                                            modifier = Modifier.padding(5.dp),
                                            size = 50.dp
                                        ) { showRoomListApiSettings = true }
                                    }
                                }

                                if (throwable != null) {
                                    item {
                                        SelectionContainer {
                                            Text(throwable?.stackTraceToString() ?: "", color = Color.Red)
                                        }
                                    }
                                }
                                RoomListAnimated(realList)
                                ServerList(allServerData)
                            }
                        }

//                var list by remember { mutableStateOf(List(100) { "Item $it" }) }
//                val lazyListState = rememberLazyListState()
//                val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
//                    list = list.toMutableList().apply {
//                        add(to.index, removeAt(from.index))
//                    }
//                }
//
//                LazyColumn(
//                    modifier = Modifier.fillMaxSize(),
//                    state = lazyListState,
//                    contentPadding = PaddingValues(8.dp),
//                    verticalArrangement = Arrangement.spacedBy(8.dp),
//                ) {
//                    items(list, key = { it }) { item ->
//                        ReorderableItem(reorderableLazyListState, key = item) {
//                            val interactionSource = remember { MutableInteractionSource() }
//
//                            Card(
//                                onClick = {},
//                                interactionSource = interactionSource,
//                            ) {
//                                Row {
//                                    Text(item, Modifier.padding(horizontal = 8.dp))
//                                    IconButton(
//                                        modifier = Modifier.draggableHandle(
//                                            onDragStarted = {
//
//                                            },
//                                            onDragStopped = {
//
//                                            },
//                                            interactionSource = interactionSource,
//                                        ),
//                                        onClick = {},
//                                    ) {
//                                        Icon(Icons.Rounded.Place, contentDescription = "Reorder")
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }

                    }
                }
            }
        }
    }
}




@Composable
private fun AnimatedBlackList(
    visible: Boolean,
    blacklists: SnapshotStateList<Blacklist>,
    onDeleteSource: (Int) -> Unit,
    onTapInfoButton: (Int) -> Unit,
    onTapAddButton: () -> Unit,
) {
    val enableAnimations = koinInject<Settings>().enableAnimations
    AnimatedVisibility(
        visible,
        enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
        exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(readI18n("multiplayer.blacklist"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(15.dp))
                LargeDividingLine { 0.dp }

                LazyColumn(
                    modifier = Modifier.selectableGroup().weight(1f),
                ) {
                    items(count = blacklists.size) { index ->
                        val blacklist = blacklists[index]
                        Modifier
                            .wrapContentSize()
                        Row(modifier = Modifier.then(if (koinInject<Settings>().enableAnimations)
                            Modifier.animateItem()
                        else Modifier)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    blacklist.name,
                                    modifier = Modifier.padding(3.dp),
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    blacklist.uuid,
                                    modifier = Modifier.padding(3.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    textDecoration = TextDecoration.Underline,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(horizontalArrangement = Arrangement.End) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.padding(5.dp).clickable {
                                    onDeleteSource(index)
                                })

                                Icon(
                                    Icons.Default.Info,
                                    null,
                                    modifier = Modifier.padding(5.dp, 5.dp, 20.dp, 5.dp).clickable { onTapInfoButton(index) },)
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(0.2f).fillMaxWidth()) {
                    IconButton(onClick = onTapAddButton, modifier = Modifier.align(Alignment.BottomEnd)) {
                        Icon(Icons.Default.AddCircle, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedBlacklistInfo(
    visible: Boolean,
    blacklist: Blacklist?,
    onDismissRequest: () -> Unit,
    onSourceChanged: (Blacklist) -> Unit
) {
    blacklist ?: return
    AnimatedVisibility(
        visible
    ){
        var name by remember { mutableStateOf(blacklist.name) }
        var url by remember { mutableStateOf(blacklist.uuid) }

        Column(modifier = Modifier.fillMaxSize()) {
            RWSingleOutlinedTextField(
                "Name",
                name,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            ) { name = it }

            RWSingleOutlinedTextField(
                "UUID",
                url,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            ) { url = it }

            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
            ) {
                TextButton(
                    onClick =
                    {
                        val cpy = blacklist.copy(name = name, uuid = url)
                        if(blacklist != cpy) onSourceChanged(cpy)
                        onDismissRequest()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd),
                ) { Text("Apply", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun WelcomeMessageAdmittingDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    val settings = koinInject<Settings>()

    AnimatedAlertDialog(
        visible, onDismissRequest = onDismissRequest, enableDismiss = false
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
             //   .fillMaxSize(GeneralProportion())
                .width(IntrinsicSize.Max)
                .padding(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, modifier = Modifier.size(32.dp).padding(5.dp))
                Text(
                    "Admitting",
                    modifier = Modifier.padding(5.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LargeDividingLine { 0.dp }
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    readI18n("multiplayer.admitting").trimIndent(),
                    modifier = Modifier.padding(5.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.weight(1f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RWTextButton("Yes", modifier = Modifier.padding(5.dp), leadingIcon = {
                        Icon(Icons.Default.Done, null, modifier = Modifier.size(30.dp))
                    }) {
                        settings.showWelcomeMessage = true
                        dismiss()
                    }

                    RWTextButton("No", modifier = Modifier.padding(5.dp), leadingIcon = {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(30.dp))
                    }) {
                        settings.showWelcomeMessage = false
                        dismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinServerRequestDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    roomDescription: RoomDescription?,
    blacklists: SnapshotStateList<Blacklist>,
    onJoin: (dismiss: () -> Unit) -> Unit,
) {
    roomDescription ?: return
    AnimatedAlertDialog(
        visible, onDismissRequest = onDismissRequest
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
              //  .fillMaxSize(GeneralProportion())
                .size(500.dp)
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
        ) {

            Box(modifier = Modifier
                .fillMaxWidth()
                .height(75.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                ),
                contentAlignment = Alignment.Center
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null, modifier = Modifier.size(32.dp).padding(5.dp))
                        Text("Join Server?", modifier = Modifier.padding(5.dp), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            LargeDividingLine { 0.dp }

            Text(
                readI18n(
                    "multiplayer.roomInfo",
                    I18nType.RWPP,
                    roomDescription.creator,
                    roomDescription.mapName,
                    roomDescription.playerCurrentCount?.toString() ?: "",
                    roomDescription.playerMaxCount?.toString() ?: "",
                    roomDescription.version,
                    roomDescription.mods
                ),
                modifier = Modifier.padding(5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(color = MaterialTheme.colorScheme.surface)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            blacklists.add(
                                Blacklist("${roomDescription.creator}: ${roomDescription.mapName}", roomDescription.uuid)
                            )

                            dismiss()
                        }
                        .weight(1f)
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = readI18n("multiplayer.addToBlackList"), style = MaterialTheme.typography.bodyLarge)
                }

                VerticalDivider(modifier = Modifier.padding(2.dp).fillMaxHeight(), thickness = 2.dp)

                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onJoin(dismiss)
                        }
                        .weight(1f)
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = readI18n("multiplayer.join"), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}