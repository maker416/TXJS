/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModInfoParser
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

private class UnloadedMod(private val file: File) : Mod {
    private val metadata = ModInfoParser.parseFromRwmod(file)
    override val id: Int = -(file.absolutePath.hashCode() and 0x7FFFFFFF) - 1
    override val name: String get() = metadata.name
    override val description: String get() = metadata.description
    override val minVersion: String get() = metadata.minVersion
    override val errorMessage: String?
        get() = if (metadata.titleMissing) missingTitleError(file.name) else null
    override var isEnabled: Boolean = false
    override val path: String = file.absolutePath
    override fun getRamUsed(): String = "0"
    override fun getSize(): Long = file.length()
    override fun getBytes(): ByteArray = file.readBytes()
}

private fun missingTitleError(fileName: String): String =
    readI18n("mod.missingTitle", I18nType.RWPP, fileName)

/** 包装 [Mod]，把 title 缺失错误透传到 [Mod.errorMessage]，其余行为完全委托。 */
private class TitleErrorMod(delegate: Mod, private val error: String) : Mod by delegate {
    override val errorMessage: String get() = error
}

/**
 * 校验所有模组的 mod-info.txt `[mod]` title：缺失时包装为错误模组，
 * 列表中显示错误标识，重载后也会进入失败列表（拿不到模组名是大问题，必须显式报错）。
 */
private fun List<Mod>.withTitleErrors(): List<Mod> = map { mod ->
    // 引擎加载错误与 UnloadedMod 自带的 title 检查已覆盖的情况不重复解析
    if (mod.errorMessage != null) return@map mod
    val file = File(mod.path)
    val meta = ModInfoParser.parseFromModFile(file) ?: return@map mod
    if (meta.titleMissing) TitleErrorMod(mod, missingTitleError(file.name)) else mod
}

