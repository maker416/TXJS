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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.MenuLayoutSpec
import io.github.rwpp.theme.MenuButtonSpec
import coil3.compose.AsyncImage
import io.github.rwpp.rwpp_core.generated.resources.*
import org.jetbrains.compose.resources.painterResource

private val ResourceText: Color
    @Composable get() = launcherThemeColor("onSurface", Color(0xFFF0F4F2))
private val ResourceMuted: Color
    @Composable get() = launcherThemeColor("onSurface", Color(0xFFA5B2AB))
private val ResourceAccent: Color
    @Composable get() = launcherThemeColor("primary", Color(0xFF90D4AE))

private val RESOURCE_CARD_IDS = setOf("resourceBrowser", "esports", "extensions")
private val RESOURCE_LAYOUT_FIELDS = setOf("orientation", "columns", "align", "offsetY", "widthPercent", "spacing")

private data class ResourceCardEntry(
    val id: String,
    val title: String,
    val description: String,
    val icon: Painter,
    val accent: Color,
    val onClick: () -> Unit,
)

/** 普通页面参与返回栈，子页关闭后回到这里；不创建原生弹窗或提前启动网页。 */
@Composable
internal fun ResourceCenterView(
    onExit: () -> Unit,
    onForum: () -> Unit,
    onEsports: () -> Unit,
    onExtensions: () -> Unit,
    extraMenus: List<UIProvider.Menu> = emptyList(),
) {
    BackHandler(true, onExit)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("resource-center")) {
        val compact = maxHeight < 480.dp
        val narrow = maxWidth < 600.dp
        val gutter = if (compact || narrow) 20.dp else 40.dp
        val headerHeight = if (compact) 56.dp else 76.dp
        val theme = ArtThemeController.activeTheme
        val layout = theme?.spec?.menu?.layout?.sanitized() ?: MenuLayoutSpec()
        val defaultLayout = MenuLayoutSpec()
        // 图片、描边和单卡宽度只改变外观；显式结构字段、排序或隐藏才重排资源卡片。
        val rearrange = theme?.spec?.explicitMenuLayoutFields.orEmpty().any { it in RESOURCE_LAYOUT_FIELDS } || layout.copy(
            buttonHeight = defaultLayout.buttonHeight,
            buttonCorner = defaultLayout.buttonCorner,
            showBorder = defaultLayout.showBorder,
        ) != defaultLayout || theme?.spec?.menu?.buttons.orEmpty().any { (id, button) ->
            id in RESOURCE_CARD_IDS && (button.order != 0 || button.span != 0 || button.hidden)
        }
        Box(Modifier.matchParentSize().background(launcherThemeColor("background", Color(0xED0D1411))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(launcherThemeColor("primaryContainer", Color(0x292C5540)), Color.Transparent, Color(0x33000000)),
        )))
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(headerHeight).padding(start = gutter - 12.dp, end = gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onExit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, readI18n("resources.back"), tint = ResourceText)
                }
                Text(readI18n("resources.title"), color = ResourceText, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("RWJS", color = ResourceMuted.copy(alpha = 0.55f), fontSize = 12.sp, letterSpacing = 3.sp)
                // 管理入口不参与主题槽位隐藏，确保任何主题都能被停用或替换。
                IconButton(onClick = onExtensions, modifier = Modifier.size(48.dp).testTag("resource-theme-management")) {
                    Icon(painterResource(Res.drawable.extension_30), readI18n("menu.extension"), tint = ResourceText)
                }
            }
            HorizontalDivider(color = launcherThemeColor("surfaceContainer", Color.White.copy(alpha = 0.07f)))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 1080.dp).fillMaxWidth().fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = if (compact) 12.dp else 28.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(readI18n("resources.heading"), color = ResourceText,
                        fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.SemiBold)
                    Text(readI18n("resources.subtitle"), color = ResourceMuted,
                        fontSize = if (compact) 12.sp else 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = if (compact) 16.dp else 28.dp))

                    if (rearrange) {
                        ResourceThemeCards(
                            listOf(
                                ResourceCardEntry("resourceBrowser", readI18n("browser.resourceBrowser"), readI18n("resources.forumDescription"),
                                    painterResource(Res.drawable.public_30), ResourceAccent, onForum),
                                ResourceCardEntry("esports", readI18n("browser.esportsSite"), readI18n("resources.esportsDescription"),
                                    painterResource(Res.drawable.swords_30), launcherThemeColor("secondary", Color(0xFFA6BEDC)), onEsports),
                                ResourceCardEntry("extensions", readI18n("menu.extension"), readI18n("resources.extensionsDescription"),
                                    painterResource(Res.drawable.extension_30), launcherThemeColor("tertiary", Color(0xFFC0B2DB)), onExtensions),
                            ), layout, compact,
                        )
                    } else if (narrow) {
                        ForumResourceCard(onForum, Modifier.fillMaxWidth(), compact = false)
                        Spacer(Modifier.height(16.dp))
                        SecondaryResourceCard(readI18n("browser.esportsSite"), readI18n("resources.esportsDescription"),
                            painterResource(Res.drawable.swords_30), launcherThemeColor("secondary", Color(0xFFA6BEDC)), onEsports,
                            Modifier.fillMaxWidth(), id = "esports")
                        Spacer(Modifier.height(12.dp))
                        SecondaryResourceCard(readI18n("menu.extension"), readI18n("resources.extensionsDescription"),
                            painterResource(Res.drawable.extension_30), launcherThemeColor("tertiary", Color(0xFFC0B2DB)), onExtensions,
                            Modifier.fillMaxWidth(), id = "extensions")
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ForumResourceCard(onForum, Modifier.weight(1.15f).fillMaxHeight(), compact)
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SecondaryResourceCard(readI18n("browser.esportsSite"), readI18n("resources.esportsDescription"),
                                    painterResource(Res.drawable.swords_30), launcherThemeColor("secondary", Color(0xFFA6BEDC)), onEsports,
                                    Modifier.weight(1f).fillMaxWidth(), compact, "esports")
                                SecondaryResourceCard(readI18n("menu.extension"), readI18n("resources.extensionsDescription"),
                                    painterResource(Res.drawable.extension_30), launcherThemeColor("tertiary", Color(0xFFC0B2DB)), onExtensions,
                                    Modifier.weight(1f).fillMaxWidth(), compact, "extensions")
                            }
                        }
                    }
                    if (extraMenus.isNotEmpty()) {
                        Text(readI18n("resources.more"), color = ResourceMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                        extraMenus.forEach { menu ->
                            Surface(onClick = menu.onClick, color = launcherThemeColor("surface", Color(0xFF1B2520)), shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(menu.title, color = ResourceText, modifier = Modifier.weight(1f))
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ResourceMuted)
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
private fun ResourceThemeCards(entries: List<ResourceCardEntry>, layout: MenuLayoutSpec, compact: Boolean) {
    val overrides = ArtThemeController.activeTheme?.spec?.menu?.buttons.orEmpty()
    val ordered = entries.filter { overrides[it.id]?.hidden != true }
        .sortedBy { overrides[it.id]?.order ?: 0 }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth(layout.widthPercent / 100f)) {
            val columns = if (layout.orientation == "vertical") 1 else
                layout.columns.coerceAtMost((maxWidth / 200.dp).toInt().coerceAtLeast(1))
            val rows = mutableListOf<List<Pair<ResourceCardEntry, Int>>>()
            var row = mutableListOf<Pair<ResourceCardEntry, Int>>()
            var used = 0
            ordered.forEach { entry ->
                val span = (overrides[entry.id]?.sanitized()?.span?.takeIf { it > 0 } ?: 1).coerceAtMost(columns)
                if (used + span > columns) {
                    rows += row
                    row = mutableListOf()
                    used = 0
                }
                row += entry to span
                used += span
            }
            if (row.isNotEmpty()) rows += row
            Column(verticalArrangement = Arrangement.spacedBy(layout.spacing.dp)) {
                rows.forEach { items ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.spacing.dp)) {
                        items.forEach { (entry, span) ->
                            SecondaryResourceCard(entry.title, entry.description, entry.icon, entry.accent, entry.onClick,
                                Modifier.weight(span.toFloat()), compact, entry.id)
                        }
                        val empty = columns - items.sumOf { it.second }
                        if (empty > 0) Spacer(Modifier.weight(empty.toFloat()))
                    }
                }
            }
        }
    }
}

