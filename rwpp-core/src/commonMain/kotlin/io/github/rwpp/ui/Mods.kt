/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.widget.UiSwitch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModInfoParser
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModSourceType
import io.github.rwpp.game.mod.SelectableMod
import io.github.rwpp.game.mod.requireModSelectionApplied
import io.github.rwpp.game.mod.snapshotModSelection
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.copyToWithProgress
import io.github.rwpp.modDir
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.analyzeRwmodFileDrop
import io.github.rwpp.platform.modFileDropTarget
import io.github.rwpp.rwpp_core.generated.resources.*
import io.github.rwpp.widget.*
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.ListIndicatorSettings
import io.github.rwpp.widget.v2.ScrollbarSelectionActionable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

private enum class ModListFilter {
    All,
    Enabled,
    Disabled,
}

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

/** 启用且单位加载成功：卡片绿框。 */
private val ModCardOkBorder = Color(0xFF62E35F)
/** 缺 title、已回退文件名：卡片黄框，不弹错误窗。 */
private val ModCardWarnBorder = Color(0xFFF5C542)

private enum class ModCardStatus {
    Neutral,
    LoadedOk,
    TitleWarn,
    LoadFailed,
}

private class UnloadedMod(private val file: File) : Mod {
    private val metadata = ModInfoParser.parseFromRwmod(file)
    val titleMissing: Boolean get() = metadata.titleMissing
    override val id: Int = -(file.absolutePath.hashCode() and 0x7FFFFFFF) - 1
    override val name: String get() = metadata.name
    override val description: String get() = metadata.description
    override val minVersion: String get() = metadata.minVersion
    override val errorMessage: String? = null
    override var isEnabled: Boolean = false
    override val path: String = file.absolutePath
    override fun getRamUsed(): String = "0"
    override fun getSize(): Long = file.length()
    override fun getBytes(): ByteArray = file.readBytes()
}

private fun missingTitleHint(fileName: String): String =
    readI18n("mod.missingTitle", I18nType.RWPP, fileName)

/** 缺 `[mod] title` 的标记包装：不覆盖引擎 [Mod.errorMessage]，单位仍可加载。 */
private class TitleErrorMod(delegate: Mod) : Mod by delegate

/** Capture engine-owned fields on the UI thread before reading file metadata in the background. */
private data class ModFileInput(val mod: Mod, val path: String, val hasError: Boolean, val enabled: Boolean)

private data class ModFiles(
    val engineMods: List<Mod>,
    val allMods: List<Mod>,
    val loadedEnabledPaths: Set<String>,
    val canonicalPaths: Map<String, String>,
)

/** Inputs contain the engine values captured on the UI thread; only disk metadata is read here. */
private fun readModFiles(inputs: List<ModFileInput>): ModFiles {
    val canonicalPaths = inputs.associate { it.path to File(it.path).canonicalPath }.toMutableMap()
    val engineMods = inputs.map { input ->
        if (!input.hasError && ModInfoParser.parseFromModFile(File(input.path))?.titleMissing == true) {
            TitleErrorMod(input.mod)
        } else input.mod
    }
    val unloaded = scanUnloadedMods(canonicalPaths.values.toSet())
    unloaded.forEach { canonicalPaths[it.path] = File(it.path).canonicalPath }
    return ModFiles(
        engineMods,
        engineMods + unloaded,
        inputs.filter { it.enabled }.map { canonicalPaths.getValue(it.path) }.toSet(),
        canonicalPaths,
    )
}

private fun Mod.hasTitleWarning(): Boolean =
    this is TitleErrorMod || (this is UnloadedMod && titleMissing)

private fun resolveModCardStatus(mod: Mod, loadedEnabledPaths: Set<String>, canonicalPaths: Map<String, String>): ModCardStatus {
    if (!mod.errorMessage.isNullOrBlank()) return ModCardStatus.LoadFailed
    if (mod.hasTitleWarning()) return ModCardStatus.TitleWarn
    if (mod.isEnabled && canonicalPaths[mod.path] in loadedEnabledPaths) return ModCardStatus.LoadedOk
    return ModCardStatus.Neutral
}

