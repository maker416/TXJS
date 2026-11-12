/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.platform.themeFontFamily
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.RoomPlayerSpec
import io.github.rwpp.theme.RoomThemeSpec
import io.github.rwpp.theme.isSafeThemeAssetPath
import io.github.rwpp.theme.parseColorHex
import io.github.rwpp.widget.v2.bounceClick
import java.io.File

/** The renderer consumes only appearance fields; all room geometry stays in MultiplayerRoom. */
@Composable
internal fun roomThemeSpec(): RoomThemeSpec {
    val theme = ArtThemeController.activeTheme
    return remember(theme) { theme?.spec?.room?.sanitized() ?: RoomThemeSpec() }
}

/** Resolve files within the active pack, including protection against symlinks escaping its directory. */
internal fun roomThemeAsset(path: String): File? {
    if (path.isBlank() || !isSafeThemeAssetPath(path)) return null
    val dir = ArtThemeController.activeTheme?.dir ?: return null
    return runCatching {
        val root = dir.canonicalFile
        File(root, path).canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
    }.getOrNull()
}

internal fun roomThemeColor(value: String, fallback: Color): Color =
    parseColorHex(value)?.let { Color(it) } ?: fallback

/** Compose treats a zero-width border as a hairline; the pack's zero means no border at all. */
internal fun Modifier.roomThemeBorder(width: Int, color: Color, shape: Shape): Modifier =
    if (width > 0) border(width.dp, color, shape) else this

/**
 * Draw behind existing content without consulting image intrinsic dimensions during measurement.
 * A missing, loading or corrupt image leaves the fallback color in place. The keyed painter
 * cannot continue drawing a previous pack after a theme switch.
 */
@Composable
internal fun Modifier.roomThemePaint(
    image: String,
    background: Color,
    shape: Shape,
    opacity: Int = 100,
    scale: ContentScale = ContentScale.Crop,
): Modifier {
    val theme = ArtThemeController.activeTheme
    val file = remember(theme, image) { roomThemeAsset(image) }
    val painter = if (file == null) null else key(theme, file) { rememberAsyncImagePainter(file) }
    val alpha = opacity.coerceIn(0, 100) / 100f
    return clip(shape)
        .background(background.copy(alpha = background.alpha * alpha))
        .drawWithContent {
            if (painter != null && size.width > 0f && size.height > 0f) {
                val intrinsic = painter.intrinsicSize
                val source = if (intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                    intrinsic.width > 0f && intrinsic.height > 0f) intrinsic else size
                val factor = scale.computeScaleFactor(source, size)
                val painted = Size(source.width * factor.scaleX, source.height * factor.scaleY)
                translate((size.width - painted.width) / 2f, (size.height - painted.height) / 2f) {
                    with(painter) { draw(painted, alpha) }
                }
            }
            drawContent()
        }
}

/** Decorate an existing details/actions node; structural fields never enter this modifier. */
@Composable
internal fun Modifier.roomThemePanelDecoration(id: String, tag: String = "room-panel-$id"): Modifier {
    val tagged = testTag(tag)
    val style = roomThemeSpec().panels[id] ?: return tagged
    val shape = RoundedCornerShape(style.corner.dp)
    return tagged.roomThemePaint(style.backgroundImage,
        roomThemeColor(style.backgroundColor, Color.Transparent), shape, style.opacity)
        .roomThemeBorder(style.borderWidth,
            roomThemeColor(style.borderColor, MaterialTheme.colorScheme.secondaryContainer), shape)
}

/** Overlay images use their parent's assigned size and never define the room's bounds. */
@Composable
internal fun RoomThemeImage(
    path: String,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    scale: ContentScale = ContentScale.Crop,
) {
    val theme = ArtThemeController.activeTheme
    val file = remember(theme, path) { roomThemeAsset(path) } ?: return
    key(theme, file) {
        AsyncImage(file, contentDescription = null, modifier = modifier, contentScale = scale, alpha = alpha)
    }
}

@Composable
internal fun RoomThemeBackground(modifier: Modifier = Modifier) {
    val style = roomThemeSpec().background
    Box(modifier.testTag("room-theme-background")) {
        RoomThemeImage(style.image, Modifier.matchParentSize(), style.alpha / 100f,
            when (style.scale) {
                "fit" -> ContentScale.Fit
                "fill" -> ContentScale.FillBounds
                else -> ContentScale.Crop
            })
        if (style.overlayColor.isNotEmpty() && style.overlayAlpha > 0) {
            val color = roomThemeColor(style.overlayColor, Color.Transparent)
            Box(Modifier.matchParentSize().background(color.copy(alpha = color.alpha * style.overlayAlpha / 100f)))
        }
    }
}

