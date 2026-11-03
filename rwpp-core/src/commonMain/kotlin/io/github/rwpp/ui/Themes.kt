/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.appKoin
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.extension_30
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.GameArtOverlayManager
import io.github.rwpp.theme.ThemeInstallResult
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.UiCircularProgressIndicator
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.ListIndicatorSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * 主题美术包管理页：导入 `.rwtheme`、启用/停用（单选语义，热切换）、删除。
 * 直接嵌入扩展页；旧 Themes 路由沿用同一套管理界面。
 */
@Composable
fun ThemesView(
    title: String = readI18n("themes.title"),
    onExit: () -> Unit,
) {
    BackHandler(true, onExit)
    val scope = rememberCoroutineScope()

    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ThemeInstallResult?>(null) }
    var deleteTarget by remember { mutableStateOf<ArtThemeController.LoadedTheme?>(null) }

    LaunchedEffect(Unit) {
        ArtThemeController.ensureInitialized()
        ArtThemeController.scan()
    }

    val themes = ArtThemeController.installedThemes
    val activeId = ArtThemeController.activeTheme?.id

    // 导入中遮罩（不可关闭）
    AnimatedAlertDialog(importing, onDismissRequest = { }, enableDismiss = false) { _ ->
        BorderCard(modifier = Modifier.size(320.dp, 160.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UiCircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    readI18n("themes.importing", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // 游戏内贴图覆盖层应用进度（重建资源目录 + 单位重载，不可关闭）
    val applyingGameArt = GameArtOverlayManager.applying
    AnimatedAlertDialog(applyingGameArt, onDismissRequest = { }, enableDismiss = false) { _ ->
        BorderCard(modifier = Modifier.size(320.dp, 160.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UiCircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    readI18n("themes.applyingGameArt", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // 导入结果（成功含警告 / 失败含原因）
    importResult?.let { result ->
        AnimatedAlertDialog(true, onDismissRequest = { importResult = null }) { _ ->
            BorderCard(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(
                        if (result.success) {
                            readI18n("themes.importSuccess", I18nType.RWPP, result.themeName ?: "")
                        } else {
                            readI18n("themes.importFailed", I18nType.RWPP)
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val messages = result.errors + result.warnings
                    if (messages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        messages.forEach {
                            Text(
                                "• $it",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        RWTextButton(readI18n("common.close", I18nType.RWPP)) { importResult = null }
                    }
                }
            }
        }
    }

    // 删除确认
    deleteTarget?.let { target ->
        AnimatedAlertDialog(true, onDismissRequest = { deleteTarget = null }) { _ ->
            BorderCard(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(
                        readI18n("themes.deleteConfirmTitle", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        readI18n("themes.deleteConfirmBody", I18nType.RWPP, target.spec.theme.name),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        RWTextButton(readI18n("common.cancel", I18nType.RWPP)) { deleteTarget = null }
                        Spacer(modifier = Modifier.width(8.dp))
                        RWTextButton(readI18n("themes.delete", I18nType.RWPP)) {
                            ArtThemeController.delete(target.id)
                            deleteTarget = null
                        }
                    }
                }
            }
        }
    }

    val busy = importing || applyingGameArt
    val onImport: () -> Unit = {
        appKoin.get<ExternalHandler>().openFileChooser { file ->
            scope.launch {
                if (!file.extension.equals("rwtheme", ignoreCase = true)) {
                    importResult = ThemeInstallResult(false, errors = listOf(readI18n("themes.invalidFile")))
                    return@launch
                }
                importing = true
                try {
                    importResult = ArtThemeController.import(file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    importResult = ThemeInstallResult(false, errors = listOf(error.message ?: "Unknown error"))
                } finally {
                    importing = false
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            // 手机横屏由可用高度决定布局，不能只按宽度套用桌面的大预览卡。
            val compact = maxHeight < 520.dp
            val horizontalCards = compact && maxWidth >= 540.dp
            val columns = when {
                horizontalCards -> 1
                maxWidth >= 960.dp -> 3
                maxWidth >= 600.dp -> 2
                else -> 1
            }
            val narrow = maxWidth < 540.dp
            Column(Modifier.fillMaxSize()) {
                ThemeLibraryHeader(title, themes.size, narrow, busy, onImport)
                CurrentThemeCard(busy)
                GameArtOverlayManager.failure?.let { reason ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(readI18n("themes.gameArtFailed", I18nType.RWPP, reason),
                            Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { GameArtOverlayManager.syncAsync() }, enabled = !busy) {
                            Text(readI18n("themes.retryGameArt"))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                if (themes.isEmpty()) {
                    // 空状态直接适配剩余视口，不进入列表，避免空列表也需要来回滚动。
                    ThemeEmptyState(busy, compact, onImport, Modifier.weight(1f))
                } else {
                    val state = rememberLazyListState()
                    LazyColumnScrollbar(
                        listState = state,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        thickness = 4.dp,
                        padding = 3.dp,
                        showItemIndicator = ListIndicatorSettings.Disabled,
                    ) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize().testTag("theme-list"),
                            contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(themes.chunked(columns), key = { row -> row.first().id }) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    row.forEach { theme ->
                                        ThemeCard(
                                            theme = theme,
                                            active = theme.id == activeId,
                                            enabled = !busy,
                                            horizontal = horizontalCards,
                                            modifier = Modifier.weight(1f),
                                            onToggle = { ArtThemeController.apply(if (theme.id == activeId) null else theme.id) },
                                            onDelete = { deleteTarget = theme },
                                        )
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
            ExitButton(onExit)
        }
    }
}

/** 管理动作固定在页头；列表滚到底也不需要回到顶部才能导入或刷新。 */
@Composable
private fun ThemeLibraryHeader(title: String, count: Int, narrow: Boolean, busy: Boolean, onImport: () -> Unit) {
    val heading: @Composable RowScope.() -> Unit = {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Icon(painterResource(Res.drawable.extension_30), null,
                modifier = Modifier.padding(9.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${readI18n("themes.manageEntry")} · ${readI18n("themes.installedCount", I18nType.RWPP, count.toString())}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { ArtThemeController.scan() }, enabled = !busy, modifier = Modifier.testTag("theme-refresh")) {
                Icon(Icons.Default.Refresh, readI18n("themes.refresh"))
            }
            Button(onClick = onImport, enabled = !busy, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("theme-import"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(readI18n("themes.import"), color = LocalContentColor.current)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 52.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            heading()
            if (!narrow) actions()
        }
        if (narrow) Box(Modifier.align(Alignment.End)) { actions() }
    }
}

/** 当前外观缩成一行，恢复动作固定可见；为长主题名保留省略能力。 */
@Composable
private fun CurrentThemeCard(busy: Boolean) {
    val theme = ArtThemeController.activeTheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("${readI18n("themes.currentAppearance")} · ${theme?.spec?.theme?.name ?: readI18n("themes.builtIn")}",
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (theme != null) {
            TextButton(onClick = { ArtThemeController.apply(null) }, enabled = !busy,
                modifier = Modifier.widthIn(max = 140.dp).heightIn(min = 48.dp).testTag("theme-restore-default")) {
                Text(readI18n("themes.restoreDefault"), color = LocalContentColor.current, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ThemeEmptyState(busy: Boolean, compact: Boolean, onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp, Alignment.CenterVertically),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
            Icon(painterResource(Res.drawable.extension_30), null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(if (compact) 10.dp else 16.dp).size(if (compact) 24.dp else 36.dp))
        }
        Text(readI18n("themes.emptyTitle"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Text(readI18n("themes.empty"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.widthIn(max = 440.dp))
        OutlinedButton(onClick = onImport, enabled = !busy, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(readI18n("themes.importFirst"), color = LocalContentColor.current)
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ArtThemeController.LoadedTheme,
    active: Boolean,
    enabled: Boolean,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = modifier.testTag("theme-card-${theme.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThemePreview(theme, active, Modifier.width(148.dp).fillMaxHeight())
                ThemeCardContent(theme, active, enabled, horizontal, Modifier.weight(1f), onToggle, onDelete)
            }
        } else {
            Column {
                ThemePreview(theme, active, Modifier.fillMaxWidth().height(124.dp))
                ThemeCardContent(theme, active, enabled, horizontal, Modifier.fillMaxWidth(), onToggle, onDelete)
            }
        }
    }
}

@Composable
private fun ThemeCardContent(
    theme: ArtThemeController.LoadedTheme,
    active: Boolean,
    enabled: Boolean,
    horizontal: Boolean,
    contentModifier: Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    if (horizontal) {
        Row(contentModifier.padding(12.dp).heightIn(min = 100.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeCardDetails(theme, horizontal = true)
            }
            ThemeCardActions(theme.id, active, enabled, Modifier.width(160.dp), onToggle, onDelete)
        }
    } else {
        Column(contentModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCardDetails(theme, horizontal = false)
            ThemeCardActions(theme.id, active, enabled, Modifier.fillMaxWidth(), onToggle, onDelete)
        }
    }
}

@Composable
private fun ThemeCardDetails(theme: ArtThemeController.LoadedTheme, horizontal: Boolean) {
    Text(theme.spec.theme.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val metadata = listOf(theme.spec.theme.author, theme.spec.theme.version.takeIf { it.isNotBlank() }?.let { "v$it" }.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")
    if (metadata.isNotBlank()) {
        Text(metadata, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (theme.spec.theme.description.isNotBlank()) {
        Text(theme.spec.theme.description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            minLines = if (horizontal) 1 else 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (theme.gameOverlayDir != null) {
        Text(readI18n("themes.hasGameArt"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ThemeCardActions(
    id: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val toggleModifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme-toggle-$id")
        if (active) {
            OutlinedButton(onClick = onToggle, enabled = enabled, modifier = toggleModifier,
                shape = RoundedCornerShape(10.dp)) { Text(readI18n("themes.disable"), color = LocalContentColor.current) }
        } else {
            Button(onClick = onToggle, enabled = enabled, modifier = toggleModifier,
                shape = RoundedCornerShape(10.dp)) { Text(readI18n("themes.enable"), color = LocalContentColor.current) }
        }
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("theme-delete-$id")) {
            Icon(Icons.Default.Delete, readI18n("themes.delete"), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** 优先展示包内背景；无背景时显示由包配色生成的外观示意，始终保留可读的状态标签。 */
@Composable
private fun ThemePreview(theme: ArtThemeController.LoadedTheme, active: Boolean, modifier: Modifier) {
    val colors = theme.colorScheme
    Box(modifier.background(Brush.linearGradient(
        listOf(colors.primary.copy(alpha = 0.35f), colors.background, colors.secondary.copy(alpha = 0.2f))))) {
        if (theme.backgroundFile != null) {
            AsyncImage(theme.backgroundFile, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.align(Alignment.Center).width(100.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(3) { index ->
                    Box(Modifier.fillMaxWidth(if (index == 0) 0.7f else 1f).height(10.dp)
                        .clip(RoundedCornerShape(4.dp)).background(colors.primary.copy(alpha = if (index == 0) 0.8f else 0.3f)))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
        if (active) {
            Surface(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                    Text(readI18n("themes.inUse"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (theme.iconFile != null) {
                AsyncImage(theme.iconFile, null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(2.dp))
            }
            listOf(colors.primary, colors.secondary, colors.background).forEach { color ->
                Surface(modifier = Modifier.size(16.dp), shape = CircleShape, color = color,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {}
            }
        }
    }
}
