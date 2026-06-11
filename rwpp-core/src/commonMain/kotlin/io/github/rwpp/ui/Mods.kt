/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.app.PermissionHelper
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.copyToWithProgress
import io.github.rwpp.modDir
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.*
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

private enum class ModImportStage {
    Preparing,
    Importing
}

private data class ModImportProgress(
    val stage: ModImportStage,
    val fileName: String?,
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null
)

@Composable
private fun ModImportProgressDialog(progress: ModImportProgress?) {
    AnimatedAlertDialog(
        visible = progress != null,
        onDismissRequest = {},
        enableDismiss = false
    ) {
        val current = progress ?: return@AnimatedAlertDialog
        BorderCard(modifier = Modifier.width(420.dp).wrapContentHeight()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val title = when (current.stage) {
                    ModImportStage.Preparing -> readI18n("mod.importPreparing")
                    ModImportStage.Importing -> readI18n("mod.importing")
                }

                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                current.fileName?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(16.dp))

                val totalBytes = current.totalBytes
                if (totalBytes != null && totalBytes > 0) {
                    val progressValue = (current.copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressValue },
                        trackColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${formatBytes(current.copiedBytes)} / ${formatBytes(totalBytes)} (${(progressValue * 100).roundToInt()}%)",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    LinearProgressIndicator(
                        trackColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (current.copiedBytes > 0) {
                        Text(
                            formatBytes(current.copiedBytes),
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return "${kb.roundToInt()} KB"
    }

    val mb = kb / 1024.0
    return "${(mb * 10).roundToInt() / 10.0} MB"
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ModsView(onExit: () -> Unit) {
    val permissionHelper = koinInject<PermissionHelper>()
    val modManager = koinInject<ModManager>()
    val game = koinInject<Game>()

    var deletedMod by remember { mutableStateOf(false) }
    val mods = remember { SnapshotStateList<Mod>().apply { addAll(modManager.getAllMods()) } }
    var filter by remember { mutableStateOf("") }
    val filteredMods = remember(mods.size, filter) {
        mods.filter {
            it.name.uppercase().contains(filter.uppercase())
        }
    }

    val scope = rememberCoroutineScope()
    var updated by remember { mutableStateOf(false) }
    var enabledChanged by remember { mutableStateOf(false) }
    var disableAll by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<ModImportProgress?>(null) }

    LoadingView(isApplying, onLoaded = {
        isApplying = false
        onExit()
    }) {
        modManager.modSaveChange()
        true
    }

    ModImportProgressDialog(importProgress)

    val enabledMods = remember(enabledChanged, filteredMods.size) {
        filteredMods.filter { it.isEnabled }
    }

    val disabledMods = remember(enabledChanged, filteredMods.size) {
        filteredMods.filter { !it.isEnabled }
    }

//    LaunchedEffect(Unit) {
//        permissionHelper.requestExternalStoragePermission()
//    }

    suspend fun reloadMods() {
        withContext(Dispatchers.IO) {
            modManager.modReload()
        }
        mods.clear()
        mods.addAll(modManager.getAllMods())
        updated = !updated
    }

    fun reload() {
        scope.launch {
            reloadMods()
        }
    }

    fun updateFileChooseProgress(progress: FileChooseProgress) {
        importProgress = ModImportProgress(
            stage = ModImportStage.Preparing,
            fileName = progress.fileName,
            copiedBytes = progress.copiedBytes,
            totalBytes = progress.totalBytes
        )
    }

    fun importModFile(file: File) {
        if (importProgress?.stage == ModImportStage.Importing) return

        scope.launch {
            if (!file.extension.equals("rwmod", ignoreCase = true)) {
                importProgress = null
                UI.showWarning(readI18n("mod.loadInfo"))
                return@launch
            }

            val target = File(modDir, file.name)

            runCatching {
                importProgress = ModImportProgress(
                    stage = ModImportStage.Importing,
                    fileName = file.name,
                    totalBytes = file.length().takeIf { it > 0 }
                )

                var lastProgressUpdate = 0L
                withContext(Dispatchers.IO) {
                    file.copyToWithProgress(target, overwrite = false) { copiedBytes, totalBytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 100L || copiedBytes == totalBytes) {
                            lastProgressUpdate = now
                            withContext(Dispatchers.Main) {
                                importProgress = ModImportProgress(
                                    stage = ModImportStage.Importing,
                                    fileName = file.name,
                                    copiedBytes = copiedBytes,
                                    totalBytes = totalBytes.takeIf { it > 0 }
                                )
                            }
                        }
                    }
                }

                importProgress = null
                reloadMods()
            }.onSuccess {
                importProgress = null
            }.onFailure {
                importProgress = null
                UI.showWarning(it.message ?: "Unknown error")
            }
        }
    }

    BackHandler(true) {
        if (deletedMod) reload()
        onExit()
    }

    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("mods").broadcastIn()
        }
    }

    @Composable
    fun ModCard(mod: Mod) {
        Column {
            var isEnabled by remember { mutableStateOf(mod.isEnabled) }
            remember(disableAll) {
                if (disableAll) {
                    mod.isEnabled = false
                    disableAll = false
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Card(
                    Modifier.padding(5.dp),
                    shape = RectangleShape,
                    border = BorderStroke(3.dp, MaterialTheme.colorScheme.secondary)
                ) {
                    Image(
                        painterResource(Res.drawable.error_missingmap),
                        null,
                        modifier = Modifier.size(100.dp)
                    )
                }

                Column(modifier = Modifier.height(IntrinsicSize.Max).weight(1f)) {
                    Text(
                        mod.name,
                        modifier = Modifier.padding(5.dp).align(Alignment.Start),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )

                    val ramUsed = remember(updated) {
                        mod.getRamUsed()
                    }

                    Text(
                        "(RAM: $ramUsed)",
                        modifier = Modifier.padding(start = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Green
                    )

                    val errorMessage = remember(updated) {
                        mod.errorMessage
                    }

                    if (errorMessage != null) {
                        Text(
                            errorMessage,
                            modifier = Modifier.padding(start = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Red
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier
                        .height(100.dp)
                        .padding(2.dp)
                        .align(Alignment.CenterVertically),
                    thickness = 4.dp,
                )

                Column(Modifier.align(Alignment.CenterVertically).padding(end = 10.dp)) {
                    IconButton(onClick = {
                        mod.isEnabled = !mod.isEnabled
                        enabledChanged = !enabledChanged
                    }, modifier = Modifier.size(45.dp)) {
                        Icon(
                            if (isEnabled) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surfaceTint
                        )
                    }

                    IconButton(onClick = {
                        if (mod.tryDelete()) {
                            mods.remove(mod)
                            deletedMod = true
                        } else {
                            UI.showWarning(readI18n("mod.removeInfo"))
                        }
                    }, modifier = Modifier.size(45.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surfaceTint
                        )
                    }
                }
            }

            if (isEnabled) {
                val expandedStyle = remember {
                    SpanStyle(
                        fontWeight = FontWeight.W500,
                        color = Color(173, 216, 230),
                        fontStyle = FontStyle.Italic,
                        textDecoration = TextDecoration.Underline
                    )
                }

                ExpandableText(
                    text = mod.description,
                    modifier = Modifier.padding(start = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textModifier = Modifier.padding(top = 5.dp),
                    showMoreStyle = expandedStyle,
                    showLessStyle = expandedStyle
                )
            }
            //  loadSvgPainter()
            //painterResource(Res.drawable.)
            Spacer(modifier = Modifier.size(10.dp))
        }
    }

    fun LazyListScope.ModList(data: List<Mod>) {
        items(
            count = data.size,
            key = { data[it].id }
        ) { index ->
            val mod = data[index]
            BorderCard(
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                    .6f
                ),
                modifier = Modifier.then(
                    if (koinInject<Settings>().enableAnimations)
                        Modifier.animateItem()
                    else Modifier
                )
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(5.dp)
            ) {
                ModCard(mod)
            }
        }
    }

    @Composable
    fun Header(isEnabledList: Boolean) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                readI18n("mod.${if (isEnabledList) "enabled" else "disabled"}"),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 5.dp)
                    .align(Alignment.CenterHorizontally)
            )

            HorizontalDivider(
                thickness = 3.dp,
                modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    @Composable
    fun Filter() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            RWSingleOutlinedTextField(
                "Filter",
                filter,
                modifier = Modifier.fillMaxWidth(.5f),
                leadingIcon = { Icon(Icons.Default.Search, null) }
            ) {
                filter = it
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(if (LocalWindowManager.current != WindowManager.Small) 10.dp else 0.dp),
        containerColor = Color.Transparent,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                RWTextButton(
                    readI18n("mod.reload"),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(Res.drawable.replay_30),
                            null,
                            modifier = Modifier.size(30.dp)
                        )
                    },
                    modifier = Modifier.padding(5.dp),
                ) { reload() }

                RWTextButton(
                    readI18n("mod.inputFile"),
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.file_open),
                            null,
                            modifier = Modifier.size(30.dp)
                        )
                    },
                    modifier = Modifier.padding(5.dp)
                ) {
                    if (importProgress != null) return@RWTextButton

                    appKoin.get<ExternalHandler>().openFileChooser(
                        onProgress = { updateFileChooseProgress(it) }
                    ) { file ->
                        importModFile(file)
                    }
                }

                RWTextButton(
                    readI18n("mod.disableAll"),
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.cancel),
                            null,
                            modifier = Modifier.size(30.dp)
                        )
                    },
                    modifier = Modifier.padding(5.dp)
                ) { disableAll = true }

                RWTextButton(
                    readI18n("mod.apply"),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Done,
                            null,
                            modifier = Modifier.size(30.dp)
                        )
                    },
                    modifier = Modifier.padding(5.dp),
                ) { isApplying = true }
            }
        }
    ) { paddingValues ->
        if (LocalWindowManager.current != WindowManager.Small) {
            BorderCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box {
                    ExitButton {
                        if (deletedMod) reload()
                        onExit()
                    }
                    Column {
                        Filter()
                        Spacer(modifier = Modifier.size(20.dp))

                        @Composable
                        fun RowScope.ModListColumn(isEnabledList: Boolean) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 5.dp, start = 5.dp, end = 2.dp, bottom = 5.dp)
                                    .weight(1f)
                            ) {
                                Header(isEnabledList)

                                val state = rememberLazyListState()
                                LazyColumnScrollbar(
                                    listState = state,
                                    modifier = Modifier.padding(start = 5.dp, bottom = 5.dp)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        state = state
                                    ) {
                                        val data = if (isEnabledList) enabledMods else disabledMods
                                        ModList(data)
                                    }
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            ModListColumn(true)
                            VerticalDivider(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(2.dp)
                                    .align(Alignment.CenterVertically),
                                thickness = 4.dp,
                            )
                            ModListColumn(false)
                        }
                    }
                }

            }
        } else {
            ExpandedCard {
                Box {
                    ExitButton {
                        if (deletedMod) reload()
                        onExit()
                    }
                    val state = rememberLazyListState()
                    LazyColumnScrollbar(
                        listState = state,
                        modifier = Modifier.padding(start = 5.dp, bottom = 5.dp, top = 30.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = state
                        ) {
                            item { Filter() }
                            item { Header(true) }
                            ModList(enabledMods)
                            item { Header(false) }
                            ModList(disabledMods)
                            item { Spacer(modifier = Modifier.size(50.dp)) }
                        }
                    }
                }
            }
        }
    }
}