private fun resourceButton(id: String): MenuButtonSpec =
    ArtThemeController.activeTheme?.spec?.menu?.buttons?.get(id)?.sanitized() ?: MenuButtonSpec()

private fun resourceCardModifier(modifier: Modifier, id: String): Modifier {
    val widthPercent = resourceButton(id).widthPercent
    val sized = if (widthPercent == 100) modifier else modifier
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(min = 48.dp)
        .fillMaxWidth(widthPercent / 100f)
    return sized.testTag("resource-card-$id")
}

private fun resourceCardCorner(default: Dp): Dp {
    val spec = ArtThemeController.activeTheme?.spec ?: return default
    val layout = spec.menu.layout
    return if ("buttonCorner" in spec.explicitMenuLayoutFields || layout.buttonCorner != MenuLayoutSpec().buttonCorner) {
        layout.sanitized().buttonCorner.dp
    } else default
}

private fun resourceCardBorder(id: String, color: Color): BorderStroke? {
    val layout = ArtThemeController.activeTheme?.spec?.menu?.layout ?: MenuLayoutSpec()
    return if (resourceButton(id).showBorder ?: layout.showBorder) BorderStroke(1.dp, color) else null
}

/** 始终保留原卡片底色；图片缺失或解码失败时直接透出底色和功能内容。 */
@Composable
private fun BoxScope.ResourceCardImage(id: String) {
    val file = launcherThemeButtonImage(id) ?: return
    var loaded by remember(file) { mutableStateOf(false) }
    AsyncImage(file, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
        onSuccess = { loaded = true }, onError = { loaded = false })
    if (loaded) Box(Modifier.matchParentSize().background(launcherThemeColor("surface", Color.Black).copy(alpha = 0.18f)))
}