private fun scanUnloadedMods(existing: List<Mod>): List<Mod> {
    val existingNames = existing.map { File(it.path).name.lowercase() }.toSet()
    val result = mutableListOf<Mod>()
    File(modDir).listFiles()?.forEach { file ->
        if (file.isFile && file.extension.equals("rwmod", ignoreCase = true)
            && file.name.lowercase() !in existingNames
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

private data class FailedModLoadInfo(
    val name: String,
    val errorMessage: String,
)

private fun collectFailedMods(mods: List<Mod>): List<FailedModLoadInfo> {
    return mods.mapNotNull { mod ->
        // 元数据校验错误（mod-info.txt 缺 title）不属于"加载失败"：禁用模组本就不会被
        // 引擎解析，缺 title 也不影响启用模组的单位加载。该类提示保留在模组卡片上展示，
        // 不进此列表——否则禁用模组会被误报为"仍保持启用但单位未成功加载"。
        if (mod is TitleErrorMod || mod is UnloadedMod) return@mapNotNull null
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
    /** 嵌入 [ModsAndMapsView] 共用外壳时为 true：不渲染 ExpandedCard / 分段 / 关闭按钮。 */
    embedded: Boolean = false,
) {
    val modManager = koinInject<ModManager>()
    val settings = koinInject<Settings>()

    var deletedMod by remember { mutableStateOf(false) }
    val initialEngineMods = remember { modManager.getAllMods().withTitleErrors() }
    val mods = remember {
        SnapshotStateList<Mod>().apply {
            addAll(initialEngineMods)
            addAll(scanUnloadedMods(initialEngineMods))
        }
    }
    var loadedEnabledFileNames by remember {
        mutableStateOf(
            initialEngineMods
                .filter { it.isEnabled }
                .map { File(it.path).name.lowercase() }
                .toSet()
        )
    }
    var filter by remember { mutableStateOf("") }

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

    suspend fun reloadMods(): List<FailedModLoadInfo> {
        val knownStates = mods.associate { File(it.path).name.lowercase() to it.isEnabled }

        withContext(Dispatchers.IO) {
            // 在引擎加载单位定义之前应用开关，未启用的新模组不会解析单位（避免浪费时间）
            modManager.modReload(enabledByFileName = knownStates)
        }
        mods.clear()
        val engineMods = modManager.getAllMods().withTitleErrors()
        // 再同步一次 UI 侧状态（与加载前写入引擎的状态一致）
        engineMods.forEach { mod ->
            val fileName = File(mod.path).name.lowercase()
            mod.isEnabled = knownStates[fileName] ?: false
        }
        loadedEnabledFileNames = engineMods
            .filter { it.isEnabled }
            .map { File(it.path).name.lowercase() }
            .toSet()

        mods.addAll(engineMods)
        mods.addAll(scanUnloadedMods(engineMods))
        updated = !updated
        enabledChanged = !enabledChanged
        // 重载已完成单位表重建，删除标记随之失效（否则“应用”会被永久拦截）
        deletedMod = false
        return collectFailedMods(engineMods)
    }

    /** 进程内重载。OOM 由平台 runReloadCore 捕获并置位全局标志，此处负责归因与弹窗。 */
    fun doReloadInProcess() {
        scope.launch {
            try {
                val failed = reloadMods()
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
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
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
        if (isClosingAfterDelete) return

        // 堆已耗尽时跳过删除后的重载：文件已删，下次启动引擎会干净重建，直接退出即可。
        if (!deletedMod || UI.modReloadMemoryExhausted) {
            onExit()
            return
        }

        isClosingAfterDelete = true
        scope.launch {
            // 删除后的重建同样要全量解析所有启用模组；若中途撞 OOM 由平台 catch 兜底
            // （置位全局标志，不闪退），开关状态已随重载流程落盘，下次启动干净重建。
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

    /**
     * 重复导入时确保既有模组在列表中可见：
     * - 清空搜索关键字，避免模组被当前过滤条件隐藏；
     * - 列表中没有该文件时（例如绕开导入流程直接放进 units/ 的文件），以 UnloadedMod 补入。
     */
    fun revealExistingMod(target: File) {
        filter = ""
        val alreadyListed = mods.any { File(it.path).name.equals(target.name, ignoreCase = true) }
        if (!alreadyListed && target.isFile) {
            mods.add(UnloadedMod(target))
        }
        updated = !updated
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

            // 目标已存在（重复导入）：不复制，确保列表中能看到既有模组，并给出明确提示。
            // 原实现直接展示 FileAlreadyExistsException 的英文异常信息；且当文件经导入流程
            // 之外的途径进入 units/（手动复制、资源浏览器下载、联机同步激活）时列表中并没有它，
            // 造成「提示已导入但界面看不到该模组」。
            if (target.exists()) {
                revealExistingMod(target)
                importProgress = null
                UI.showWarning(readI18n("mod.importAlreadyExists", I18nType.RWPP, file.name))
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
                val enginePaths = mods.map { File(it.path).name }.toSet()
                if (file.name !in enginePaths) {
                    mods.add(UnloadedMod(target))
                    updated = !updated
                }
                UI.showWarning(readI18n("mod.importSuccess", I18nType.RWPP, file.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileAlreadyExistsException) {
                // 预检查与复制之间存在竞态（复制期间目标被其他流程创建），按重复导入处理。
                revealExistingMod(target)
                UI.showWarning(readI18n("mod.importAlreadyExists", I18nType.RWPP, file.name))
            } catch (e: Throwable) {
                UI.showWarning(e.message ?: "Unknown error")
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
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = .14f),
            border = BorderStroke(1.dp, color.copy(alpha = .45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                Text(
                    value,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    fun SummaryStrip() {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
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
    fun ModsTopBar(modifier: Modifier = Modifier) {
        // 顶栏固定单行：分段/标题 + 统计 + 搜索图标（展开后同排输入框），高度留给左右模组列表
        var searchExpanded by remember { mutableStateOf(filter.isNotBlank()) }
        LaunchedEffect(filter) {
            if (filter.isNotBlank()) searchExpanded = true
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    top = if (embedded) 8.dp else 12.dp,
                    end = if (embedded) 10.dp else 46.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onTabChange != null) {
                ModsMapsSegmentedControl(
                    selected = selectedTab,
                    onSelect = onTabChange,
                    modifier = Modifier.widthIn(min = 140.dp, max = 220.dp),
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

            if (searchExpanded) {
                FilterField(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        filter = ""
                        searchExpanded = false
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SummaryStrip()
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { searchExpanded = true },
                    modifier = Modifier.size(36.dp),
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

    @Composable
    fun ModCard(mod: Mod, dense: Boolean = false) {
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
        val clipboardManager = LocalClipboardManager.current
        var showErrorDialog by remember(mod.id) { mutableStateOf(false) }
        var errorCopied by remember(mod.id) { mutableStateOf(false) }
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

        val thumbSize = if (dense) 48.dp else 72.dp
        val cardPad = if (dense) 6.dp else 10.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(cardPad),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(thumbSize),
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

            Spacer(modifier = Modifier.width(if (dense) 8.dp else 10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        mod.name,
                        style = if (dense) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
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
                    // dense 下状态已由左右分栏表达，省略状态 pill
                    if (!dense) {
                        MetadataPill(statusText, statusColor)
                    }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                errorCopied = false
                                showErrorDialog = true
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp).padding(top = 2.dp),
                        )
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            readI18n("mod.errorViewDetails"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                if (description.isNotBlank()) {
                    ExpandableText(
                        text = description,
                        collapsedMaxLine = if (dense) 1 else 2,
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
                modifier = Modifier.width(if (dense) 56.dp else 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (dense) 4.dp else 8.dp)
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

        if (errorMessage != null) {
            AnimatedAlertDialog(
                visible = showErrorDialog,
                onDismissRequest = { showErrorDialog = false },
            ) { dismiss ->
                BorderCard(
                    modifier = Modifier
                        .fillMaxWidth(LargeProportion())
                        .widthIn(max = 560.dp)
                        .padding(10.dp),
                ) {
                    val errorScrollState = rememberScrollState()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                readI18n("mod.errorLoadTitle"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            mod.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 320.dp)
                                .padding(horizontal = 14.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(errorScrollState)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            RWTextButton(
                                if (errorCopied) readI18n("mod.errorCopied") else readI18n("mod.errorCopy"),
                            ) {
                                clipboardManager.setText(AnnotatedString(errorMessage))
                                errorCopied = true
                            }
                            RWTextButton(readI18n("common.close")) { dismiss() }
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

    fun LazyListScope.ModList(data: List<Mod>, dense: Boolean = false) {
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
                    .padding(horizontal = 4.dp, vertical = if (dense) 3.dp else 5.dp)
            ) {
                ModCard(mod, dense = dense)
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
        denseCards: Boolean = false,
        modifier: Modifier = Modifier
    ) {
        val state = rememberLazyListState()
        Column(modifier = modifier.fillMaxHeight()) {
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
                    ModList(data, dense = denseCards)
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
                val enabledNotLoaded = mods.filter { mod ->
                    mod.isEnabled && File(mod.path).name.lowercase() !in loadedEnabledFileNames
                }
                if (enabledNotLoaded.isNotEmpty()) {
                    UI.showWarning(
                        readI18n(
                            "mod.enabledNotLoaded",
                            I18nType.RWPP,
                            enabledNotLoaded.joinToString { it.name }
                        )
                    )
                    return@RWTextButton
                }

                val needsUnitRebuild = deletedMod || mods.any { mod ->
                    !mod.isEnabled && File(mod.path).name.lowercase() in loadedEnabledFileNames
                }
                if (needsUnitRebuild) {
                    // 禁用/删除已加载模组后必须先重载：直接应用会在错误线程重建单位表，
                    // 与存活的对局世界并发导致单位贴图丢失（紫色 M 占位）
                    UI.showWarning(readI18n("mod.applyNeedReload"))
                    return@RWTextButton
                }

                // 仅导入了默认禁用的模组时无需触碰引擎；文件继续保持未加载状态。
                onExit()
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
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
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
    fun ModsBody(denseCards: Boolean, modifier: Modifier = Modifier) {
        // 始终左右分栏：左=已启用，右=未启用；顶栏按内容高度，剩余全给列表
        Column(modifier = modifier.fillMaxSize()) {
            ModsTopBar()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModListPanel(
                    isEnabledList = true,
                    data = enabledMods,
                    denseCards = denseCards,
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
                    denseCards = denseCards,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { ActionBar() }
    ) { paddingValues ->
        // 始终左右分栏；窄屏仅收紧卡片密度，不再跌回上下堆叠
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val denseCards = maxWidth < 840.dp
            if (embedded) {
                ModsBody(denseCards = denseCards, modifier = Modifier.fillMaxSize())
            } else {
                ExpandedCard {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ModsBody(denseCards = denseCards, modifier = Modifier.fillMaxSize())
                        ExitButton { exit() }
                    }
                }
            }
        }
    }

    DeleteModConfirmDialog()
    FailedModsReloadDialog()
    MemoryExhaustedDialog()
}
