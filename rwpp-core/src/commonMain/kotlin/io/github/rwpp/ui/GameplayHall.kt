/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.pollWhileVisible
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameClient
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.account.AccountSession
import io.github.rwpp.net.games.isOnlineGamePortalUrl
import io.github.rwpp.rwpp_core.generated.resources.*
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.uiAnimationsEnabled
import org.jetbrains.compose.resources.painterResource
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

private val HallWhite = Color(0xFFF0F5EE)
private val HallMuted = Color(0xFFABBCA9)
private val HallGreen = Color(0xFF87E69A)
private val HallBorder = Color(0xFF375440)
private val HallShape = RoundedCornerShape(18.dp)

private enum class HallCategory(val key: String) { Recommended("recommended"), All("all"), Extended("extended"), Original("original"), Favorites("favorites") }
private data class HallEntry(val id: String, val tags: List<String>, val scene: Int,
    val destination: LauncherPage? = null, val game: OnlineGame? = null) {
    val available: Boolean get() = destination != null || game?.available == true
    val canOpen: Boolean get() = destination != null || game?.let { isOnlineGamePortalUrl(it.portalUrl) && it.portalRevision > 0 } == true
}
private val hallEntries = listOf(
    HallEntry("multiplayer", listOf("pvp", "strategy"), 0, LauncherPage.Multiplayer),
    HallEntry("missions", listOf("pve", "strategy"), 2, LauncherPage.Mission),
    HallEntry("survival", listOf("pve", "strategy"), 1, LauncherPage.Survival),
)

/** 与普通页面共用输入/返回闸门；关闭动画时整段转场立即完成。 */
@Composable
internal fun GameplayHallPageHost(content: @Composable () -> Unit) {
    val animated = uiAnimationsEnabled()
    val stateHolder = rememberSaveableStateHolder()
    // AnimatedVisibility 会保留已经启动的动画；切换开关时重建转场，立即停止旧动画。
    // 页面状态由外层 holder 保存，避免开关动画时丢失搜索与收藏。
    key(animated) {
        LauncherPageHost(
            LauncherPage.GameplayHall,
            enter = if (animated) fadeIn(tween(280)) +
                slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 10 } +
                scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.97f) else EnterTransition.None,
            exit = if (animated) fadeOut(tween(180)) +
                slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 14 } else ExitTransition.None,
        ) { stateHolder.SaveableStateProvider("gameplay-hall", content) }
    }
}

