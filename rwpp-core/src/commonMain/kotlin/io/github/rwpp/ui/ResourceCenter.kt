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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.rwpp_core.generated.resources.*
import org.jetbrains.compose.resources.painterResource

private val ResourceText = Color(0xFFF0F4F2)
private val ResourceMuted = Color(0xFFA5B2AB)
private val ResourceAccent: Color
    @Composable get() = ArtThemeController.activeTheme?.takeIf { "primary" in it.spec.colors }?.colorScheme?.primary
        ?: Color(0xFF90D4AE)

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
        Box(Modifier.matchParentSize().background(Color(0xED0D1411)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color(0x292C5540), Color.Transparent, Color(0x33000000)),
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
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
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

                    if (narrow) {
                        ForumResourceCard(onForum, Modifier.fillMaxWidth(), compact = false)
                        Spacer(Modifier.height(16.dp))
                        SecondaryResourceCard(readI18n("browser.esportsSite"), readI18n("resources.esportsDescription"),
                            painterResource(Res.drawable.swords_30), Color(0xFFA6BEDC), onEsports, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        SecondaryResourceCard(readI18n("menu.extension"), readI18n("resources.extensionsDescription"),
                            painterResource(Res.drawable.extension_30), Color(0xFFC0B2DB), onExtensions, Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ForumResourceCard(onForum, Modifier.weight(1.15f).fillMaxHeight(), compact)
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SecondaryResourceCard(readI18n("browser.esportsSite"), readI18n("resources.esportsDescription"),
                                    painterResource(Res.drawable.swords_30), Color(0xFFA6BEDC), onEsports,
                                    Modifier.weight(1f).fillMaxWidth(), compact)
                                SecondaryResourceCard(readI18n("menu.extension"), readI18n("resources.extensionsDescription"),
                                    painterResource(Res.drawable.extension_30), Color(0xFFC0B2DB), onExtensions,
                                    Modifier.weight(1f).fillMaxWidth(), compact)
                            }
                        }
                    }
                    if (extraMenus.isNotEmpty()) {
                        Text(readI18n("resources.more"), color = ResourceMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                        extraMenus.forEach { menu ->
                            Surface(onClick = menu.onClick, color = Color(0xFF1B2520), shape = RoundedCornerShape(12.dp),
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
private fun ForumResourceCard(onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1B3025), border = BorderStroke(1.dp, ResourceAccent.copy(alpha = 0.24f)),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF243E2F), Color(0xFF17271F))))) {
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
                    Text(readI18n("resources.openForum"), color = Color(0xFF14261C), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = Color(0xFF14261C))
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
) {
    Surface(onClick = onClick, modifier = modifier, color = Color(0xF51B2520), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))) {
        Row(
            Modifier.heightIn(min = if (compact) 76.dp else 104.dp)
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