@Composable
private fun modCardStatusColor(status: ModCardStatus): Color = when (status) {
    ModCardStatus.LoadedOk -> ModCardOkBorder
    ModCardStatus.TitleWarn -> ModCardWarnBorder
    ModCardStatus.LoadFailed -> MaterialTheme.colorScheme.error
    ModCardStatus.Neutral -> MaterialTheme.colorScheme.surfaceContainer
}

private fun scanUnloadedMods(existingPaths: Set<String>): List<Mod> {
    val result = mutableListOf<Mod>()
    File(modDir).listFiles()?.forEach { file ->
        if (file.isFile && file.extension.equals("rwmod", ignoreCase = true)
            && file.canonicalPath !in existingPaths
        ) {
            result.add(UnloadedMod(file))
        }
    }
    return result
}

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
                    UiLinearProgressIndicator(
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

private data class FailedModLoadInfo(
    val name: String,
    val errorMessage: String,
)

private fun collectFailedMods(mods: List<Mod>): List<FailedModLoadInfo> {
    return mods.mapNotNull { mod ->
        // 缺 title 只是显示名回退，单位仍会加载，不进失败弹窗。
        if (mod is TitleErrorMod || mod is UnloadedMod || !mod.isEnabled) return@mapNotNull null
        val error = mod.errorMessage ?: return@mapNotNull null
        FailedModLoadInfo(name = mod.name.ifBlank { File(mod.path).name }, errorMessage = error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ModsView(
    onExit: () -> Unit,
    selectedTab: ModsMapsTab = ModsMapsTab.Mods,
    onTabChange: ((ModsMapsTab) -> Unit)? = null,
    /** 嵌入 [ModsAndMapsView] 共用外壳时为 true：不渲染 ExpandedCard / 关闭按钮。 */
    embedded: Boolean = false,
) {
    val modManager = koinInject<ModManager>()

    var deletedMod by remember { mutableStateOf(false) }
    val fileLoad = rememberPageFileLoadState<ModFiles>()
    val mods = remember { SnapshotStateList<Mod>() }
    var loadedEnabledPaths by remember { mutableStateOf(emptySet<String>()) }
    var canonicalPaths by remember { mutableStateOf(emptyMap<String, String>()) }
    var filter by remember { mutableStateOf("") }
    var listFilter by remember { mutableStateOf(ModListFilter.All) }
    var selectedMod by remember { mutableStateOf<Mod?>(null) }

    val scope = rememberCoroutineScope()
    var updated by remember { mutableStateOf(false) }
    var enabledChanged by remember { mutableStateOf(false) }
    var isClosingAfterDelete by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<ModImportProgress?>(null) }
    var pendingDeleteMod by remember { mutableStateOf<Mod?>(null) }
    var failedModsAfterReload by remember { mutableStateOf<List<FailedModLoadInfo>?>(null) }
    // OOM 元凶模组名单（空列表 = 引擎未能归因到具体模组）；与对话框可见性分离，
    // 拦截后续重载时可带着名单重弹对话框
    var oomCulpritMods by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMemoryExhaustedDialog by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var modDropOverlayActive by remember { mutableStateOf(false) }
    var modDropPointerInRoot by remember { mutableStateOf(Offset.Unspecified) }
    val appContext = koinInject<AppContext>()
    val canAcceptModFileDrop = LocalDialogInteractive.current &&
        appContext.isDesktop() &&
        fileLoad.isReady &&
        !isReloading &&
        importProgress == null

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

    suspend fun loadModFiles(actualMods: List<Mod>): ModFiles? {
        // These getters belong to the engine adapters. Keep them on their original UI thread.
        val inputs = actualMods.map { actual ->
            val selected = SelectableMod(actual)
            ModFileInput(selected, selected.path, selected.errorMessage != null, selected.isEnabled)
        }
        val files = fileLoad.load { readModFiles(inputs) } ?: return null
        mods.clear()
        mods.addAll(files.allMods)
        loadedEnabledPaths = files.loadedEnabledPaths
        canonicalPaths = files.canonicalPaths
        updated = !updated
        enabledChanged = !enabledChanged
        return files
    }

    LaunchedEffect(modManager) { loadModFiles(modManager.getAllMods()) }

    suspend fun reloadMods(knownStates: Map<String, Boolean>): List<FailedModLoadInfo> {
        // Only this explicit Apply commits the temporary startup choice as the user's new selection.
        io.github.rwpp.game.mod.ModLoadSafety.commitRecoverySelection()
        withContext(Dispatchers.IO) {
            // 在引擎加载单位定义之前应用开关，未启用的新模组不会解析单位（避免浪费时间）
            modManager.modReload(enabledByFileName = knownStates)
        }
        val actualMods = modManager.getAllMods()
        if (!UI.modReloadMemoryExhausted) requireModSelectionApplied(actualMods, knownStates)
        val files = loadModFiles(actualMods)
        // 重载已完成单位表重建，删除标记随之失效（否则“应用”会被永久拦截）
        deletedMod = false
        return collectFailedMods(files?.engineMods ?: actualMods)
    }

    /** 进程内重载。OOM 由平台 runReloadCore 捕获并置位全局标志，此处负责归因与弹窗。 */
    fun doReloadInProcess() {
        if (!fileLoad.isReady || isReloading || importProgress != null) return
        // 点击时立即固定选择，防止协程启动/引擎扫描期间开关发生变化。
        val selection = snapshotModSelection(mods.toList())
        isReloading = true
        scope.launch {
            try {
                val failed = reloadMods(selection)
                // 引擎把每个模组的 OOM 包装进该模组的错误消息后继续（首个撞 OOM 的是元凶，
                // 其余多为连带失败）；未包装的外层 OOM 由平台 catch 置位标志（此时无名单）。
                // 一旦堆耗尽，锁定后续重载（二次 OOM 必崩），弹窗指名元凶。
                val oomHit = UI.modReloadMemoryExhausted ||
                    failed.any { it.errorMessage.contains("OutOfMemoryError") }
                if (oomHit) {
                    UI.modReloadMemoryExhausted = true
                    oomCulpritMods = failed
                        .filter { it.errorMessage.contains("OutOfMemoryError") }
                        .map { it.name }
                        .distinct()
                    showMemoryExhaustedDialog = true
                } else if (failed.isNotEmpty()) {
                    failedModsAfterReload = failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: io.github.rwpp.game.mod.ModLoadFailure) {
                // App switches to the shared, lightweight recovery screen; do not enumerate the damaged table.
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) UI.showWarning(e.message.orEmpty())
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
            } finally {
                isReloading = false
            }
        }
    }

    fun reload() {
        // 堆已耗尽：本进程内再次重载必然撞墙（崩溃），拦截并重弹元凶对话框。
        if (UI.modReloadMemoryExhausted) {
            showMemoryExhaustedDialog = true
            return
        }
        doReloadInProcess()
    }

    fun exit() {
        if (isClosingAfterDelete || isReloading) return

        // 堆已耗尽时跳过删除后的重载：文件已删，下次启动引擎会干净重建，直接退出即可。
        if (!deletedMod || UI.modReloadMemoryExhausted) {
            onExit()
            return
        }

        isClosingAfterDelete = true
        val selection = snapshotModSelection(mods.toList())
        scope.launch {
            // 删除后的重建同样要全量解析所有启用模组；若中途撞 OOM 由平台 catch 兜底
            // （置位全局标志，不闪退），开关状态已随重载流程落盘，下次启动干净重建。
            try {
                reloadMods(selection)
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

    /**
     * 重复导入时确保既有模组在列表中可见：
     * - 清空搜索关键字，避免模组被当前过滤条件隐藏；
     * - 列表中没有该文件时（例如绕开导入流程直接放进模组目录的文件），以 UnloadedMod 补入。
     */
    suspend fun revealExistingMod(target: File) {
        val existingPaths = canonicalPaths.values.toSet()
        val discovered = withContext(Dispatchers.IO) {
            val canonical = target.canonicalPath
            if (canonical !in existingPaths && target.isFile) UnloadedMod(target) to canonical else null
        }
        filter = ""
        listFilter = ModListFilter.All
        if (discovered != null) {
            mods.add(discovered.first)
            canonicalPaths = canonicalPaths + (discovered.first.path to discovered.second)
        }
        updated = !updated
    }

    fun importModFile(file: File, ignoredRwmods: Int = 0) {
        if (!fileLoad.isReady || isReloading || importProgress?.stage == ModImportStage.Importing) return

        // 全局提示只有一个槽位，忽略数量必须随本次导入的最终结果一起显示。
        fun showImportResult(message: String) {
            val notice = if (ignoredRwmods > 0) {
                message + "\n\n" + readI18n("mod.dropExtraRwmodIgnored", I18nType.RWPP, ignoredRwmods.toString())
            } else {
                message
            }
            UI.showWarning(notice)
        }

        scope.launch {
            if (!file.extension.equals("rwmod", ignoreCase = true)) {
                importProgress = null
                showImportResult(readI18n("mod.loadInfo"))
                return@launch
            }

            val target = File(modDir, file.name)

            // 目标已存在（重复导入）：不复制，确保列表中能看到既有模组，并给出明确提示。
            // 原实现直接展示 FileAlreadyExistsException 的英文异常信息；且当文件经导入流程
            // 之外的途径进入模组目录（手动复制、资源浏览器下载、联机同步激活）时列表中并没有它，
            // 造成「提示已导入但界面看不到该模组」。
            if (target.exists()) {
                revealExistingMod(target)
                importProgress = null
                showImportResult(readI18n("mod.importAlreadyExists", I18nType.RWPP, file.name))
                return@launch
            }

            try {
                importProgress = ModImportProgress(
                    stage = ModImportStage.Importing,
                    fileName = file.name,
                    totalBytes = file.length().takeIf { it > 0 }
                )

                var lastProgressUpdate = 0L
                withContext(Dispatchers.IO) {
                    io.github.rwpp.game.mod.ModPlaytimeHashCache.sourceChanged(target)
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

                // 复制完成后扫描文件系统，将新模组以 UnloadedMod（禁用）加入列表
                revealExistingMod(target)
                showImportResult(readI18n("mod.importSuccess", I18nType.RWPP, file.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileAlreadyExistsException) {
                // 预检查与复制之间存在竞态（复制期间目标被其他流程创建），按重复导入处理。
                revealExistingMod(target)
                showImportResult(readI18n("mod.importAlreadyExists", I18nType.RWPP, file.name))
            } catch (e: Throwable) {
                showImportResult(e.message ?: "Unknown error")
            } finally {
                importProgress = null
            }
        }
    }

    if (!embedded) {
        BackHandler(true) {
            exit()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 嵌入模式下切换 tab 会 dispose，勿广播关闭；由外壳统一处理。
            if (!embedded) {
                CloseUIPanelEvent("mods").broadcastIn()
            }
        }
    }

    fun changeModEnabled(mod: Mod, enabled: Boolean) {
        if (!fileLoad.isReady || isReloading) return
        if (mod.isEnabled == enabled) return
        mod.isEnabled = enabled
        enabledChanged = !enabledChanged
    }

    fun deleteMod(mod: Mod) {
        if (!fileLoad.isReady || isReloading) return
        if (mod.isEnabled) {
            UI.showWarning(readI18n("mod.removeEnabledInfo"))
        } else if (mod.tryDelete()) {
            mods.remove(mod)
            deletedMod = true
        } else {
            UI.showWarning(readI18n("mod.removeInfo"))
        }
    }

    fun sourceTypeText(mod: Mod): String = readI18n(
        when (mod.sourceType) {
            ModSourceType.RwMod -> "mod.sourceTypeRwMod"
            ModSourceType.Folder -> "mod.sourceTypeFolder"
            ModSourceType.Ini -> "mod.sourceTypeIni"
            ModSourceType.Unknown -> "mod.sourceTypeUnknown"
        }
    )

    @Composable
    fun FilterField(modifier: Modifier = Modifier) {
        RWSingleOutlinedTextField(
            readI18n("mod.search"),
            filter,
            modifier = modifier.testTag("mods-search-field"),
            leadingIcon = { Icon(Icons.Default.Search, null) },
        ) { filter = it }
    }

    @Composable
    fun ModsTopBar(modifier: Modifier = Modifier) {
        var searchExpanded by remember { mutableStateOf(false) }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val narrow = maxWidth < 600.dp
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 56.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (searchExpanded) {
                    FilterField(Modifier.weight(1f))
                    if (!narrow) MemoryUsageButton(Modifier.width(140.dp))
                    IconButton(
                        onClick = {
                            filter = ""
                            searchExpanded = false
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = readI18n("mod.closeSearch"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (onTabChange != null) {
                        ModsMapsSegmentedControl(
                            selected = selectedTab,
                            onSelect = onTabChange,
                            modifier = Modifier.width(if (narrow) 132.dp else 176.dp),
                        )
                    } else {
                        Text(
                            readI18n("menu.mods"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!narrow) Spacer(Modifier.weight(1f))
                    MemoryUsageButton(if (narrow) Modifier.weight(1f) else Modifier.width(140.dp))
                    IconButton(
                        onClick = { searchExpanded = true },
                        modifier = Modifier.size(48.dp).testTag("mods-search-button"),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = readI18n("mod.search"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    /** 列表只显示两行关键信息；开关独立命中，说明和删除收进详情。 */
    @Composable
    fun ModRow(mod: Mod, status: ModCardStatus) {
        val ramUsed = remember(updated, enabledChanged, mod.id) { mod.getRamUsed() }
        val issue = when (status) {
            ModCardStatus.LoadFailed -> readI18n("mod.errorLoadTitle")
            ModCardStatus.TitleWarn -> readI18n("mod.metadataWarning")
            else -> null
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(onClickLabel = readI18n("mod.errorViewDetails")) { selectedMod = mod }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(8.dp),
                color = modCardStatusColor(status).copy(alpha = .16f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (issue != null) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = modCardStatusColor(status))
                    } else {
                        Text(mod.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(mod.name, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    issue ?: listOfNotNull(
                        sourceTypeText(mod),
                        readI18n("mod.ramUsage", I18nType.RWPP, ramUsed),
                        readI18n("mod.networkMod").takeIf { mod.isNetworkMod },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue != null) modCardStatusColor(status) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            UiSwitch(
                checked = mod.isEnabled,
                onCheckedChange = { changeModEnabled(mod, it) },
                enabled = !isReloading,
                modifier = Modifier.testTag("mod-switch-${mod.id}").semantics { contentDescription = mod.name },
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun ModDetailsDialog() {
        val mod = selectedMod ?: return
        // 和列表使用相同选择快照，详情内切换后立即更新状态。
        val enabled = remember(mod, enabledChanged) { mod.isEnabled }
        val clipboard = LocalClipboardManager.current
        var copied by remember(mod.id) { mutableStateOf(false) }
        AnimatedAlertDialog(visible = true, onDismissRequest = { selectedMod = null }) { dismiss ->
            BorderCard(
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = 600.dp).testTag("mod-details"),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SelectionContainer {
                            Text(mod.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(readI18n("mod.${if (enabled) "enabled" else "disabled"}"), Modifier.weight(1f))
                            UiSwitch(enabled, { changeModEnabled(mod, it) }, enabled = !isReloading)
                        }
                        Text(
                            listOfNotNull(sourceTypeText(mod),
                                readI18n("mod.ramUsage", I18nType.RWPP, mod.getRamUsed()),
                                readI18n("mod.networkMod").takeIf { mod.isNetworkMod }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (mod.hasTitleWarning()) {
                            Text(missingTitleHint(File(mod.path).name), color = ModCardWarnBorder, style = MaterialTheme.typography.bodySmall)
                        }
                        mod.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .4f)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(readI18n("mod.errorLoadTitle"), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleSmall)
                                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(error)); copied = true }) {
                                        Text(readI18n(if (copied) "mod.errorCopied" else "mod.errorCopy"))
                                    }
                                }
                            }
                        }
                        if (mod.description.isNotBlank()) {
                            SelectionContainer { Text(mod.description.trim(), style = MaterialTheme.typography.bodyMedium) }
                        }
                        SelectionContainer {
                            Text(mod.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                selectedMod = null
                                pendingDeleteMod = mod
                            },
                            enabled = !isReloading,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("mod-delete"),
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(readI18n("mod.delete"), color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { dismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(readI18n("common.close"))
                        }
                    }
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
            key = { File(data[it].path).absolutePath }
        ) { index ->
            val mod = data[index]
            val cardStatus = remember(
                updated,
                enabledChanged,
                loadedEnabledPaths,
                mod.id,
                mod.isEnabled,
            ) {
                resolveModCardStatus(mod, loadedEnabledPaths, canonicalPaths)
            }
            BorderCard(
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                    if (mod.isEnabled) .72f else .5f
                ),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, modCardStatusColor(cardStatus)),
                modifier = Modifier.then(
                    if (uiAnimationsEnabled())
                        Modifier.animateItem()
                    else Modifier
                )
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 2.dp, vertical = 3.dp)
                    .testTag("mod-row-${mod.id}")
            ) {
                ModRow(mod, status = cardStatus)
            }
        }
    }

    @Composable
    fun Header(isEnabledList: Boolean, count: Int) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    readI18n("mod.${if (isEnabledList) "enabled" else "disabled"}"),
                    style = MaterialTheme.typography.titleMedium,
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
        Column(modifier = modifier.fillMaxHeight().testTag(if (isEnabledList) "mods-list-enabled" else "mods-list-disabled")) {
            Header(isEnabledList, data.size)
            LazyColumnScrollbar(
                listState = state,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp),
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
        var moreExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().testTag("mods-actions").padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(
                onClick = {
                    if (importProgress == null) {
                        appKoin.get<ExternalHandler>().openFileChooser(
                            onProgress = { updateFileChooseProgress(it) },
                        ) { file -> importModFile(file) }
                    }
                },
                enabled = fileLoad.isReady && !isReloading && importProgress == null,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(painterResource(Res.drawable.file_open), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(readI18n("mod.importAction"), maxLines = 1)
            }
            Box {
                IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(48.dp).testTag("mods-more")) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = readI18n("mod.moreActions"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = moreExpanded && LocalDialogInteractive.current, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(readI18n("mod.disableAll")) },
                        enabled = fileLoad.isReady && !isReloading && enabledTotal > 0,
                        onClick = {
                            moreExpanded = false
                            mods.forEach { it.isEnabled = false }
                            enabledChanged = !enabledChanged
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { reload() },
                enabled = fileLoad.isReady && !isReloading && importProgress == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("mods-reload"),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(readI18n("mod.reload"), maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    val enabledNotLoaded = mods.filter { mod ->
                        mod.isEnabled && canonicalPaths[mod.path] !in loadedEnabledPaths
                    }
                    if (enabledNotLoaded.isNotEmpty()) {
                        UI.showWarning(
                            readI18n(
                                "mod.enabledNotLoaded",
                                I18nType.RWPP,
                                enabledNotLoaded.joinToString { it.name }
                            )
                        )
                        return@OutlinedButton
                    }

                    val needsUnitRebuild = deletedMod || mods.any { mod ->
                        !mod.isEnabled && canonicalPaths[mod.path] in loadedEnabledPaths
                    }
                    if (needsUnitRebuild) {
                        // 禁用/删除已加载模组后必须先重载：直接应用会在错误线程重建单位表，
                        // 与存活的对局世界并发导致单位贴图丢失（紫色 M 占位）
                        UI.showWarning(readI18n("mod.applyNeedReload"))
                        return@OutlinedButton
                    }

                    // 仅导入了默认禁用的模组时无需触碰引擎；文件继续保持未加载状态。
                    onExit()
                },
                enabled = fileLoad.isReady && !isReloading && !isClosingAfterDelete && importProgress == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("mods-apply"),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(readI18n("mod.apply"), maxLines = 1)
            }
        }
    }

    /** 堆耗尽后的拦截对话框：指名导致 OOM 的元凶模组，并说明重载已锁定（防二次 OOM 闪退）。 */
    @Composable
    fun MemoryExhaustedDialog() {
        AnimatedAlertDialog(
            visible = showMemoryExhaustedDialog,
            onDismissRequest = { showMemoryExhaustedDialog = false },
            enableDismiss = true
        ) { dismiss ->
            BorderCard(
                modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 420.dp).wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            readI18n("mod.memoryExhaustedTitle"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1
                        )
                    }

                    Text(
                        if (oomCulpritMods.isEmpty()) {
                            readI18n("mod.memoryExhaustedMessageUnknown")
                        } else {
                            readI18n(
                                "mod.memoryExhaustedMessage",
                                I18nType.RWPP,
                                oomCulpritMods.joinToString("、")
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        RWTextButton(readI18n("common.ok"), onClick = dismiss)
                    }
                }
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
                        color = MaterialTheme.colorScheme.onSurface
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

    @Composable
    fun FailedModsReloadDialog() {
        val failedMods = failedModsAfterReload
        AnimatedAlertDialog(
            visible = failedMods != null,
            onDismissRequest = { failedModsAfterReload = null },
            enableDismiss = true
        ) { dismiss ->
            val items = failedMods ?: return@AnimatedAlertDialog
            BorderCard(
                modifier = Modifier
                    .fillMaxWidth(LargeProportion())
                    .widthIn(max = 520.dp)
                    .wrapContentHeight()
                    .padding(10.dp),
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                val listScrollState = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        readI18n("mod.reloadFailedTitle"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        readI18n("mod.reloadFailedMessage", I18nType.RWPP, items.size.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 280.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(listScrollState)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "• ${item.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        item.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        readI18n("mod.reloadFailedHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RWTextButton(readI18n("common.ok"), onClick = dismiss)
                }
            }
        }
    }

    @Composable
    fun SingleModList(modifier: Modifier = Modifier) {
        val state = rememberLazyListState()
        val visibleMods = when (listFilter) {
            ModListFilter.All -> filteredMods
            ModListFilter.Enabled -> enabledMods
            ModListFilter.Disabled -> disabledMods
        }
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModListFilter.entries.forEach { option ->
                    val label = readI18n(when (option) {
                        ModListFilter.All -> "mod.total"
                        ModListFilter.Enabled -> "mod.enabled"
                        ModListFilter.Disabled -> "mod.disabled"
                    })
                    val count = when (option) {
                        ModListFilter.All -> filteredMods.size
                        ModListFilter.Enabled -> enabledMods.size
                        ModListFilter.Disabled -> disabledMods.size
                    }
                    Surface(
                        modifier = Modifier.weight(1f).testTag("mods-filter-${option.name}")
                            .selectable(listFilter == option, role = Role.Tab) { listFilter = option },
                        shape = RoundedCornerShape(8.dp),
                        color = if (listFilter == option) MaterialTheme.colorScheme.primary.copy(alpha = .18f)
                            else Color.Transparent,
                    ) {
                        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "$label $count",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (listFilter == option) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            LazyColumnScrollbar(
                listState = state,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("mods-list-all"),
                thickness = ModListScrollbarThickness,
                padding = ModListScrollbarPadding,
                selectionActionable = ScrollbarSelectionActionable.WhenVisible,
                showItemIndicator = ListIndicatorSettings.Disabled,
            ) {
                LazyColumn(
                    state = state, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 6.dp, end = ModListScrollbarReservedWidth, bottom = 8.dp),
                ) { ModList(visibleMods) }
            }
        }
    }

    @Composable
    fun ModsBody(singleColumn: Boolean, modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxSize()) {
            ModsTopBar()
            if (!fileLoad.isReady) {
                PageFileLoading(
                    state = fileLoad,
                    pageTitle = readI18n("common.mod"),
                    onRetry = { scope.launch { loadModFiles(modManager.getAllMods()) } },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else if (singleColumn) {
                SingleModList(Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModListPanel(isEnabledList = true, data = enabledMods, modifier = Modifier.weight(1f))
                    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    ModListPanel(isEnabledList = false, data = disabledMods, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { ActionBar() }
    ) { paddingValues ->
        // 手机横屏保留双栏，窄窗口使用全宽列表；所有尺寸都把高度留给模组。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .modFileDropTarget(
                    enabled = canAcceptModFileDrop,
                    onDragStarted = { modDropOverlayActive = true },
                    onDragEnded = {
                        modDropOverlayActive = false
                        modDropPointerInRoot = Offset.Unspecified
                    },
                    onDragPointerInRoot = { modDropPointerInRoot = it },
                    onFilesDropped = { files ->
                        val selection = analyzeRwmodFileDrop(files)
                        selection.importCandidate?.let {
                            importModFile(it, ignoredRwmods = (selection.rwmodCount - 1).coerceAtLeast(0))
                        }
                    },
                )
        ) {
            val singleColumn = maxWidth < 600.dp
            Box(modifier = Modifier.fillMaxSize()) {
                if (embedded) {
                    ModsBody(singleColumn = singleColumn, modifier = Modifier.fillMaxSize())
                } else {
                    ExpandedCard {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ModsBody(
                                singleColumn = singleColumn,
                                modifier = Modifier.fillMaxSize(),
                            )
                            ExitButton { exit() }
                        }
                    }
                }
                ModDropOverlay(
                    visible = modDropOverlayActive,
                    dragPointerInRoot = modDropPointerInRoot,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    ModDetailsDialog()
    DeleteModConfirmDialog()
    FailedModsReloadDialog()
    MemoryExhaustedDialog()
}