@Composable
internal fun GameplayHallView(
    onExit: () -> Unit,
    onOpenPage: (LauncherPage) -> Unit,
    loadGames: suspend () -> List<OnlineGame> = {
        withContext(Dispatchers.IO) { OnlineGameClient(resolveOpenPlatformUrl()).games() }
    },
    refreshGame: suspend (String) -> OnlineGame? = {
        withContext(Dispatchers.IO) { OnlineGameClient(resolveOpenPlatformUrl()).game(it) }
    },
    loggedIn: Boolean = AccountSession.loggedIn,
    onLogin: () -> Unit = { UI.showAccountView = true },
) {
    var category by rememberSaveable { mutableStateOf(HallCategory.Recommended) }
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(listOf<String>()) }
    var featuredIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingPortal by remember { mutableStateOf<String?>(null) }
    var games by remember { mutableStateOf(emptyList<OnlineGame>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val latestLoad by rememberUpdatedState(loadGames)
    LaunchedEffect(retry) {
        LauncherVisibility.visible.pollWhileVisible(30_000) {
            try {
                games = latestLoad()
                loadFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                games = emptyList()
                loadFailed = true
            } finally { loading = false }
        }
    }
    val allEntries = games.mapIndexed { index, game ->
        HallEntry("game-${game.appId}", emptyList(), index % 3, game = game)
    } + hallEntries
    val expansionUnavailableText = hallText("expansionUnavailable")
    LaunchedEffect(UI.pendingExpansionGameAppId) {
        val appId = UI.pendingExpansionGameAppId ?: return@LaunchedEffect
        try {
            val approved = refreshGame(appId)
            if (UI.pendingExpansionGameAppId != appId) return@LaunchedEffect
            if (approved?.expansionBinding == null || !isOnlineGamePortalUrl(approved.portalUrl)) UI.showWarning(expansionUnavailableText)
            else if (loggedIn) onOpenPage(LauncherPage.GamePortal(approved.appId))
            else { pendingPortal = approved.appId; onLogin() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UI.showWarning(expansionUnavailableText)
        } finally {
            if (UI.pendingExpansionGameAppId == appId) UI.pendingExpansionGameAppId = null
        }
    }
    val featuredEntries = allEntries.take(3)
    val selectedFeatured = featuredIndex.coerceIn(featuredEntries.indices)
    BackHandler(true, onExit)
    val portalUnavailable = readI18n("gamePortal.unavailable")
    LaunchedEffect(loggedIn, UI.showAccountView) {
        val pending = pendingPortal ?: return@LaunchedEffect
        if (loggedIn) {
            pendingPortal = null
            UI.showAccountView = false
            onOpenPage(LauncherPage.GamePortal(pending))
        } else if (!UI.showAccountView) pendingPortal = null
    }
    val open: (HallEntry) -> Unit = { entry ->
        entry.destination?.let(onOpenPage) ?: entry.game?.let { game ->
            if (!isOnlineGamePortalUrl(game.portalUrl) || game.portalRevision <= 0) UI.showWarning(portalUnavailable)
            else if (loggedIn) onOpenPage(LauncherPage.GamePortal(game.appId))
            else { pendingPortal = game.appId; onLogin() }
        }
    }
    val favorite: (HallEntry) -> Unit = { entry ->
        favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
    }
    val entries = allEntries.filter { entry ->
        val matchesCategory = when (category) {
            HallCategory.Extended -> entry.destination == null
            HallCategory.Original -> entry.destination != null
            HallCategory.Favorites -> entry.id in favorites
            else -> true
        }
        matchesCategory && (tag.isEmpty() || tag in entry.tags) && (!availableOnly || entry.available) &&
            (query.isBlank() || (entry.title() + entry.description() +
                entry.tags.map { hallText("tags.$it") }.joinToString()).contains(query.trim(), ignoreCase = true))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("gameplay-hall")) {
        val narrow = maxWidth < 700.dp
        val compact = maxHeight < 500.dp
        val edge = if (narrow || compact) 12.dp else 24.dp
        // 保留平台战场背景，使用静态遮罩保证阅读；没有后台轮播或额外引擎采样。
        Box(Modifier.matchParentSize().background(Color(0xEE0A140E)))
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0x502D6338), Color.Transparent, Color(0x50183128)))))
        Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
            Row(Modifier.fillMaxWidth().height(if (compact) 64.dp else 82.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HallIconButton(onExit, "back") { Icon(Icons.AutoMirrored.Filled.ArrowBack, hallText("back"), tint = HallWhite) }
                Column(Modifier.weight(1f)) {
                    Text(hallText("title"), color = HallWhite, fontSize = if (compact || narrow) 23.sp else 28.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text(hallText("subtitle"), color = HallMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!narrow) HallSearch(query, { query = it }, Modifier.widthIn(max = 360.dp).weight(1f))
                HallIconButton({ category = if (category == HallCategory.Favorites) HallCategory.Recommended else HallCategory.Favorites }, "favorites") {
                    HallStar(category == HallCategory.Favorites, hallText("categories.favorites"), HallGreen)
                }
            }
            if (narrow) {
                HallSearch(query, { query = it }, Modifier.fillMaxWidth())
                Row(Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HallCategory.entries.forEach { item -> HallCategoryButton(item, category == item, { category = item }, compact = true) }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                if (!narrow) {
                    Column(Modifier.width(if (compact) 164.dp else 192.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HallCategory.entries.forEach { item -> HallCategoryButton(item, category == item, { category = item }) }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = HallBorder.copy(alpha = 0.6f))
                        Surface(onClick = { onOpenPage(LauncherPage.Multiplayer) }, color = Color.Transparent, shape = HallShape) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.Add, null, tint = HallMuted)
                                Text(hallText("host"), color = HallMuted, fontSize = 14.sp)
                            }
                        }
                        Text(hallText("discoveryNote"), color = HallMuted.copy(alpha = 0.65f), fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp))
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val columns = when { maxWidth >= 840.dp -> 3; maxWidth >= 500.dp -> 2; else -> 1 }
                    LazyColumn(Modifier.fillMaxSize().testTag("hall-content"), verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (category == HallCategory.Recommended && query.isBlank() && tag.isEmpty() && !availableOnly) {
                            item("featured") {
                                HallFeatured(featuredEntries, selectedFeatured, compact, narrow,
                                    { featuredIndex = it }, { open(featuredEntries[selectedFeatured]) })
                            }
                        }
                        item("filters") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                HallFilter(hallText("tag"), if (tag.isEmpty()) hallText("any") else hallText("tags.$tag"), "tag",
                                    listOf("") + listOf("pvp", "pve", "strategy", "explore"),
                                    { if (it.isEmpty()) hallText("any") else hallText("tags.$it") }, { tag = it })
                                HallFilter(hallText("status"), hallText(if (availableOnly) "available" else "any"), "status",
                                    listOf(false, true), { hallText(if (it) "available" else "any") }, { availableOnly = it })
                                if (query.isNotEmpty() || tag.isNotEmpty() || availableOnly) {
                                    TextButton(onClick = { query = ""; tag = ""; availableOnly = false }, modifier = Modifier.testTag("hall-reset")) {
                                        Text(hallText("reset"), color = HallGreen)
                                    }
                                }
                            }
                        }
                        item("heading") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.width(3.dp).height(20.dp).background(HallGreen, CircleShape))
                                Text(hallText(if (category == HallCategory.Recommended) "discover" else "categories.${category.key}"),
                                    color = HallWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${entries.size} " + hallText("entries"), color = HallMuted, fontSize = 12.sp)
                            }
                        }
                        item("directory-status") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(hallText(when { loading -> "directoryLoading"; loadFailed -> "directoryFailed"
                                    games.isEmpty() -> "directoryEmpty"; else -> "directoryReady" }),
                                    color = HallMuted, fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("hall-directory-status"))
                                TextButton(onClick = { loading = true; retry++ }, modifier = Modifier.testTag("hall-refresh")) {
                                    Text(hallText("refresh"), color = HallGreen)
                                }
                            }
                        }
                        if (entries.isEmpty()) item("empty") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.Search, null, Modifier.size(36.dp), tint = HallMuted)
                                Text(hallText(if (category == HallCategory.Favorites) "emptyFavorites" else "empty"), color = HallMuted)
                            }
                        }
                        items(entries.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { entry -> HallGameCard(entry, entry.id in favorites, { favorite(entry) }, { open(entry) },
                                    Modifier.weight(1f).fillMaxHeight(), compact) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HallEntry.title(): String = game?.name ?: hallText("games.$id.title")
@Composable private fun HallEntry.description(): String = game?.description ?: hallText("games.$id.description")

@Composable
private fun hallText(key: String): String = readI18n("gameplayHall.$key")

@Composable
private fun HallIconButton(onClick: () -> Unit, id: String, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).border(1.dp, HallBorder, CircleShape)
        .background(Color(0x441B3523), CircleShape).testTag("hall-$id"), content = content)
}

