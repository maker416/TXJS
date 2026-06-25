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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModSourceType
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.copyToWithProgress
import io.github.rwpp.modDir
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.*
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.ListIndicatorSettings
import io.github.rwpp.widget.v2.ScrollbarSelectionActionable
import kotlinx.coroutines.CancellationException
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

private val ModListScrollbarThickness = 4.dp
private val ModListScrollbarPadding = 3.dp
private val ModListScrollbarReservedWidth = 18.dp

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

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ModsView(onExit: () -> Unit) {
    val modManager = koinInject<ModManager>()
    val settings = koinInject<Settings>()

    var deletedMod by remember { mutableStateOf(false) }
    val mods = remember { SnapshotStateList<Mod>().apply { addAll(modManager.getAllMods()) } }
    var filter by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    var updated by remember { mutableStateOf(false) }
    var enabledChanged by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var applySucceeded by remember { mutableStateOf(false) }
    var isClosingAfterDelete by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<ModImportProgress?>(null) }
    var pendingDeleteMod by remember { mutableStateOf<Mod?>(null) }

    LoadingView(isApplying, onLoaded = {
        isApplying = false
        if (applySucceeded) {
            applySucceeded = false
            onExit()
        }
    }) {
        try {
            modManager.modSaveChange()
            withContext(Dispatchers.Main) {
                applySucceeded = true
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            message(e.message ?: "Unknown error")
            withContext(Dispatchers.Main) {
                applySucceeded = false
            }
            false
        }
    }

    ModImportProgressDialog(importProgress)

    val filteredMods = remember(mods.size, updated, enabledChanged, filter) {
        val keyword = filter.trim()
        if (keyword.isBlank()) {
            mods.toList()
        } else {
            mods.filter { it.name.contains(keyword, ignoreCase = true) }
        }
    }

    val enabledMods = remember(enabledChanged, filteredMods) {
        filteredMods.filter { it.isEnabled }
    }

    val disabledMods = remember(enabledChanged, filteredMods) {
        filteredMods.filter { !it.isEnabled }
    }

    val enabledTotal = remember(enabledChanged, mods.size) {
        mods.count { it.isEnabled }
    }
    val disabledTotal = mods.size - enabledTotal

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
            try {
                reloadMods()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
            }
        }
    }

    fun exit() {
        if (isClosingAfterDelete) return

        if (!deletedMod) {
            onExit()
            return
        }

        isClosingAfterDelete = true
        scope.launch {
            try {
                reloadMods()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
            } finally {
                isClosingAfterDelete = false
            }
            onExit()
        }
    }

    fun updateFileChooseProgress(progress: FileChooseProgress) {
        if (progress.fileName == null) {
            importProgress = null
            return
        }

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

            try {
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

                reloadMods()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
            } finally {
                importProgress = null
            }
        }
    }

    BackHandler(true) {
        exit()
    }

    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("mods").broadcastIn()
        }
    }

    fun changeModEnabled(mod: Mod, enabled: Boolean) {
        if (mod.isEnabled == enabled) return
        mod.isEnabled = enabled
        enabledChanged = !enabledChanged
    }

    fun deleteMod(mod: Mod) {
        if (mod.isEnabled) {
            UI.showWarning(readI18n("mod.removeEnabledInfo"))
        } else if (mod.tryDelete()) {
            mods.remove(mod)
            deletedMod = true
        } else {
            UI.showWarning(readI18n("mod.removeInfo"))
        }
    }

    @Composable
    fun StatPill(label: String, value: String, color: Color) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = .14f),
            border = BorderStroke(1.dp, color.copy(alpha = .45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
                Text(
                    value,
                    color = color,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    fun SummaryStrip() {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatPill(readI18n("mod.total"), mods.size.toString(), MaterialTheme.colorScheme.onSurfaceVariant)
            StatPill(readI18n("mod.enabled"), enabledTotal.toString(), MaterialTheme.colorScheme.primary)
            StatPill(readI18n("mod.disabled"), disabledTotal.toString(), MaterialTheme.colorScheme.secondary)
        }
    }

    @Composable
    fun FilterField(modifier: Modifier = Modifier) {
        RWSingleOutlinedTextField(
            readI18n("mod.search"),
            filter,
            modifier = modifier,
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.surfaceTint) },
            trailingIcon = if (filter.isNotBlank()) {
                {
                    IconButton(
                        onClick = { filter = "" },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.surfaceTint)
                    }
                }
            } else null
        ) {
            filter = it
        }
    }

    @Composable
    fun ModsTopBar(compact: Boolean) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = 14.dp,
                    end = if (compact) 10.dp else 46.dp,
                    bottom = 10.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (compact) {
                Text(
                    readI18n("menu.mods"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                SummaryStrip()
                FilterField(Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        readI18n("menu.mods"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    FilterField(Modifier.weight(1f).widthIn(min = 240.dp))
                    SummaryStrip()
                }
            }
        }
    }

    @Composable
    fun ModCard(mod: Mod) {
        val isEnabled = mod.isEnabled
        val statusText = readI18n("mod.${if (isEnabled) "enabled" else "disabled"}")
        val statusColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        val sourceTypeText = when (mod.sourceType) {
            ModSourceType.RwMod -> readI18n("mod.sourceTypeRwMod")
            ModSourceType.Folder -> readI18n("mod.sourceTypeFolder")
            ModSourceType.Ini -> readI18n("mod.sourceTypeIni")
            ModSourceType.Unknown -> readI18n("mod.sourceTypeUnknown")
        }
        val ramUsed = remember(updated, enabledChanged, mod.id) { mod.getRamUsed() }
        val errorMessage = remember(updated, enabledChanged, mod.id) { mod.errorMessage }
        val description = remember(updated, mod.id) { mod.description.trim() }
        val expandedStyle = remember {
            SpanStyle(
                fontWeight = FontWeight.W500,
                color = Color(173, 216, 230),
                fontStyle = FontStyle.Italic,
                textDecoration = TextDecoration.Underline
            )
        }

        @Composable
        fun MetadataPill(text: String, color: Color) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = color.copy(alpha = .14f),
                border = BorderStroke(1.dp, color.copy(alpha = .35f))
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, statusColor.copy(alpha = .75f))
            ) {
                Image(
                    painterResource(Res.drawable.error_missingmap),
                    null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        mod.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MetadataPill(statusText, statusColor)
                    MetadataPill(sourceTypeText, MaterialTheme.colorScheme.tertiary)
                    if (mod.isNetworkMod) {
                        MetadataPill(readI18n("mod.networkMod"), MaterialTheme.colorScheme.secondary)
                    }
                }

                Text(
                    readI18n("mod.ramUsage", I18nType.RWPP, ramUsed),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF62E35F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (description.isNotBlank()) {
                    ExpandableText(
                        text = description,
                        collapsedMaxLine = 2,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        showMoreText = readI18n("mod.showMore"),
                        showLessText = readI18n("mod.showLess"),
                        showMoreStyle = expandedStyle,
                        showLessStyle = expandedStyle
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(
                modifier = Modifier.width(70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { changeModEnabled(mod, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                IconButton(
                    onClick = { pendingDeleteMod = mod },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @Composable
    fun EmptyModList() {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                readI18n("mod.empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    fun LazyListScope.ModList(data: List<Mod>) {
        if (data.isEmpty()) {
            item { EmptyModList() }
            return
        }

        items(
            count = data.size,
            key = { data[it].id }
        ) { index ->
            val mod = data[index]
            BorderCard(
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                    if (mod.isEnabled) .72f else .5f
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.then(
                    if (settings.enableAnimations)
                        Modifier.animateItem()
                    else Modifier
                )
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 4.dp, vertical = 5.dp)
            ) {
                ModCard(mod)
            }
        }
    }

    @Composable
    fun Header(isEnabledList: Boolean, count: Int) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    readI18n("mod.${if (isEnabledList) "enabled" else "disabled"}"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    readI18n("mod.count", I18nType.RWPP, count.toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(
                thickness = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = .85f)
            )
        }
    }

    @Composable
    fun ModListPanel(
        isEnabledList: Boolean,
        data: List<Mod>,
        modifier: Modifier = Modifier
    ) {
        val state = rememberLazyListState()
        Column(modifier = modifier.fillMaxHeight()) {
            Header(isEnabledList, data.size)
            LazyColumnScrollbar(
                listState = state,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 3.dp),
                thickness = ModListScrollbarThickness,
                padding = ModListScrollbarPadding,
                alwaysShowScrollBar = false,
                selectionActionable = ScrollbarSelectionActionable.WhenVisible,
                showItemIndicator = ListIndicatorSettings.Disabled
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    contentPadding = PaddingValues(
                        end = ModListScrollbarReservedWidth,
                        bottom = 8.dp
                    )
                ) {
                    ModList(data)
                }
            }
        }
    }

    @Composable
    fun ActionBar() {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RWTextButton(
                readI18n("mod.reload"),
                leadingIcon = {
                    Icon(
                        Icons.Default.Refresh,
                        null,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            ) { reload() }

            RWTextButton(
                readI18n("mod.inputFile"),
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.file_open),
                        null,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp)
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
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                mods.forEach { it.isEnabled = false }
                enabledChanged = !enabledChanged
            }

            RWTextButton(
                readI18n("mod.apply"),
                leadingIcon = {
                    Icon(
                        Icons.Default.Done,
                        null,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                applySucceeded = false
                loadingMessage = ""
                isApplying = true
            }
        }
    }

    @Composable
    fun DeleteModConfirmDialog() {
        AnimatedAlertDialog(
            visible = pendingDeleteMod != null,
            onDismissRequest = { pendingDeleteMod = null },
            enableDismiss = true
        ) { dismiss ->
            val mod = pendingDeleteMod ?: return@AnimatedAlertDialog
            BorderCard(modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 420.dp).wrapContentHeight()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        readI18n("mod.deleteConfirmTitle"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        readI18n("mod.deleteConfirmMessage", I18nType.RWPP, mod.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RWTextButton(
                            readI18n("mod.cancel"),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = dismiss
                        )

                        RWTextButton(
                            readI18n("mod.delete"),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                pendingDeleteMod = null
                                deleteMod(mod)
                            }
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(if (LocalWindowManager.current != WindowManager.Small) 10.dp else 0.dp),
        containerColor = Color.Transparent,
        bottomBar = { ActionBar() }
    ) { paddingValues ->
        if (LocalWindowManager.current != WindowManager.Small) {
            BorderCard(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box {
                    Column(Modifier.fillMaxSize()) {
                        ModsTopBar(compact = false)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ModListPanel(
                                isEnabledList = true,
                                data = enabledMods,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .align(Alignment.CenterVertically),
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            ModListPanel(
                                isEnabledList = false,
                                data = disabledMods,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    ExitButton {
                        exit()
                    }
                }
            }
        } else {
            ExpandedCard(modifier = Modifier.padding(paddingValues)) {
                Box {
                    val state = rememberLazyListState()
                    LazyColumnScrollbar(
                        listState = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 8.dp, end = 40.dp),
                        thickness = ModListScrollbarThickness,
                        padding = ModListScrollbarPadding,
                        alwaysShowScrollBar = false,
                        selectionActionable = ScrollbarSelectionActionable.WhenVisible,
                        showItemIndicator = ListIndicatorSettings.Disabled
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = state,
                            contentPadding = PaddingValues(
                                end = ModListScrollbarReservedWidth,
                                bottom = 12.dp
                            )
                        ) {
                            item { ModsTopBar(compact = true) }
                            item { Header(true, enabledMods.size) }
                            ModList(enabledMods)
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Header(false, disabledMods.size)
                            }
                            ModList(disabledMods)
                        }
                    }
                    ExitButton {
                        exit()
                    }
                }
            }
        }
    }

    DeleteModConfirmDialog()
}