/** Preserve old explicit onSurface colors while allowing a panel-specific foreground. */
@Composable
internal fun RoomThemeContentColor(value: String, content: @Composable () -> Unit) {
    val parsed = parseColorHex(value)
    val foreground = parsed?.let { Color(it) } ?: LocalContentColor.current
    val inheritedColors = MaterialTheme.colorScheme
    val colors = if (parsed == null) inheritedColors
        else inheritedColors.copy(onSurface = foreground, onSurfaceVariant = foreground)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides foreground, content = content)
    }
}

/** Same Card and ColumnScope as BorderCard, with no additional layout node or padding. */
@Composable
internal fun RoomThemeCard(
    id: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.background.copy(UI.backgroundTransparency),
    shape: Shape = RoundedCornerShape(20.dp),
    border: BorderStroke = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainer),
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = roomThemeSpec().panels[id]
    val tagged = modifier.testTag("room-panel-$id")
    val resolvedShape = style?.let { RoundedCornerShape(it.corner.dp) } ?: shape
    val resolvedBackground = roomThemeColor(style?.backgroundColor.orEmpty(), backgroundColor)
    val resolvedBorder = if (style == null) border else if (style.borderWidth == 0) null
        else BorderStroke(style.borderWidth.dp,
            roomThemeColor(style.borderColor, (border.brush as? SolidColor)?.value ?: MaterialTheme.colorScheme.surfaceContainer))
    val painted = if (style == null) tagged
        else tagged.roomThemePaint(style.backgroundImage, resolvedBackground, resolvedShape, style.opacity)
    RoomThemeContentColor(style?.contentColor.orEmpty()) {
        val colors = CardDefaults.cardColors(containerColor = if (style == null) backgroundColor else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface)
        if (onClick == null) {
            Card(modifier = painted, shape = resolvedShape, border = resolvedBorder, colors = colors, content = content)
        } else {
            Card(onClick = onClick, modifier = painted, shape = resolvedShape, border = resolvedBorder, colors = colors, content = content)
        }
    }
}

/** Keep RWTextButton's original content, spacing, elevation and click/long-click behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomThemeTextButton(
    id: String,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val style = roomThemeSpec().buttons[id]
    val tagged = modifier.testTag("room-action-$id")
    val shape = RoundedCornerShape(style?.corner?.dp ?: 10.dp)
    val background = roomThemeColor(style?.backgroundColor.orEmpty(), MaterialTheme.colorScheme.surface)
    val foreground = roomThemeColor(style?.contentColor.orEmpty(), MaterialTheme.colorScheme.onSurface)
    val stroke = if (style == null) BorderStroke(5.dp, MaterialTheme.colorScheme.surfaceContainer)
        else if (style.borderWidth == 0) null else BorderStroke(style.borderWidth.dp,
            roomThemeColor(style.borderColor, MaterialTheme.colorScheme.surfaceContainer))
    val painted = if (style == null) tagged else tagged.roomThemePaint(style.backgroundImage, background, shape, style.opacity)
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(Color.Transparent, RippleAlpha(0f, 0f, 0f, 0f)),
        LocalContentColor provides foreground,
    ) {
        Card(
            border = stroke,
            colors = CardDefaults.cardColors(containerColor = if (style == null) background else Color.Transparent,
                contentColor = foreground),
            shape = shape,
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = painted.bounceClick(onLongClick = onLongClick, onClick = onClick),
        ) {
            Row(Modifier.padding(5.dp)) {
                leadingIcon?.invoke()
                Text(label, color = foreground, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(5.dp))
            }
        }
    }
}

@Composable
internal fun roomPlayerNameFont(): FontFamily? {
    val theme = ArtThemeController.activeTheme
    val style = roomThemeSpec().player
    return remember(theme, style.nameFont, style.nameFontBold) {
        themeFontFamily(roomThemeAsset(style.nameFont), roomThemeAsset(style.nameFontBold))
    }
}

/** Slot images use birth position, independent of the team's current display order. */
internal fun roomPlayerBackground(style: RoomPlayerSpec, player: Player, room: GameRoom): String =
    sequence {
        if (!player.isSpectator && player.spawnPoint >= 0) {
            style.slotImages[(player.spawnPoint + 1).toString()]?.let { yield(it) }
        }
        if (style.localImage.isNotBlank() && player == room.localPlayer) yield(style.localImage)
        if (style.hostImage.isNotBlank() && player.isRoomHost) yield(style.hostImage)
        if (style.aiImage.isNotBlank() && player.isAI) yield(style.aiImage)
        if (style.readyImage.isNotBlank() || style.notReadyImage.isNotBlank()) {
            yield(if (player.data.ready) style.readyImage else style.notReadyImage)
        }
        yield(style.backgroundImage)
    }.firstOrNull { roomThemeAsset(it) != null }.orEmpty()
