/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import com.eclipsesource.json.Json
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.Net
import io.github.rwpp.net.ReleaseAsset
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.projectVersion
import okhttp3.Request
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun SettingsView(
    onCheckUpdate: (LatestVersionProfile) -> Unit,
    defaultTheme: String,
    onChangeTheme: (String) -> Unit,
    onChangeBackgroundImage: (String) -> Unit,
    onExit: () -> Unit
) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("settings").broadcastIn()
        }
    }

    val configIO = koinInject<ConfigIO>()
    val appContext = koinInject<AppContext>()
    val settings = koinInject<Settings>()


    var backgroundImagePath by remember { mutableStateOf(settings.backgroundImagePath ?: "") }
    var showRestartHint by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    settings.backgroundImagePath = backgroundImagePath
                    configIO.saveAllConfig()
                    onChangeBackgroundImage(backgroundImagePath)
                    onExit()
                },
                shape = CircleShape,
                modifier = Modifier.padding(5.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(Icons.Default.Done, null, tint = MaterialTheme.colorScheme.surfaceTint)
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) {
        ExpandedCard(Modifier.autoClearFocus()) {
            Box {
                ExitButton(onExit)
                Column {
                    Spacer(Modifier.height(30.dp))
                    var selectedItem by remember { mutableIntStateOf(0) }
                    // 利用前缀来区分从游戏还是rwpp读取i18n
                    val items = listOf(
                        "graphics",
                        "gameplay",
                        "audio",
                        "developer",
                        "networking",
                        "rwpp-client",
                        "rwpp-theme"
                    )

                    NavigationBar(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        containerColor = Color.Transparent,
                    ) {
                        items.forEachIndexed { index, s ->
                            NavigationBarItem(
                                icon = {},
                                label = {
                                    Text(
                                        if (s.startsWith("rwpp-"))
                                            readI18n("settings.${s.removePrefix("rwpp-")}", I18nType.RWPP)
                                        else readI18n("menus.settings.heading.$s", I18nType.RW),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                selected = selectedItem == index,
                                onClick = { selectedItem = index },
                            )

                            if (index != items.lastIndex) {
                                VerticalDivider(modifier = Modifier.padding(2.dp).height(40.dp), thickness = 2.dp)
                            }
                        }
                    }

                    LargeDividingLine { 0.dp }

                    val state = rememberLazyListState()

                    LazyColumnScrollbar(listState = state) {
                        LazyColumn(state = state) {
                            item(selectedItem) {
                                val str = items[selectedItem]

                                when (str) {
                                    "graphics" -> SettingsGroup("graphics") {
                                        SettingsSwitchComp("showUnitWaypoints")
                                        SettingsSwitchComp("showHp", "alwayUnitHealth") // I don't why they are different
                                        SettingsSwitchComp("showUnitIcons", "unitIcons")
                                        SettingsSwitchComp("renderVsync")
                                        SettingsSwitchComp("renderClouds")
                                        SettingsSwitchComp("shaderEffects")
                                        SettingsSwitchComp("enableMouseCapture")
                                        SettingsSwitchComp("quickRally")
                                        SettingsSwitchComp("doubleClickToAttackMove")

                                        if (appContext.isDesktop()) {
                                            SettingsSwitchComp(
                                                readI18n("menus.settings.option.immersiveFullScreen", I18nType.RW),
                                                defaultValue = settings.isFullscreen,
                                                customConfigSettingAction = {
                                                    settings.isFullscreen = it
                                                }
                                            )

                                            SettingsSwitchComp(
                                                readI18n("settings.enableOffscreenPanel"),
                                                defaultValue = settings.enableOffscreenPanel,
                                                customConfigSettingAction = {
                                                    settings.enableOffscreenPanel = it
                                                }
                                            )
                                        }
                                    }

                                    "gameplay" -> {
                                        SettingsGroup("gameplay") {
                                            SettingsSwitchComp("showSelectedUnitsList")
                                            SettingsSwitchComp("useMinimapAllyColors")
                                            SettingsSwitchComp("showWarLogOnScreen")
                                            SettingsSwitchComp("smartSelection_v2", "smartSelection") //v2 ???

                                            val languageKeys = listOf("auto", "zh", "en")
                                            val languageItems = remember {
                                                listOf(
                                                    readI18n("settings.languageAuto"),
                                                    readI18n("settings.languageZh"),
                                                    readI18n("settings.languageEn")
                                                )
                                            }
                                            var selectedLanguageIndex by remember {
                                                mutableIntStateOf(
                                                    languageKeys.indexOf(settings.language).coerceAtLeast(0)
                                                )
                                            }
                                            SettingsDropDown(
                                                "language",
                                                languageItems,
                                                selectedLanguageIndex
                                            ) { index, _ ->
                                                selectedLanguageIndex = index
                                                settings.language = languageKeys[index]
                                                settings.forceEnglish = languageKeys[index] == "en"
                                                configIO.setGameConfig("forceEnglish", languageKeys[index] == "en")
                                                showRestartHint = true
                                            }
                                            SettingsSwitchComp("showUnitGroups", "unitGroupInterface")

                                            var xOffset by remember { mutableStateOf(settings.displayUnitGroupXOffset) }
                                            SettingsTextField(
                                                readI18n("settings.displayUnitGroupXOffset"),
                                                xOffset.toString(),
                                                typeInOnlyInteger = true,
                                                typeInNumberOnly = true,
                                                onValueChange = {
                                                    xOffset = it.toIntOrNull() ?: 0
                                                    settings.displayUnitGroupXOffset = it.toIntOrNull() ?: 0
                                                },
                                            )

                                            SettingsSlider(
                                                readI18n("settings.maxDisplayUnitGroupCount"),
                                                settings.maxDisplayUnitGroupCount / 10f,
                                                { settings.maxDisplayUnitGroupCount = (it * 10).roundToInt() },
                                                valueFormat = { "${(it * 10).roundToInt()}" },
                                            )
                                            if (appContext.isDesktop()) {
                                                val list = remember { listOf("Default", "Software", "OpenGL") }
                                                var selectedIndex by remember { mutableStateOf(list.indexOf(settings.renderingBackend)) }

                                                SettingsDropDown(
                                                    "renderingBackend",
                                                    list,
                                                    selectedIndex
                                                ) { index, backend ->
                                                    settings.renderingBackend = list[index]
                                                    selectedIndex = index
                                                }
                                            }

                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.displayTimeInGame"),
                                                settings.displayTimeInGame
                                            ) {
                                                settings.displayTimeInGame = it
                                            }
                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.enhancedReinforceTroops"),
                                                settings.enhancedReinforceTroops
                                            ) {
                                                settings.enhancedReinforceTroops = it
                                            }


                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.showUnitTargetLine"),
                                                settings.showUnitTargetLine
                                            ) {
                                                settings.showUnitTargetLine = it
                                            }

                                            val list = listOf("Zero", "Keep", "Unlimited")
                                            var selectedIndex by remember { mutableIntStateOf(list.indexOf(settings.effectLimitForAllEffects)) }
                                            SettingsDropDown("effectLimitForAllEffects", list, selectedIndex) { index, type ->
                                                selectedIndex = index
                                                settings.effectLimitForAllEffects = type
                                            }

//                                            SettingsSwitchComp(
//                                                "",
//                                                readI18n("settings.pathfindingOptimization"),
//                                                settings.pathfindingOptimization
//                                            ) {
//                                                settings.pathfindingOptimization = it
//                                            }

                                            if (appContext.isAndroid()) {
                                                SettingsSwitchComp(
                                                    "",
                                                    readI18n("settings.enableVolumeKeyMapping"),
                                                    settings.enableVolumeKeyMapping
                                                ) {
                                                    settings.enableVolumeKeyMapping = it
                                                }

                                                SettingsSwitchComp(
                                                    "",
                                                    readI18n("settings.enableLargerKeys"),
                                                    settings.enableLargerKeys
                                                ) {
                                                    settings.enableLargerKeys = it
                                                }
                                            }

                                            if (appContext.isDesktop()) {
                                                SettingsSwitchComp(
                                                    "",
                                                    readI18n("settings.improvedHealthBar"),
                                                    settings.improvedHealthBar
                                                ) {
                                                    settings.improvedHealthBar = it
                                                }

                                                SettingsSwitchComp(
                                                    "",
                                                    readI18n("settings.mouseMoveView"),
                                                    settings.mouseMoveView
                                                ) {
                                                    settings.mouseMoveView = it
                                                }
                                            }
                                            var teamUnitCapSinglePlayer by remember {
                                                mutableStateOf(
                                                    configIO.getGameConfig<Int?>(
                                                        "teamUnitCapSinglePlayer"
                                                    )
                                                )
                                            }
                                            SettingsTextField(
                                                readI18n("settings.teamUnitCapSinglePlayer"),
                                                teamUnitCapSinglePlayer?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true
                                            ) {
                                                teamUnitCapSinglePlayer = it.toIntOrNull()
                                                configIO.setGameConfig(
                                                    "teamUnitCapSinglePlayer",
                                                    teamUnitCapSinglePlayer ?: 100
                                                )
                                            }
                                            var teamUnitCapHostedGame by remember {
                                                mutableStateOf(
                                                    configIO.getGameConfig<Int?>(
                                                        "teamUnitCapHostedGame"
                                                    )
                                                )
                                            }
                                            SettingsTextField(
                                                readI18n("settings.teamUnitCapHostedGame"),
                                                teamUnitCapHostedGame?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true
                                            ) {
                                                teamUnitCapHostedGame = it.toIntOrNull()
                                                configIO.setGameConfig(
                                                    "teamUnitCapHostedGame",
                                                    teamUnitCapHostedGame ?: 100
                                                )
                                            }
                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.showExtraButton"),
                                                settings.showExtraButton
                                            ) {
                                                settings.showExtraButton = it
                                            }
                                        }

                                        SettingsGroup("", readI18n("settings.buildings")) {
                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.showAttackRange"),
                                                settings.showBuildingAttackRange
                                            ) {
                                                settings.showBuildingAttackRange = it
                                            }
                                        }

                                        SettingsGroup("", readI18n("settings.units")) {
                                            val list = Settings.unitAttackRangeTypes
                                            var selectedIndex by remember { mutableIntStateOf(list.indexOf(settings.showAttackRangeUnit)) }
                                            SettingsDropDown("showAttackRange", list, selectedIndex) { index, type ->
                                                selectedIndex = index
                                                settings.showAttackRangeUnit = type
                                            }
                                        }

                                        SettingsGroup("", readI18n("settings.inGameOffscreenPanel")) {
                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.enableQuickSelectMenu"),
                                                settings.enableQuickSelectMenu
                                            ) {
                                                settings.enableQuickSelectMenu = it
                                            }
                                        }
                                    }

                                    "audio" -> SettingsGroup("audio") {
                                        SettingsSliderRW("masterVolume")
                                        SettingsSliderRW("gameVolume")
                                        SettingsSliderRW("interfaceVolume")
                                        SettingsSliderRW("musicVolume")
                                    }

                                    "developer" -> SettingsGroup("developer") {
                                        SettingsSwitchComp("showFps")
                                        SettingsSwitchComp(
                                            "Show Welcome Message",
                                            defaultValue = settings.showWelcomeMessage ?: false
                                        ) { settings.showWelcomeMessage = it }
                                    }

                                    "networking" -> SettingsGroup("networking") {
                                        SettingsSwitchComp("udpInMultiplayer")
                                        SettingsSwitchComp("saveMultiplayerReplays", "saveReplays")
                                        SettingsSwitchComp("showChatAndPingShortcuts")
                                        SettingsSwitchComp("showMapPingsOnBattlefield")
                                        SettingsSwitchComp("showMapPingsOnMinimap")
                                        SettingsSwitchComp("showPlayerChatInGame")
                                    }

                                    "rwpp-client" -> {
                                        val net = koinInject<Net>()
                                        val scope = rememberCoroutineScope()
                                        var showUpdateLog by remember { mutableStateOf(false) }
                                        val logLines = remember { mutableStateListOf<String>() }
                                        val logScrollState = rememberLazyListState()
                                        var isChecking by remember { mutableStateOf(false) }
                                        var checkResult by remember { mutableStateOf<LatestVersionProfile?>(null) }

                                        LaunchedEffect(logLines.size) {
                                            if (logLines.isNotEmpty()) {
                                                logScrollState.animateScrollToItem(logLines.size - 1)
                                            }
                                        }

                                        AnimatedAlertDialog(
                                            visible = showUpdateLog,
                                            onDismissRequest = { if (!isChecking) showUpdateLog = false },
                                            enableDismiss = !isChecking
                                        ) { dismiss ->
                                            BorderCard(
                                                modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(
                                                        readI18n("settings.updateLogTitle", I18nType.RWPP),
                                                        style = MaterialTheme.typography.headlineSmall,
                                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    BorderCard(
                                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                                        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                                                    ) {
                                                        SelectionContainer {
                                                            LazyColumn(
                                                                state = logScrollState,
                                                                modifier = Modifier.padding(8.dp).fillMaxSize()
                                                            ) {
                                                                items(logLines.size) { index ->
                                                                    Text(
                                                                        logLines[index],
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    Spacer(Modifier.height(8.dp))
                                                    Row(
                                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        if (checkResult != null) {
                                                            RWTextButton(readI18n("settings.updateViewUpdate", I18nType.RWPP)) {
                                                                dismiss()
                                                                showUpdateLog = false
                                                                onCheckUpdate(checkResult!!)
                                                            }
                                                        }
                                                        RWTextButton(readI18n("settings.updateClose", I18nType.RWPP)) {
                                                            dismiss()
                                                            showUpdateLog = false
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        SettingsGroup("", readI18n("settings.client")) {
                                            Row {
                                                RWTextButton(readI18n("settings.checkUpdate"), modifier = Modifier.padding(5.dp)) {
                                                    logLines.clear()
                                                    checkResult = null
                                                    showUpdateLog = true
                                                    isChecking = true
                                                    scope.launch(Dispatchers.IO) {
                                                        val url = "https://gitee.com/api/v5/repos/maker416/TXJS/releases/latest"
                                                        val time = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(System.currentTimeMillis()))
                                                        logLines += "[$time] ${readI18n("settings.updateCheckStarted", I18nType.RWPP)}"
                                                        logLines += readI18n("settings.updateCurrentVersion", I18nType.RWPP, projectVersion)
                                                        logLines += readI18n("settings.updateRequestUrl", I18nType.RWPP, url)

                                                        try {
                                                            val request = Request.Builder().url(url).build()
                                                            net.client.newCall(request).execute().use { response ->
                                                                logLines += readI18n("settings.updateHttpStatus", I18nType.RWPP, response.code.toString())
                                                                val contentLength = response.body?.contentLength() ?: 0
                                                                logLines += readI18n("settings.updateResponseLength", I18nType.RWPP, contentLength.toString())

                                                                if (!response.isSuccessful) {
                                                                    logLines += readI18n("settings.updateRequestFailed", I18nType.RWPP, response.code.toString())
                                                                    isChecking = false
                                                                    return@launch
                                                                }

                                                                val body = response.body?.string()
                                                                if (body == null) {
                                                                    logLines += readI18n("settings.updateEmptyBody", I18nType.RWPP)
                                                                    isChecking = false
                                                                    return@launch
                                                                }

                                                                logLines += readI18n("settings.updateParsing", I18nType.RWPP)
                                                                val json = Json.parse(body).asObject()
                                                                val version = json.getString("tag_name", "null")
                                                                val bodyText = json.getString("body", "")
                                                                val prerelease = json.getBoolean("prerelease", false)
                                                                val assets = json.get("assets")?.asArray()?.map {
                                                                    val obj = it.asObject()
                                                                    val name = obj.getString("name", "")
                                                                    val downloadUrl = obj.getString("browser_download_url", "")
                                                                    ReleaseAsset(name, downloadUrl)
                                                                }?.filter { asset ->
                                                                    !asset.name.endsWith(".zip") && !asset.name.endsWith(".tar.gz")
                                                                } ?: emptyList()

                                                                logLines += readI18n("settings.updateRemoteVersion", I18nType.RWPP, version)
                                                                logLines += readI18n("settings.updatePrerelease", I18nType.RWPP, prerelease.toString())
                                                                logLines += readI18n("settings.updateAssetCount", I18nType.RWPP, assets.size.toString())
                                                                assets.forEach { logLines += readI18n("settings.updateAssetItem", I18nType.RWPP, it.name) }

                                                                if (version == "null") {
                                                                    logLines += readI18n("settings.updateParseError", I18nType.RWPP)
                                                                    isChecking = false
                                                                    return@launch
                                                                }

                                                                logLines += "--------------------------------------------------"
                                                                if (compareVersions(version, projectVersion) <= 0) {
                                                                    logLines += readI18n("settings.updateLatestVersion", I18nType.RWPP)
                                                                } else if (settings.ignoreVersion == version) {
                                                                    logLines += readI18n("settings.updateIgnoredVersion", I18nType.RWPP, version)
                                                                } else {
                                                                    logLines += readI18n("settings.updateNewVersionFound", I18nType.RWPP)
                                                                    checkResult = LatestVersionProfile(version, bodyText, prerelease, assets)
                                                                }
                                                                logLines += readI18n("settings.updateCheckCompleted", I18nType.RWPP)
                                                                isChecking = false
                                                            }
                                                        } catch (e: Exception) {
                                                            logLines += readI18n("settings.updateException", I18nType.RWPP, e.message ?: "Unknown")
                                                            isChecking = false
                                                        }
                                                    }
                                                }

                                                if (isChecking) CircularProgressIndicator(color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }

                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.autoCheckUpdate"),
                                                settings.autoCheckUpdate
                                            ) {
                                                settings.autoCheckUpdate = it
                                            }
                                        }
                                    }

                                    "rwpp-theme" -> {
                                        val list = themes.keys.toList()
                                        var selectedIndex by remember { mutableStateOf(list.indexOf(defaultTheme)) }
                                        SettingsGroup("", readI18n("settings.theme")) {
                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.enableAnimations"),
                                                settings.enableAnimations
                                            ) {
                                                settings.enableAnimations = it
                                            }

                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.boldText"),
                                                settings.boldText
                                            ) {
                                                settings.boldText = it
                                            }

                                            SettingsSwitchComp(
                                                "",
                                                readI18n("settings.changeGameTheme"),
                                                settings.changeGameTheme
                                            ) {
                                                settings.changeGameTheme = it
                                            }

                                            SettingsDropDown("colorScheme", list, selectedIndex,
                                                selectedItemColor = { theme, _ -> themes[theme]!!.primary }
                                            ) { index, theme ->
                                                onChangeTheme(theme)
                                                selectedIndex = index
                                            }

                                            val externalHandler = koinInject<ExternalHandler>()

                                            SettingsTextField(
                                                readI18n("settings.setBackgroundImagePath"),
                                                backgroundImagePath,
                                                onValueChange = {
                                                    backgroundImagePath = it
                                                },
                                                trailingIcon = {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.List,
                                                        null,
                                                        modifier = Modifier.clickable {
                                                            externalHandler.openFileChooser { backgroundImagePath = it.canonicalPath }
                                                        }
                                                    )
                                                },
                                            )

                                            SettingsSlider(
                                                readI18n("settings.backgroundTransparency"),
                                                settings.backgroundTransparency,
                                                {
                                                    settings.backgroundTransparency = it
                                                    UI.backgroundTransparency = it
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showRestartHint) {
                AlertDialog(
                    onDismissRequest = { showRestartHint = false },
                    title = {
                        Text(
                            readI18n("settings.language"),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(readI18n("settings.restartHint"))
                    },
                    confirmButton = {
                        TextButton(onClick = { showRestartHint = false }) {
                            Text(readI18n("common.ok", I18nType.RWPP))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.SettingsGroup(
    name: String,
    displayName: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp).then(if (koinInject<Settings>().enableAnimations)
        Modifier.animateItem()
    else Modifier)) {
        Text(
            displayName ?: readI18n("menus.settings.heading.$name", I18nType.RW),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 5.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(.6f),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4),
        ) {
            Column {
                content()
            }
        }
    }
}


@Composable
private fun SettingsSliderRW(
    name: String,
) {
    val configIO = koinInject<ConfigIO>()

    SettingsSlider(
        readI18n("menus.settings.option.$name", I18nType.RW),
        configIO.getGameConfig(name),
        { configIO.setGameConfig(name, it) },
        0f..1f,
    )
}