@Composable
private fun HallSearch(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onChange, modifier = modifier.testTag("hall-search"), singleLine = true, shape = CircleShape,
        placeholder = { Text(hallText("search"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, hallText("clear")) } }) else null,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = HallWhite, unfocusedTextColor = HallWhite,
            focusedBorderColor = HallGreen, unfocusedBorderColor = HallBorder, cursorColor = HallGreen,
            focusedLeadingIconColor = HallGreen, unfocusedLeadingIconColor = HallMuted, unfocusedPlaceholderColor = HallMuted))
}

@Composable
private fun HallCategoryButton(category: HallCategory, selected: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (selected) Color(0xFF26683A) else Color(0x6616261B),
        border = BorderStroke(1.dp, if (selected) Color(0xFF4B9A5C) else HallBorder),
        modifier = (if (compact) Modifier else Modifier.fillMaxWidth()).testTag("hall-category-${category.key}")) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = Modifier.size(22.dp)
            when (category) {
                HallCategory.Recommended -> Icon(Icons.Default.Star, null, icon, tint = HallWhite)
                HallCategory.All -> Icon(painterResource(Res.drawable.dns_30), null, icon, tint = HallMuted)
                HallCategory.Extended -> Icon(painterResource(Res.drawable.extension_30), null, icon, tint = HallMuted)
                HallCategory.Original -> Icon(painterResource(Res.drawable.swords_30), null, icon, tint = HallMuted)
                HallCategory.Favorites -> HallStar(false, null, HallMuted, icon)
            }
            Text(hallText("categories.${category.key}"), color = if (selected) HallWhite else HallMuted,
                fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun <T> HallFilter(label: String, value: String, id: String, options: List<T>, title: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, color = Color(0x991A3020), shape = CircleShape,
            border = BorderStroke(1.dp, HallBorder), modifier = Modifier.testTag("hall-filter-$id")) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$label: ", color = HallMuted, fontSize = 13.sp)
                Text(value, color = HallWhite, fontSize = 13.sp)
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = HallMuted)
            }
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = Color(0xFF1B3021)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(title(option), color = HallWhite) },
                onClick = { expanded = false; onSelect(option) }) }
        }
    }
}

