/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.AppContext
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.coreVersion
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.projectVersion
import io.github.rwpp.theme.ArtThemeController
import coil3.compose.AsyncImage
import io.github.rwpp.rwpp_core.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

private val MenuWhite = Color(0xFFF2F5F3)
private val MenuMuted = Color(0xFFACB5B1)
private val MenuGreen: Color
    @Composable get() = ArtThemeController.activeTheme?.takeIf { "primary" in it.spec.colors }?.colorScheme?.primary
        ?: Color(0xFF5FCE89)

/** 默认主菜单只叠加轻量遮罩；背景继续由平台既有的游戏/自定义背景负责。 */
@Composable
internal fun LauncherMainMenu(
    multiplayer: () -> Unit,
    singlePlayer: () -> Unit,
    settings: () -> Unit,
    mods: () -> Unit,
    resources: () -> Unit,
    openSourceInfo: () -> Unit,
    account: () -> Unit,
    friends: () -> Unit,
) {
    val appContext = koinInject<AppContext>()
    val artTheme = ArtThemeController.activeTheme
    val primaryAccent = if (artTheme != null && "primary" in artTheme.spec.colors) {
        lerp(Color(0xFF14231B), MenuGreen, 0.52f)
    } else Color(0xFF287D44)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("launcher-main-menu")) {
        val short = maxHeight < 500.dp
        val narrow = maxWidth < 600.dp
        val compactLandscape = !narrow && maxHeight < 420.dp
        val minimalHeight = compactLandscape && maxHeight < 340.dp
        val edge = if (short || narrow) 16.dp else 28.dp
        val gap = if (compactLandscape) 10.dp else if (short || narrow) 12.dp else 18.dp
        val headerHeight = if (compactLandscape) 56.dp else if (maxHeight < 620.dp) 68.dp else 88.dp
        val footerHeight = if (compactLandscape) 48.dp else 56.dp
        val contentWidth = (maxWidth * when {
            narrow -> 0.92f
            compactLandscape -> 0.86f
            else -> 0.78f
        }).coerceAtMost(880.dp)

        // 深浅分层让实时战场可见，同时保证文字在明亮地图上仍清晰。
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.36f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color(0x88090D0C), 0.42f to Color.Transparent, 1f to Color(0xC2090D0C),
        )))

        Box(
            Modifier.fillMaxSize().padding(top = headerHeight, bottom = footerHeight),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(contentWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(vertical = if (short) 4.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val logoWidth = (contentWidth * if (narrow) 0.85f else 0.60f).coerceAtMost(
                    if (minimalHeight) 160.dp else if (compactLandscape) 240.dp else 490.dp,
                )
                Box(Modifier.width(logoWidth).height(logoWidth / 5f)) {
                    if (artTheme?.titleFile != null) {
                        AsyncImage(
                            model = artTheme.titleFile, contentDescription = "RWJS",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        )
                    } else {
                        Image(
                            painterResource(Res.drawable.title), "RWJS",
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(MenuWhite),
                        )
                        // 沿用内置标志的几何轮廓，仅保留 J 的品牌绿。
                        Image(
                            painterResource(Res.drawable.title), null,
                            Modifier.fillMaxSize().drawWithContent {
                                clipRect(left = size.width * 0.53f, right = size.width * 0.775f) { this@drawWithContent.drawContent() }
                            }, contentScale = ContentScale.Fit,
                        )
                    }
                }
                if (minimalHeight) {
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        readI18n("menu.home.tagline"), color = MenuMuted,
                        fontSize = if (short) 11.sp else 14.sp, letterSpacing = if (compactLandscape) 2.sp else 4.sp,
                        modifier = Modifier.padding(top = if (compactLandscape) 6.dp else 10.dp,
                            bottom = if (compactLandscape) 12.dp else if (short) 18.dp else 26.dp),
                    )
                }

                val primaryHeight = if (minimalHeight) 74.dp else if (compactLandscape) 82.dp else if (short) 94.dp else 122.dp
                val primaryCards: @Composable RowScope.() -> Unit = {
                    LauncherMenuCard(
                        readI18n("menu.singlePlayerGame"), readI18n("menu.home.singleDescription"),
                        singlePlayer, Modifier.weight(1f), primaryHeight, primaryAccent,
                        prominent = true, compact = compactLandscape,
                    ) { Icon(Icons.Default.Person, null, Modifier.size(if (compactLandscape) 32.dp else 44.dp), tint = MenuWhite) }
                    LauncherMenuCard(
                        readI18n("menu.multiplayer"), readI18n("menu.home.multiDescription"),
                        multiplayer, Modifier.weight(1f), primaryHeight, Color(0xFF365D78),
                        prominent = true, compact = compactLandscape,
                    ) { Icon(painterResource(Res.drawable.group_30), null, Modifier.size(if (compactLandscape) 36.dp else 48.dp), tint = MenuWhite) }
                }
                if (narrow) {
                    // 竖屏保留完整标题和 48dp 以上命中区。
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        LauncherMenuCard(
                            readI18n("menu.singlePlayerGame"), readI18n("menu.home.singleDescription"),
                            singlePlayer, Modifier.fillMaxWidth(), 94.dp, primaryAccent, true,
                        ) { Icon(Icons.Default.Person, null, Modifier.size(40.dp), tint = MenuWhite) }
                        LauncherMenuCard(
                            readI18n("menu.multiplayer"), readI18n("menu.home.multiDescription"),
                            multiplayer, Modifier.fillMaxWidth(), 94.dp, Color(0xFF365D78), true,
                        ) { Icon(painterResource(Res.drawable.group_30), null, Modifier.size(44.dp), tint = MenuWhite) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = primaryCards)
                }
                Spacer(Modifier.height(gap))

                val tools = listOf(
                    Triple(readI18n("menu.home.resources"), readI18n("menu.home.resourcesDescription"), resources),
                    Triple(readI18n("menu.modsAndMaps"), readI18n("menu.home.modsDescription"), mods),
                    Triple(readI18n("menu.settings"), readI18n("menu.home.settingsDescription"), settings),
                    Triple(readI18n("menu.openSourceInfo"), readI18n("menu.home.infoDescription"), openSourceInfo),
                )
                tools.indices.chunked(if (!compactLandscape && (narrow || contentWidth < 720.dp)) 2 else 4).forEachIndexed { row, indices ->
                    if (row > 0) Spacer(Modifier.height(gap))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        indices.forEach { index ->
                            val (title, description, onClick) = tools[index]
                            Box(Modifier.weight(1f)) {
                                LauncherMenuCard(title, description, onClick, Modifier.fillMaxWidth(),
                                    if (compactLandscape) 60.dp else if (short) 72.dp else 84.dp, compact = compactLandscape) {
                                    val iconSize = if (compactLandscape) 22.dp else 30.dp
                                    when (index) {
                                        0 -> Icon(painterResource(Res.drawable.launcher_resources), null, Modifier.size(iconSize), tint = MenuMuted)
                                        1 -> Icon(painterResource(Res.drawable.launcher_maps), null, Modifier.size(iconSize), tint = MenuMuted)
                                        2 -> Icon(Icons.Default.Settings, null, Modifier.size(iconSize), tint = MenuMuted)
                                        else -> Icon(painterResource(Res.drawable.launcher_feedback), null, Modifier.size(iconSize), tint = MenuMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 头尾在滚动区之外，避免小屏或较大字体时入口互相覆盖。
        Row(
            Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = edge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LauncherAccountEntry(account, compact = short || narrow, modifier = Modifier.weight(1f, fill = false))
                LauncherFriendsEntry(friends)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!narrow) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("$coreVersion (app $projectVersion)", color = MenuMuted, fontSize = 12.sp)
                        if (artTheme?.spec?.menu?.showTitleBadge != false) LauncherEditionBadge()
                    }
                } else {
                    if (artTheme?.spec?.menu?.showTitleBadge != false) LauncherEditionBadge()
                    Text("$coreVersion · $projectVersion", color = MenuMuted, fontSize = 9.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
                if (LocalUpdateCheckInProgress.current) UpdateCheckHint(Modifier.padding(top = 4.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(footerHeight).padding(horizontal = edge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(onClick = { appContext.exit() }, color = Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(Res.drawable.launcher_power), null, Modifier.size(24.dp), tint = MenuMuted)
                    Text(readI18n("menu.home.exit"), color = MenuMuted, fontSize = 13.sp)
                }
            }
            if (!narrow) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(readI18n("menu.home.footer"), color = MenuMuted.copy(alpha = 0.65f), fontSize = 12.sp, letterSpacing = 1.sp)
                    Box(Modifier.width(28.dp).height(1.dp).background(MenuMuted.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

@Composable
private fun LauncherEditionBadge() {
    Text(
        readI18n("menu.home.edition"), color = MenuGreen, fontSize = 11.sp,
        modifier = Modifier.background(MenuGreen.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun LauncherAccountEntry(onClick: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(40.dp), modifier = modifier.widthIn(max = 230.dp)) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val name = if (AccountSession.loggedIn) AccountSession.displayName else readI18n("menu.home.commander")
            if (AccountSession.loggedIn) {
                AccountAvatarBox(
                    name, size = if (compact) 48.dp else 60.dp,
                    avatar = AccountSession.user?.let { AccountAvatar(it.id, it.hasAvatar, AccountSession.avatarVersion) },
                    online = true,
                )
            } else {
                Box(
                    Modifier.size(if (compact) 48.dp else 60.dp).border(2.dp, MenuGreen, CircleShape)
                        .padding(5.dp).background(MenuGreen.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Person, null, Modifier.size(32.dp), tint = MenuWhite) }
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(readI18n("menu.home.greeting"), color = MenuMuted, fontSize = 12.sp)
                Text(name, color = MenuWhite, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LauncherFriendsEntry(onClick: () -> Unit) {
    val unread = FriendsSession.badgeCount()
    BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
        IconButton(onClick = onClick) {
            Icon(painterResource(Res.drawable.group_30), readI18n("menu.friends"), Modifier.size(24.dp), tint = MenuMuted)
        }
    }
}

@Composable
private fun LauncherMenuCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    accent: Color = Color(0xFF343F3C),
    prominent: Boolean = false,
    compact: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(if (prominent) 15.dp else 12.dp)
    Surface(
        onClick = onClick, modifier = modifier, shape = shape,
        color = Color.Transparent, contentColor = MenuWhite,
        border = BorderStroke(if (prominent) 1.5.dp else 1.dp,
            if (prominent) accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.14f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = height)
                .background(Brush.linearGradient(listOf(
                    if (prominent) accent.copy(alpha = 0.70f) else Color(0xB21A201F),
                    Color(0xB30D1515),
                )))
                .padding(horizontal = if (compact) 12.dp else if (prominent) 24.dp else 18.dp,
                    vertical = if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else if (prominent) 22.dp else 14.dp),
        ) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = if (prominent && compact) 20.sp else if (prominent) 24.sp else if (compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MenuWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (prominent || !compact) {
                    Text(description, fontSize = if (prominent && compact) 11.sp else if (prominent) 13.sp else 10.sp, color = MenuMuted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (prominent) {
                Box(Modifier.size(if (compact) 28.dp else 38.dp).border(1.dp, Color.White.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = MenuWhite, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}
