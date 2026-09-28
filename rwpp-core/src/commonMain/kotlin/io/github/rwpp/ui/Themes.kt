/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.appKoin
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.ThemeInstallResult
import io.github.rwpp.theme.parseColorHex
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * 主题美术包管理页：导入 `.rwtheme`、启用/停用（单选语义，热切换）、删除。
 * 入口在设置页「主题」分组。
 */
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ThemesView(
    onExit: () -> Unit
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
    AnimatedAlertDialog(importing, onDismissRequest = { }) { _ ->
        BorderCard(modifier = Modifier.size(320.dp, 160.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    readI18n("themes.importing", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // 导入结果（成功含警告 / 失败含原因）
    importResult?.let { result ->
        AnimatedAlertDialog(true, onDismissRequest = { importResult = null }) { _ ->
            BorderCard(modifier = Modifier.width(420.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
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
            BorderCard(modifier = Modifier.width(420.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
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

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                RWTextButton(
                    readI18n("themes.import", I18nType.RWPP),
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.file_open),
                            null,
                            modifier = Modifier.size(30.dp)
                        )
                    },
                    modifier = Modifier.padding(5.dp)
                ) {
                    appKoin.get<ExternalHandler>().openFileChooser { file ->
                        if (file.extension != "rwtheme") {
                            UI.showWarning(readI18n("themes.invalidFile", I18nType.RWPP))
                            return@openFileChooser
                        }
                        scope.launch {
                            importing = true
                            val result = runCatching { ArtThemeController.import(file) }
                                .getOrElse {
                                    ThemeInstallResult(
                                        false,
                                        errors = listOf(it.message ?: "Unknown error")
                                    )
                                }
                            importing = false
                            importResult = result
                        }
                    }
                }

                RWTextButton(
                    readI18n("themes.refresh", I18nType.RWPP),
                    modifier = Modifier.padding(5.dp)
                ) { ArtThemeController.scan() }
            }
        }
    ) {
        BorderCard(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Box {
                ExitButton(onExit)
                Column(modifier = Modifier.fillMaxSize().padding(top = 34.dp)) {
                    Text(
                        readI18n("themes.title", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Text(
                        readI18n("themes.activeHint", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    if (themes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                readI18n("themes.empty", I18nType.RWPP),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        val state = rememberLazyListState()
                        LazyColumnScrollbar(
                            listState = state,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = state,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(themes, key = { it.id }) { theme ->
                                    ThemeCard(
                                        theme = theme,
                                        active = theme.id == activeId,
                                        onToggle = {
                                            ArtThemeController.apply(if (theme.id == activeId) null else theme.id)
                                        },
                                        onDelete = { deleteTarget = theme }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ArtThemeController.LoadedTheme,
    active: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    BorderCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：包内 icon.png，缺失时用主色圆形占位
            val iconFile = theme.iconFile
            if (iconFile != null) {
                AsyncImage(
                    model = iconFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        theme.spec.theme.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (theme.spec.theme.version.isNotBlank()) {
                        Text(
                            "  v${theme.spec.theme.version}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (active) {
                        Text(
                            "  ${readI18n("themes.inUse", I18nType.RWPP)}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                val metaLine = buildString {
                    if (theme.spec.theme.author.isNotBlank()) append(theme.spec.theme.author)
                    if (theme.spec.theme.description.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(theme.spec.theme.description)
                    }
                }
                if (metaLine.isNotBlank()) {
                    Text(
                        metaLine,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 配色预览色板（取前 6 个合法色值）
                val swatches = theme.spec.colors.entries.mapNotNull { (role, hex) ->
                    if (role in io.github.rwpp.theme.SUPPORTED_COLOR_ROLES) {
                        parseColorHex(hex)?.let { Color(it) }
                    } else null
                }.take(6)
                if (swatches.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        swatches.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }
                    }
                }
            }

            RWTextButton(
                if (active) readI18n("themes.disable", I18nType.RWPP)
                else readI18n("themes.enable", I18nType.RWPP),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) { onToggle() }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = readI18n("themes.delete", I18nType.RWPP),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