@Composable
private fun HallFeatured(entries: List<HallEntry>, index: Int, compact: Boolean, narrow: Boolean, onSelect: (Int) -> Unit, onOpen: () -> Unit) {
    val entry = entries[index]
    Box(Modifier.fillMaxWidth().heightIn(min = if (narrow) 284.dp else if (compact) 206.dp else 244.dp)
        .clip(HallShape).border(1.dp, Color(0xFF527D4F), HallShape).testTag("hall-featured")) {
        HallCover(entry, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFA102317), Color(0xD9102317), Color(0x22102317)))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB309160D)))))
        Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 26.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
            HallBadge(hallText("featured"), HallGreen)
            Text(entry.title(), color = HallWhite, fontWeight = FontWeight.Bold,
                fontSize = if (compact) 30.sp else 36.sp, lineHeight = if (compact) 36.sp else 44.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.75f))
            Text(entry.description(), color = HallMuted, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.72f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HallBadge(hallText(if (entry.game != null) "categories.extended" else "categories.original"), HallGreen)
                entry.tags.forEach { HallBadge(hallText("tags.$it"), Color(0xFFBDD5DC)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(entries.size) { dot ->
                        val description = entries[dot].title()
                        Surface(onClick = { onSelect(dot) }, color = Color.Transparent, shape = CircleShape,
                            modifier = Modifier.size(48.dp).testTag("hall-featured-$dot").semantics { contentDescription = description }) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(Modifier.width(if (index == dot) 22.dp else 6.dp).height(6.dp)
                                    .background(if (index == dot) HallGreen else HallMuted.copy(alpha = 0.5f), CircleShape))
                            }
                        }
                    }
                }
                Surface(onClick = onOpen, enabled = entry.canOpen, color = if (entry.canOpen) Color(0xFF3BBA54) else HallBorder,
                    shape = CircleShape, modifier = Modifier.testTag("hall-featured-open")) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (entry.canOpen) hallText("enter") else readI18n("gamePortal.maintenance"),
                            color = if (entry.canOpen) Color(0xFF061B0B) else HallMuted, fontWeight = FontWeight.Bold)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = Color(0xFF061B0B))
                    }
                }
            }
        }
    }
}

@Composable
private fun HallCover(entry: HallEntry, modifier: Modifier) {
    Box(modifier) {
        HallBattlefield(entry.scene, Modifier.matchParentSize())
        entry.game?.coverUrl?.takeIf(String::isNotBlank)?.let { url ->
            AsyncImage(url, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun HallBadge(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(color.copy(alpha = 0.12f), CircleShape).border(1.dp, color.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun HallGameCard(entry: HallEntry, favorite: Boolean, onFavorite: () -> Unit, onOpen: () -> Unit, modifier: Modifier, compact: Boolean) {
    Surface(onClick = onOpen, enabled = entry.canOpen, color = Color(0xE6192A1F), shape = HallShape, border = BorderStroke(1.dp, HallBorder),
        modifier = modifier.testTag("hall-card-${entry.id}")) {
        Column {
            Box(Modifier.fillMaxWidth().height(if (compact) 90.dp else 118.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))) {
                HallCover(entry, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x88304427)))))
                Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    HallBadge(if (!entry.canOpen) readI18n("gamePortal.maintenance") else hallText(if (entry.available) "available" else "maintenance"),
                        if (entry.available) HallGreen else Color(0xFFEDCF8D))
                }
                IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("hall-favorite-${entry.id}")) {
                    HallStar(favorite, hallText(if (favorite) "unfavorite" else "favorite"),
                        if (favorite) Color(0xFFFFD573) else HallWhite, Modifier.size(22.dp))
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entry.title(), color = HallWhite, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { entry.tags.forEach { HallBadge(hallText("tags.$it"), HallGreen) } }
                Text(entry.description(), color = HallMuted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 36.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.canOpen) hallText("enter") else readI18n("gamePortal.maintenance"),
                        color = if (entry.canOpen) HallGreen else HallMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = HallGreen)
                }
            }
        }
    }
}