@Composable
private fun ForumResourceCard(onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Surface(
        onClick = onClick, modifier = resourceCardModifier(modifier, "resourceBrowser"), shape = RoundedCornerShape(resourceCardCorner(20.dp)),
        color = launcherThemeColor("surface", Color(0xFF1B3025)),
        border = resourceCardBorder("resourceBrowser", launcherThemeColor("surfaceContainer", ResourceAccent.copy(alpha = 0.24f))),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(
            launcherThemeColor("primaryContainer", Color(0xFF243E2F)), launcherThemeColor("surface", Color(0xFF17271F)),
        )))) {
            ResourceCardImage("resourceBrowser")
            Icon(painterResource(Res.drawable.launcher_resources), null, tint = ResourceAccent.copy(alpha = 0.09f),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(if (compact) 92.dp else 150.dp))
            Column(
                Modifier.heightIn(min = if (compact) 164.dp else 272.dp)
                    .padding(if (compact) 16.dp else 28.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(Res.drawable.public_30), null, Modifier.size(if (compact) 16.dp else 18.dp), tint = ResourceAccent)
                    Text(readI18n("resources.community"), color = ResourceAccent, fontSize = if (compact) 11.sp else 12.sp, letterSpacing = 1.sp)
                }
                Text(readI18n("browser.resourceBrowser"), color = ResourceText, fontSize = if (compact) 22.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(readI18n("resources.forumDescription"), color = ResourceMuted, fontSize = if (compact) 11.sp else 14.sp,
                    modifier = Modifier.widthIn(max = 360.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.background(ResourceAccent, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = if (compact) 7.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(readI18n("resources.openForum"), color = launcherThemeColor("onPrimary", Color(0xFF14261C)), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = launcherThemeColor("onPrimary", Color(0xFF14261C)))
                }
            }
        }
    }
}

@Composable
private fun SecondaryResourceCard(
    title: String,
    description: String,
    icon: Painter,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
    id: String,
) {
    Surface(onClick = onClick, modifier = resourceCardModifier(modifier, id),
        color = launcherThemeColor("surface", Color(0xF51B2520)), shape = RoundedCornerShape(resourceCardCorner(18.dp)),
        border = resourceCardBorder(id, launcherThemeColor("surfaceContainer", Color.White.copy(alpha = 0.07f)))) {
        Box {
            ResourceCardImage(id)
            Row(
                Modifier.heightIn(min = (if (compact) 76.dp else 104.dp).coerceAtLeast(
                    (ArtThemeController.activeTheme?.spec?.menu?.layout?.sanitized()?.buttonHeight ?: 44).dp))
                    .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            ) {
                Box(Modifier.size(if (compact) 32.dp else 44.dp).background(accent.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = ResourceText, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.Medium)
                    Text(description, color = ResourceMuted, fontSize = if (compact) 11.sp else 12.sp, maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = ResourceMuted)
            }
        }
    }
}
