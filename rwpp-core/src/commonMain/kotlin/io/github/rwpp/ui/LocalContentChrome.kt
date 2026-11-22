/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.RWSelectionColors
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.animateUiColorAsState

internal val LocalContentWideBreakpoint = 600.dp
internal val LocalContentShortHeightBreakpoint = 440.dp

@Composable
internal fun localContentBarColor(): Color = Color.Black.copy(alpha = 0.22f)

@Composable
internal fun localContentHairlineColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)

@Composable
internal fun localContentRecessedColor(): Color = Color.Black.copy(alpha = 0.26f)

@Composable
internal fun localContentCardColor(elevated: Boolean = false): Color =
    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (elevated) 0.72f else 0.56f)

@Composable
internal fun localContentCardBorderColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)

/**
 * 本地内容页的会话级轻量缓存：
 * 保持在 [ModsAndMapsView] 生命周期内，离开页面时释放；
 * 在模组 / 地图 / 存档之间切换时避免重新扫描闪烁，同时让顶栏工具与内容状态保持同步。
 */
@Stable
internal class LocalContentSessionCache {
    var modFiles: ModFiles? by mutableStateOf(null)
    var modsFilter: String by mutableStateOf("")
    var modsSearchExpanded: Boolean by mutableStateOf(false)
    var modsListFilter: ModListFilter by mutableStateOf(ModListFilter.All)
    var modsBusy: Boolean by mutableStateOf(false)

    var customMaps: List<ManagedCustomMap>? by mutableStateOf(null)
    var mapsFilter: String by mutableStateOf("")
    var mapsVisibleCount: Int? by mutableStateOf(null)
    var mapsBusy: Boolean by mutableStateOf(false)

    var savedGames: List<ManagedSavedGame>? by mutableStateOf(null)
    var savesFilter: String by mutableStateOf("")
    var savesOldestFirst: Boolean by mutableStateOf(false)
    var savesVisibleCount: Int? by mutableStateOf(null)
    var savesBusy: Boolean by mutableStateOf(false)
    var savesReady: Boolean by mutableStateOf(false)

    val anyBusy: Boolean get() = modsBusy || mapsBusy || savesBusy
}

internal val LocalLocalContentSessionCache = compositionLocalOf<LocalContentSessionCache?> { null }

/**
 * 模组 / 地图 / 存档共用的统一顶栏外壳。
 * 宽屏（含手机横屏）单行排布，把垂直空间留给内容列表；
 * 窄屏（手机竖屏）固定为「导航 + 关闭」首行与紧凑工具次行，切换标签时首行位置与尺寸完全稳定。
 */
@Composable
internal fun LocalContentTopChrome(
    wide: Boolean,
    shortViewport: Boolean,
    navigation: @Composable (Modifier) -> Unit,
    wideTools: @Composable RowScope.() -> Unit,
    narrowTools: (@Composable RowScope.() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeEnabled: Boolean = true,
    reserveTrailingCloseSpace: Boolean = false,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = localContentBarColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().testTag("local-content-header"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (wide) {
                val verticalPad = if (shortViewport) 2.dp else 6.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = if (reserveTrailingCloseSpace && onClose == null) 56.dp else 12.dp,
                            top = verticalPad,
                            bottom = verticalPad,
                        )
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    navigation(Modifier)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        content = wideTools,
                    )
                    if (onClose != null) {
                        LocalContentCloseButton(onClick = onClose, enabled = closeEnabled)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = if (reserveTrailingCloseSpace && onClose == null) 56.dp else 12.dp,
                            top = 8.dp,
                            bottom = if (narrowTools != null) 6.dp else 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    navigation(Modifier.weight(1f))
                    if (onClose != null) {
                        LocalContentCloseButton(onClick = onClose, enabled = closeEnabled)
                    }
                }
                if (narrowTools != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = narrowTools,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 1.dp, color = localContentHairlineColor())
                if (busy) {
                    UiLinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalContentCloseButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier.size(40.dp).testTag("local-content-close"),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = readI18n("common.close"),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 模组 / 地图 / 存档共用的统一底部操作栏。
 */
@Composable
internal fun LocalContentBottomBar(
    shortViewport: Boolean = false,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = localContentBarColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().testTag("local-content-bottom-bar"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = localContentHairlineColor())
            Row(
                modifier = rowModifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = if (shortViewport) 3.dp else 6.dp,
                    )
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/**
 * 现代紧凑胶囊搜索框：固定 40dp 高度，聚焦时边框与图标平滑点亮，
 * 支持单手快速清空；[fieldModifier] 直接挂在 [BasicTextField] 上以保证语义与输入测试直达。
 */
@Composable
internal fun LocalContentSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateUiColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        },
        animationSpec = tween(160),
        label = "searchBorder",
    )
    val iconColor by animateUiColorAsState(
        targetValue = if (focused || value.isNotEmpty()) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(160),
        label = "searchIcon",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = localContentRecessedColor(),
        border = BorderStroke(if (focused) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .height(height)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) { focusRequester.requestFocus() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = if (value.isNotEmpty()) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CompositionLocalProvider(LocalTextSelectionColors provides RWSelectionColors) {
                    BasicTextField(
                        value = value,
                        onValueChange = { incoming ->
                            onValueChange(if (incoming.contains('\n')) incoming.substringAfterLast('\n') else incoming)
                        },
                        enabled = enabled,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .then(fieldModifier),
                    )
                }
            }
            if (value.isNotEmpty() && enabled) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalContentCountBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val bg = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val border = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val fg = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
internal fun LocalContentMetaPill(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
internal fun LocalContentToggleChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingPainter: Painter? = null,
    leadingIcon: ImageVector? = null,
    height: Dp = 40.dp,
) {
    val container by animateUiColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.52f)
        },
        animationSpec = tween(160),
        label = "chipBg",
    )
    val border by animateUiColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        },
        animationSpec = tween(160),
        label = "chipBorder",
    )
    val content by animateUiColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(160),
        label = "chipContent",
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = modifier.height(height),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leadingPainter != null) {
                Icon(
                    painter = leadingPainter,
                    contentDescription = null,
                    tint = if (enabled) content else content.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) content else content.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) content else content.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LocalContentPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingPainter: Painter? = null,
    leadingIcon: ImageVector? = null,
    minHeight: Dp = 44.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier.heightIn(min = minHeight),
    ) {
        if (leadingPainter != null) {
            Icon(leadingPainter, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun LocalContentSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingPainter: Painter? = null,
    leadingIcon: ImageVector? = null,
    minHeight: Dp = 44.dp,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
        modifier = modifier.heightIn(min = minHeight),
    ) {
        if (leadingPainter != null) {
            Icon(leadingPainter, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 精致的空状态视图：柔和圆形图标光晕 + 清晰文案 + 可选副提示与快捷操作按钮。
 */
@Composable
internal fun LocalContentEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    iconPainter: Painter? = null,
    iconVector: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (iconPainter != null || iconVector != null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconPainter != null) {
                        Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            modifier = Modifier.size(28.dp),
                        )
                    } else if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                LocalContentSecondaryButton(
                    text = actionLabel,
                    onClick = onAction,
                    minHeight = 40.dp,
                )
            }
        }
    }
}