@Composable
private fun HallStar(selected: Boolean, description: String?, color: Color, modifier: Modifier = Modifier) {
    if (selected) Icon(Icons.Default.Star, description, modifier, tint = color)
    else Icon(painterResource(Res.drawable.gameplay_favorite_outline), description, modifier, tint = color)
}

/** 静态战术地图插画：矢量地形与单位，不依赖联网封面或游戏 GL 上下文。 */
@Composable
private fun HallBattlefield(scene: Int, modifier: Modifier) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 800f, size.height / 280f, pivot = Offset.Zero) }) {
            drawRect(if (scene == 2) Color(0xFF303C35) else Color(0xFF304C2B), size = Size(800f, 280f))
            repeat(160) { tile ->
                val x = (tile % 32) * 26f
                val y = (tile / 32) * 60f + sin(tile.toFloat()) * 12f
                drawCircle(Color(if (tile % 3 == 0) 0xFF416039 else 0xFF223E29), 14f + tile % 8, Offset(x, y))
            }
            val river = Path().apply {
                moveTo(340f, -20f); cubicTo(260f, 100f, 520f, 130f, 410f, 310f)
                lineTo(480f, 310f); cubicTo(580f, 130f, 340f, 100f, 415f, -20f); close()
            }
            drawPath(river, Color(0xFF477B79), style = Stroke(14f))
            drawPath(river, Color(0xFF254C61))
            drawLine(Color(0xFF9B9770), Offset(385f, 111f), Offset(470f, 111f), 30f)
            repeat(8) { drawLine(Color(0xFF545C48), Offset(390f + it * 10, 97f), Offset(390f + it * 10, 125f), 2f) }
            // 两侧基地与编队。
            listOf(Offset(190f, 78f), Offset(691f, 201f)).forEachIndexed { team, center ->
                val color = if (team == 0) Color(0xFF709BB9) else Color(0xFFB87862)
                drawRoundRect(Color(0xFF15201B), center - Offset(38f, 32f), Size(76f, 64f), CornerRadius(5f))
                drawRoundRect(color.copy(alpha = 0.8f), center - Offset(30f, 25f), Size(60f, 50f), CornerRadius(3f))
                drawRoundRect(Color(0xFF253B3E), center - Offset(18f, 16f), Size(36f, 32f), CornerRadius(3f))
                drawCircle(color, 9f, center)
                repeat(9) { unit ->
                    val point = Offset(if (team == 0) 260f + unit % 3 * 36 else 550f + unit % 3 * 36,
                        50f + unit / 3 * 68 + team * 14)
                    rotate(if (team == 0) 110f else -70f, point) {
                        drawRoundRect(Color(0xFF13231D), point - Offset(10f, 15f), Size(20f, 30f), CornerRadius(3f))
                        drawRect(color, point - Offset(6f, 11f), Size(12f, 22f))
                        drawCircle(Color(0xFFB8C6A5), 5f, point)
                        drawLine(color, point, point - Offset(0f, 21f), 3f)
                    }
                }
            }
            if (scene == 1) {
                repeat(5) { i ->
                    val point = Offset(500f + i * 38, 70f + (i % 2) * 65)
                    drawCircle(Color(0x664FCD91), 22f, point)
                    drawCircle(Color(0xFFE4C97A), 9f, point)
                    drawCircle(Color(0xFF7DB77F), 4f, point - Offset(0f, 8f))
                }
            } else if (scene == 2) {
                drawCircle(Color(0xFF859A80), 30f, Offset(505f, 196f), style = Stroke(3f))
                drawLine(Color(0xFFB4C4A2), Offset(490f, 196f), Offset(520f, 196f), 4f)
                drawLine(Color(0xFFB4C4A2), Offset(505f, 181f), Offset(505f, 211f), 4f)
            } else {
                repeat(3) { i ->
                    val point = Offset(490f + i * 18, 64f + i * 54)
                    drawCircle(Color(0x18FFCF68), 27f, point)
                    drawCircle(Color(0x44FFCF68), 15f, point)
                    drawCircle(Color(0xFFF6BA60), 5f, point)
                    drawLine(Color(0xFFB8B477), point - Offset(36f, 10f), point, 1.5f)
                }
            }
            repeat(12) { i ->
                val point = Offset(50f + i * 65, 230f + cos(i.toFloat()) * 25)
                drawCircle(Color(0xFF1B3325), 16f, point + Offset(3f, 4f))
                drawCircle(Color(0xFF537046), 13f, point)
                drawCircle(Color(0xFF668452), 7f, point - Offset(3f, 4f))
            }
        }
    }
}
