/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.rwpp.account.AccountSession
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.whileVisible
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.games.GamePortalException
import io.github.rwpp.net.games.GameTestAccess
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameClient
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.dns_30
import io.github.rwpp.rwpp_core.generated.resources.extension_30
import io.github.rwpp.rwpp_core.generated.resources.gameplay_favorite_outline
import io.github.rwpp.rwpp_core.generated.resources.public_30
import io.github.rwpp.widget.LocalDialogInteractive
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.animateUiFloatAsState
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 深空黑曜石 + 翡翠微光色彩体系：以中性暗面承载画面层次，仅在焦点与主操作点亮品牌绿。
private val HallCanvasScrim = Color(0xEB080C0F)
private val HallSurfaceLow = Color(0xCC0F161B)
private val HallSurfaceCard = Color(0xF0131C22)
private val HallSurfaceElevated = Color(0xFF1A252C)
private val HallGlassDark = Color(0xBD090F13)

private val HallInkPrimary = Color(0xFFF3F7F5)
private val HallInkSecondary = Color(0xFF9EB0A9)
private val HallInkTertiary = Color(0xFF6A7D77)

private val HallAccent = Color(0xFF3CE38A)
private val HallAccentBright = Color(0xFF7AF2B2)
private val HallAccentDeep = Color(0xFF051D10)
private val HallAmber = Color(0xFFF4C86A)
private val HallAmberSoft = Color(0xFFFFF3D4)
private val HallCyan = Color(0xFF7ADBF0)
private val HallCoral = Color(0xFFEE7B75)

private val HallHairline = Color(0x18FFFFFF)
private val HallHairlineStrong = Color(0x2EFFFFFF)

private val HallHeroShape = RoundedCornerShape(22.dp)
private val HallCardShape = RoundedCornerShape(18.dp)
private val HallControlShape = RoundedCornerShape(12.dp)

private enum class HallCategory(val key: String) {
    All("all"),
    Favorites("favorites"),
}

private data class HallEntry(
    val id: String,
    val scene: Int,
    val game: OnlineGame,
) {
    val canOpen = game.canEnter
}

private data class HallLoginRequest(val game: OnlineGame, val testing: Boolean)

private data class OnlineGameTelemetry(
    val onlineServers: Int,
    val players: Int,
    val hasExpansion: Boolean,
    val hasArtPack: Boolean,
    val artVersion: String,
)

private fun OnlineGame.telemetry(): OnlineGameTelemetry {
    val active = servers.filter { it.online && it.available }
    val onlineList = servers.filter { it.online }
    return OnlineGameTelemetry(
        onlineServers = active.size,
        players = onlineList.sumOf { it.currentPlayers },
        hasExpansion = expansionBinding != null,
        hasArtPack = artBinding != null && expansionBinding == null,
        artVersion = artBinding?.version.orEmpty(),
    )
}

internal class HallDirectoryCache(
    var platformUrl: String? = null,
    var games: List<OnlineGame> = emptyList(),
)

private val LocalHallDirectoryCache = compositionLocalOf<HallDirectoryCache?> { null }

/** 与普通页面共用输入/返回闸门；关闭动画时整段转场立即完成。 */
@Composable
internal fun GameplayHallPageHost(content: @Composable () -> Unit) {
    val animated = uiAnimationsEnabled()
    val stateHolder = rememberSaveableStateHolder()
    val directoryCache = remember { HallDirectoryCache() }
    val transitionMemory = remember { HallTransitionMemory() }
    val visible = launcherPage == LauncherPage.GameplayHall
    val enterFromRight =
        if (visible && !transitionMemory.lastVisible) {
            val from = launcherNavigationTransition.from
            from == LauncherPage.MainMenu || from == LauncherPage.GameplayHall
        } else {
            transitionMemory.enterFromRight
        }
    val exitToRight =
        if (!visible && transitionMemory.lastVisible) {
            launcherPage == LauncherPage.MainMenu
        } else {
            transitionMemory.exitToRight
        }
    SideEffect {
        transitionMemory.lastVisible = visible
        transitionMemory.enterFromRight = enterFromRight
        transitionMemory.exitToRight = exitToRight
    }
    // AnimatedVisibility 会保留已经启动的动画；切换开关时重建转场，立即停止旧动画。
    // 页面状态由外层 holder 保存，避免开关动画时丢失搜索与收藏。
    key(animated) {
        CompositionLocalProvider(
            LocalHallDirectoryCache provides directoryCache,
            LocalGameplayHallMotionConfig provides GameplayHallMotionConfig(
                animated = animated,
                enterFromRight = enterFromRight,
                exitToRight = exitToRight,
            ),
        ) {
            LauncherPageHost(
                LauncherPage.GameplayHall,
                enter = if (animated) fadeIn(tween(HALL_BACKDROP_ENTER_MS, easing = StandardDecelerateEasing)) else EnterTransition.None,
                exit = if (animated) fadeOut(tween(HALL_EXIT_MS, easing = EmphasizedAccelerateEasing)) else ExitTransition.None,
            ) { stateHolder.SaveableStateProvider("gameplay-hall", content) }
        }
    }
}

private class GameplayHallMotionStates(
    val headerOffset: State<Float>?,
    val bodyOffset: State<Float>?,
    val bodyScale: State<Float>?,
    val foregroundAlpha: State<Float>?,
    val feedLift: State<Float>?,
    val feedAlpha: State<Float>?,
)

@Composable
private fun rememberGameplayHallMotionStates(
    transition: Transition<EnterExitState>?,
    motionConfig: GameplayHallMotionConfig,
): GameplayHallMotionStates {
    if (transition == null || !motionConfig.animated) {
        return GameplayHallMotionStates(null, null, null, null, null, null)
    }
    val enterSign = if (motionConfig.enterFromRight) 1f else -1f
    val exitSign = if (motionConfig.exitToRight) 1f else -1f
    val headerOffset = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(300, easing = EmphasizedDecelerateEasing)
            } else {
                tween(190, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "HallHeaderOffset",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> enterSign
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> exitSign
        }
    }
    val bodyDuration = if (motionConfig.enterFromRight) 360 else 300
    val bodyDelay = if (motionConfig.enterFromRight) 15 else 0
    val bodyOffset = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(bodyDuration, delayMillis = bodyDelay, easing = EmphasizedDecelerateEasing)
            } else {
                tween(200, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "HallBodyOffset",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> enterSign
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> exitSign
        }
    }
    val bodyScale = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(bodyDuration, delayMillis = bodyDelay, easing = EmphasizedDecelerateEasing)
            } else {
                tween(200, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "HallBodyScale",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> if (motionConfig.enterFromRight) 0.984f else 0.974f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> if (motionConfig.exitToRight) 0.984f else 0.974f
        }
    }
    val foregroundAlpha = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(220, delayMillis = 35, easing = StandardDecelerateEasing)
            } else {
                tween(155, easing = FastOutLinearInEasing)
            }
        },
        label = "HallForegroundAlpha",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0f
        }
    }
    val feedLift = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(
                    if (motionConfig.enterFromRight) 340 else 280,
                    delayMillis = if (motionConfig.enterFromRight) 45 else 20,
                    easing = EmphasizedDecelerateEasing,
                )
            } else {
                tween(160, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "HallFeedLift",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> if (motionConfig.enterFromRight) 1f else 0.35f
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> 0f
        }
    }
    val feedAlpha = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(
                    220,
                    delayMillis = if (motionConfig.enterFromRight) 55 else 25,
                    easing = StandardDecelerateEasing,
                )
            } else {
                tween(145, easing = FastOutLinearInEasing)
            }
        },
        label = "HallFeedAlpha",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0f
        }
    }
    return GameplayHallMotionStates(
        headerOffset = headerOffset,
        bodyOffset = bodyOffset,
        bodyScale = bodyScale,
        foregroundAlpha = foregroundAlpha,
        feedLift = feedLift,
        feedAlpha = feedAlpha,
    )
}

private fun Modifier.hallHeaderMotion(motion: GameplayHallMotionStates): Modifier {
    val offset = motion.headerOffset ?: return this
    val alphaState = motion.foregroundAlpha ?: return this
    return graphicsLayer {
        val maxShift = minOf(size.width * 0.03f, 24.dp.toPx())
        translationX = offset.value * maxShift
        alpha = alphaState.value
    }
}

private fun Modifier.hallBodyMotion(motion: GameplayHallMotionStates): Modifier {
    val offset = motion.bodyOffset ?: return this
    val scaleState = motion.bodyScale ?: return this
    val alphaState = motion.foregroundAlpha ?: return this
    return graphicsLayer {
        val maxShift = minOf(size.width * 0.055f, 48.dp.toPx())
        translationX = offset.value * maxShift
        scaleX = scaleState.value
        scaleY = scaleState.value
        transformOrigin = TransformOrigin(0.5f, 0.35f)
        alpha = alphaState.value
    }
}

private fun Modifier.hallFeedMotion(motion: GameplayHallMotionStates): Modifier {
    val lift = motion.feedLift ?: return this
    val alphaState = motion.feedAlpha ?: return this
    return graphicsLayer {
        translationY = lift.value * 12.dp.toPx()
        alpha = alphaState.value
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    verifyTestAccess: suspend (String, String) -> GameTestAccess = ::verifyGameTestAccess,
) {
    val animated = uiAnimationsEnabled()
    val pageScope = LocalPageAnimatedVisibilityScope.current
    val motionConfig = LocalGameplayHallMotionConfig.current ?: GameplayHallMotionConfig(
        animated = animated,
        enterFromRight = true,
        exitToRight = true,
    )
    val motion = rememberGameplayHallMotionStates(pageScope?.transition, motionConfig)
    val directoryCache = LocalHallDirectoryCache.current
    val platformUrl = if (directoryCache != null) resolveOpenPlatformUrl() else ""
    val cachedInitial = remember(directoryCache, platformUrl) {
        directoryCache?.takeIf { it.platformUrl == platformUrl }?.games.orEmpty()
    }
    var category by rememberSaveable { mutableStateOf(HallCategory.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMobileDetails by rememberSaveable { mutableStateOf(false) }
    val contentListState = rememberLazyListState()
    var pendingLogin by remember(platformUrl) { mutableStateOf<HallLoginRequest?>(null) }
    var maintenanceGame by remember(platformUrl) { mutableStateOf<OnlineGame?>(null) }
    var passwordGame by remember(platformUrl) { mutableStateOf<OnlineGame?>(null) }
    var games by remember { mutableStateOf(cachedInitial) }
    var loading by remember { mutableStateOf(cachedInitial.isEmpty()) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val latestLoad by rememberUpdatedState(loadGames)
    LaunchedEffect(retry, platformUrl) {
        LauncherVisibility.visible.whileVisible {
            var retryDelay = 1_000L
            while (true) {
                try {
                    val loaded = latestLoad()
                    games = loaded
                    directoryCache?.let {
                        it.platformUrl = platformUrl
                        it.games = loaded
                    }
                    loadFailed = false
                    retryDelay = 1_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    games = emptyList()
                    directoryCache?.takeIf { it.platformUrl == platformUrl }?.games = emptyList()
                    loadFailed = true
                }
                loading = false
                delay(if (loadFailed) retryDelay else 30_000L)
                if (loadFailed) retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    // 第二主界面纯粹聚焦已审核上架的新版网游玩法，不再混入原版入口。
    val allEntries = games.mapIndexed { index, game ->
        HallEntry("game-${game.appId}", index % 3, game)
    }

    fun enterGame(game: OnlineGame, testing: Boolean = false) {
        if (!testing && !game.canEnter) {
            maintenanceGame = game
        } else if (!loggedIn) {
            pendingLogin = HallLoginRequest(game, testing)
            onLogin()
        } else if (testing) {
            passwordGame = game
        } else {
            onOpenPage(LauncherPage.GamePortal(game.appId))
        }
    }
    val expansionUnavailableText = hallText("expansionUnavailable")
    val latestEnter by rememberUpdatedState(::enterGame)
    LaunchedEffect(UI.pendingExpansionGameAppId) {
        val appId = UI.pendingExpansionGameAppId ?: return@LaunchedEffect
        try {
            val approved = refreshGame(appId)
            if (UI.pendingExpansionGameAppId != appId) return@LaunchedEffect
            if (approved == null || (approved.canEnter && approved.expansionBinding == null)) UI.showWarning(expansionUnavailableText)
            else latestEnter(approved, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UI.showWarning(expansionUnavailableText)
        } finally {
            if (UI.pendingExpansionGameAppId == appId) UI.pendingExpansionGameAppId = null
        }
    }

    LaunchedEffect(loggedIn, UI.showAccountView) {
        val pending = pendingLogin ?: return@LaunchedEffect
        if (loggedIn) {
            pendingLogin = null
            UI.showAccountView = false
            enterGame(games.firstOrNull { it.appId == pending.game.appId } ?: pending.game, pending.testing)
        } else if (!UI.showAccountView) pendingLogin = null
    }
    val favorite: (HallEntry) -> Unit = { entry ->
        favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
    }

    val hasFilters = query.isNotBlank() || availableOnly
    val trimmedQuery = query.trim()
    val entries = allEntries.filter { entry ->
        val matchesCategory = category != HallCategory.Favorites || entry.id in favorites
        val matchesQuery = trimmedQuery.isEmpty() ||
            entry.game.name.contains(trimmedQuery, ignoreCase = true) ||
            entry.game.description.contains(trimmedQuery, ignoreCase = true)
        matchesCategory && (!availableOnly || entry.canOpen) && matchesQuery
    }

    val selectedEntry = entries.firstOrNull { it.game.appId == selectedAppId } ?: entries.firstOrNull()
    // 保存有效选择，目录重排或从网页返回时仍按 appId 显示同一款游戏。
    if (!loading && !loadFailed) {
        val visibleAppId = selectedEntry?.game?.appId
        SideEffect {
            selectedAppId = visibleAppId
            if (visibleAppId == null) showMobileDetails = false
        }
    }
    val triggerRefresh: () -> Unit = {
        loading = true
        retry++
    }

    val categoryCounts = mapOf(
        HallCategory.All to allEntries.size,
        HallCategory.Favorites to favorites.count { favId -> allEntries.any { it.id == favId } },
    )

    if (LocalDialogInteractive.current) maintenanceGame?.let { game ->
        AlertDialog(
            onDismissRequest = { maintenanceGame = null },
            title = { Text(game.name) },
            text = { Text(hallText("maintenanceNotice")) },
            confirmButton = {
                TextButton(onClick = { maintenanceGame = null }) { Text(hallText("gotIt")) }
            },
            dismissButton = {
                if (game.testPasswordSet) TextButton(onClick = {
                    maintenanceGame = null
                    enterGame(game, testing = true)
                }) { Text(hallText("developerTest")) }
            },
        )
    }
    if (LocalDialogInteractive.current) passwordGame?.let { game ->
        if (loggedIn) key(game.appId) {
            HallDeveloperTestDialog(
                game = game,
                verify = verifyTestAccess,
                onDismiss = { passwordGame = null },
                onVerified = { access ->
                    passwordGame = null
                    onOpenPage(LauncherPage.GamePortal(game.appId, access))
                },
            )
        } else SideEffect { passwordGame = null }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("gameplay-hall")) {
        val compact = maxHeight < 500.dp
        val narrow = maxWidth < 540.dp || (maxWidth < 840.dp && maxWidth < maxHeight)
        val edge = if (narrow || compact) 12.dp else 24.dp
        val listWidth = (maxWidth * 0.25f).coerceIn(190.dp, 340.dp)
        val coverHeight = when {
            compact -> (maxHeight * 0.31f).coerceAtMost(140.dp)
            narrow -> ((maxWidth - edge * 2) * 0.56f).coerceAtMost(280.dp)
            else -> (maxHeight * 0.34f).coerceIn(154.dp, 320.dp)
        }
        val detailOnly = narrow && showMobileDetails && selectedEntry != null
        val goBack: () -> Unit = { if (detailOnly) showMobileDetails = false else onExit() }
        BackHandler(maintenanceGame == null && passwordGame == null, goBack)

        // 保留平台实时战场背景，叠加中性黑曜石遮罩与克制翡翠/深青氛围光，静置零动画帧。
        Box(
            Modifier
                .matchParentSize()
                .background(HallCanvasScrim)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x2826C873), Color(0x0E164E35), Color.Transparent),
                            center = Offset(size.width * 0.14f, size.height * 0.06f),
                            radius = (size.maxDimension * 0.56f).coerceAtLeast(1f),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x221B4D66), Color(0x0A0F2838), Color.Transparent),
                            center = Offset(size.width * 0.90f, size.height * 0.92f),
                            radius = (size.maxDimension * 0.50f).coerceAtLeast(1f),
                        ),
                    )
                },
        )

        Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
            Row(
                Modifier.fillMaxWidth().hallHeaderMotion(motion).heightIn(min = if (compact) 52.dp else 64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HallBackButton(goBack, compact) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = hallText(if (detailOnly) "backToList" else "back"),
                        tint = HallInkPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(hallText("title"), color = HallInkPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (allEntries.isNotEmpty() && !detailOnly) {
                    Spacer(Modifier.weight(1f))
                    if (narrow) {
                        HallRefreshButton(triggerRefresh)
                    } else {
                        HallDirectoryStatusStrip(
                            loading = loading, onRefresh = triggerRefresh, modifier = Modifier.widthIn(max = 360.dp),
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxSize().hallBodyMotion(motion)
                    .padding(top = 4.dp, bottom = if (compact) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
            ) {
                if (allEntries.isEmpty()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        HallDirectoryZeroStage(loading, loadFailed, compact, triggerRefresh)
                    }
                } else {
                    if (!detailOnly) {
                        Column(
                            modifier = (if (narrow) Modifier.fillMaxWidth() else Modifier.width(listWidth))
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                        ) {
                            HallSearch(query, { query = it }, compact, Modifier.fillMaxWidth())
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                HallCategory.entries.forEach { item ->
                                    HallCategoryChip(item, category == item, categoryCounts[item]) { category = item }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                HallFilterPill(
                                    label = hallText("status"),
                                    value = hallText(if (availableOnly) "available" else "any"),
                                    id = "status", active = availableOnly, options = listOf(false, true),
                                    title = { hallText(if (it) "available" else "any") },
                                    onSelect = { availableOnly = it },
                                )
                                if (hasFilters) {
                                    Surface(
                                        onClick = {
                                            query = ""
                                            availableOnly = false
                                        },
                                        color = Color.Transparent,
                                        shape = HallControlShape,
                                        modifier = Modifier.testTag("hall-reset"),
                                    ) {
                                        Text(hallText("reset"), Modifier.padding(6.dp), color = HallAccentBright, fontSize = 12.sp)
                                    }
                                }
                            }
                            LazyColumn(
                                state = contentListState,
                                modifier = Modifier.fillMaxWidth().weight(1f).testTag("hall-list"),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(entries, key = { it.id }) { entry ->
                                    HallLibraryRow(entry, selected = entry.id == selectedEntry?.id) {
                                        selectedAppId = entry.game.appId
                                        showMobileDetails = true
                                    }
                                }
                                if (entries.isEmpty() && narrow) {
                                    item { HallEmptyStateCard(category == HallCategory.Favorites && !hasFilters) }
                                }
                            }
                        }
                    }
                    if (!narrow || detailOnly) {
                        if (selectedEntry != null) {
                            key(selectedEntry.game.appId) {
                                HallGameDetails(
                                    entry = selectedEntry,
                                    favorite = selectedEntry.id in favorites,
                                    onFavorite = { favorite(selectedEntry) },
                                    onOpen = { enterGame(selectedEntry.game) },
                                    onTest = { enterGame(selectedEntry.game, testing = true) },
                                    coverHeight = coverHeight,
                                    compact = compact,
                                    modifier = Modifier.weight(1f).fillMaxHeight().hallFeedMotion(motion),
                                )
                            }
                        } else {
                            HallEmptyStateCard(category == HallCategory.Favorites && !hasFilters, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HallDeveloperTestDialog(
    game: OnlineGame,
    verify: suspend (String, String) -> GameTestAccess,
    onDismiss: () -> Unit,
    onVerified: (GameTestAccess) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var errorKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dismiss = {
        scope.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(hallText("developerTest")) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorKey = null
                },
                enabled = !checking,
                label = { Text(hallText("testPassword")) },
                modifier = Modifier.fillMaxWidth().testTag("hall-test-password"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                isError = errorKey != null,
                supportingText = errorKey?.let { key -> { Text(hallText(key)) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !checking && password.isNotEmpty(),
                onClick = {
                    if (!checking) {
                        checking = true
                        errorKey = null
                        scope.launch {
                            val access = try {
                                verify(game.appId, password)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                errorKey = when ((e as? GamePortalException)?.code) {
                                    "invalid_test_password" -> "testPasswordIncorrect"
                                    "rate_limited" -> "testRateLimited"
                                    "game_maintenance", "game_portal_unavailable" -> "maintenanceNotice"
                                    else -> "testAccessFailed"
                                }
                                return@launch
                            } finally {
                                checking = false
                            }
                            onVerified(access)
                        }
                    }
                },
            ) { Text(hallText(if (checking) "testVerifying" else "enterTest")) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(readI18n("common.cancel")) }
        },
    )
}

@Composable
private fun HallLibraryRow(entry: HallEntry, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onSelect,
        shape = HallControlShape,
        color = if (selected) HallSurfaceElevated else HallSurfaceLow,
        border = if (selected) BorderStroke(1.dp, HallAccent.copy(alpha = 0.55f)) else null,
        modifier = Modifier.fillMaxWidth().testTag("hall-card-${entry.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HallCover(entry, Modifier.size(60.dp, 42.dp).clip(RoundedCornerShape(6.dp)))
            Text(
                entry.game.name, Modifier.weight(1f), color = if (selected) HallInkPrimary else HallInkSecondary,
                fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HallGameDetails(
    entry: HallEntry,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    onTest: () -> Unit,
    coverHeight: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val telemetry = remember(entry.game) { entry.game.telemetry() }
    val telemetryLabel = HallTelemetryLabel(telemetry)
    val spacing = if (compact) 10.dp else 16.dp
    Column(
        modifier.testTag("hall-details").verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = coverHeight).clip(HallCardShape)) {
            HallCover(entry, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)),
            )))
            Text(
                entry.game.name,
                Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 12.dp),
                fontSize = if (compact) 24.sp else 30.sp, lineHeight = if (compact) 29.sp else 37.sp,
                color = HallInkPrimary, fontWeight = FontWeight.Bold,
            )
            HallCardFavoriteButton(favorite, entry.id, onFavorite, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onOpen,
                shape = HallControlShape,
                color = if (entry.canOpen) HallAccent else HallSurfaceElevated,
                modifier = Modifier.testTag("hall-open"),
            ) {
                Row(
                    Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tint = if (entry.canOpen) HallAccentDeep else HallInkSecondary
                    Text(
                        hallText(if (entry.canOpen) "enter" else "maintenance"),
                        color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    )
                    if (entry.canOpen) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(17.dp), tint = tint)
                }
            }
            if (!entry.canOpen && entry.game.testPasswordSet) {
                Surface(
                    onClick = onTest,
                    shape = HallControlShape,
                    color = HallSurfaceElevated,
                    border = BorderStroke(1.dp, HallHairlineStrong),
                    modifier = Modifier.testTag("hall-developer-test"),
                ) {
                    Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(hallText("developerTest"), color = HallInkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
            if (entry.canOpen && telemetryLabel != null) HallMetaGlassChip(telemetryLabel, HallAccentBright)
            if (telemetry.hasExpansion) HallMetaGlassChip(hallText("expansionTag"), HallAmber)
            if (telemetry.hasArtPack) {
                val version = telemetry.artVersion.takeIf { it.isNotBlank() }
                HallMetaGlassChip(listOfNotNull(hallText("artPackTag"), version).joinToString(" · "), HallCyan)
            }
        }
        if (entry.game.description.isNotBlank()) {
            Column(
                Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(hallText("description"), color = HallInkPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = entry.game.description,
                    modifier = Modifier.testTag("hall-description"),
                    color = HallInkPrimary.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 24.sp,
                )
            }
        }
    }
}

@Composable
private fun hallText(key: String): String = readI18n("gameplayHall.$key")

@Composable
private fun HallTelemetryLabel(telemetry: OnlineGameTelemetry): String? {
    val parts = mutableListOf<String>()
    if (telemetry.onlineServers == 1) {
        parts += hallText("serverOnlineSingle")
    } else if (telemetry.onlineServers > 1) {
        parts += hallText("serversOnline").replace("{0}", telemetry.onlineServers.toString())
    }
    if (telemetry.players > 0) {
        parts += hallText("playersOnline").replace("{0}", telemetry.players.toString())
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun HallBackButton(
    onClick: () -> Unit,
    compact: Boolean,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val scale = animateUiFloatAsState(
        targetValue = if (animated && pressed) 0.94f else 1f,
        animationSpec = tween(if (animated) 110 else 0, easing = StandardDecelerateEasing),
        label = "HallIconScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = when {
            pressed -> Color(0x24FFFFFF)
            hovered -> Color(0x16FFFFFF)
            else -> HallSurfaceLow
        },
        animationSpec = tween(if (animated) 150 else 0),
        label = "HallIconBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            hovered || pressed -> HallHairlineStrong
            else -> HallHairline
        },
        animationSpec = tween(if (animated) 150 else 0),
        label = "HallIconBorder",
    )
    val buttonSize = if (compact) 38.dp else 42.dp
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(buttonSize)
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = scale.value
                        scaleX = value
                        scaleY = value
                    }
                } else it
            }
            .background(bgColor, HallControlShape)
            .border(1.dp, borderColor, HallControlShape)
            .testTag("hall-back"),
        content = content,
    )
}

@Composable
private fun HallSearch(
    value: String,
    onChange: (String) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val animated = uiAnimationsEnabled()
    val borderColor by animateUiColorAsState(
        targetValue = when {
            focused -> HallAccent.copy(alpha = 0.72f)
            hovered -> HallHairlineStrong
            else -> HallHairline
        },
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallSearchBorder",
    )
    val bgColor by animateUiColorAsState(
        targetValue = if (focused) Color(0xE6141E24) else HallSurfaceLow,
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallSearchBg",
    )
    val iconTint by animateUiColorAsState(
        targetValue = if (focused) HallAccentBright else HallInkTertiary,
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallSearchIcon",
    )
    Row(
        modifier = modifier
            .height(if (compact) 38.dp else 42.dp)
            .background(bgColor, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .padding(start = 14.dp, end = if (value.isNotEmpty()) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(17.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = hallText("search"),
                    color = HallInkTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(HallAccent),
                textStyle = TextStyle(
                    color = HallInkPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                modifier = Modifier.fillMaxWidth().testTag("hall-search"),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onChange("") },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = hallText("clear"),
                    tint = HallInkSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun HallCategoryChip(
    category: HallCategory,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
) {
    val animated = uiAnimationsEnabled()
    val bgColor by animateUiColorAsState(
        targetValue = if (selected) HallAccent.copy(alpha = 0.18f) else HallSurfaceLow,
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallChipBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = if (selected) HallAccent.copy(alpha = 0.45f) else HallHairline,
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallChipBorder",
    )
    val textColor by animateUiColorAsState(
        targetValue = if (selected) HallInkPrimary else HallInkSecondary,
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallChipText",
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.testTag("hall-category-${category.key}"),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HallCategoryIcon(
                category = category,
                selected = selected,
                tint = if (selected) HallAccentBright else HallInkTertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = hallText("categories.${category.key}"),
                color = textColor,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    color = if (selected) HallAccentBright else HallInkTertiary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun HallCategoryIcon(
    category: HallCategory,
    selected: Boolean,
    tint: Color,
    modifier: Modifier,
) {
    when (category) {
        HallCategory.All -> Icon(painterResource(Res.drawable.dns_30), null, modifier, tint = tint)
        HallCategory.Favorites -> HallStar(selected, null, tint, modifier)
    }
}

@Composable
private fun HallDirectoryStatusStrip(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (loading) HallAmber else HallAccent, CircleShape))
        Text(
            text = hallText(if (loading) "directoryLoading" else "directoryReady"),
            color = HallInkTertiary,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("hall-directory-status"),
        )
        HallRefreshButton(onClick = onRefresh)
    }
}

@Composable
private fun HallRefreshButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val animated = uiAnimationsEnabled()
    val bgColor by animateUiColorAsState(
        targetValue = if (hovered) HallAccent.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(if (animated) 140 else 0),
        label = "HallRefreshBg",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier.testTag("hall-refresh"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = HallAccentBright,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = hallText("refresh"),
                color = HallAccentBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun <T> HallFilterPill(
    label: String,
    value: String,
    id: String,
    active: Boolean,
    options: List<T>,
    title: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val animated = uiAnimationsEnabled()
    val bgColor by animateUiColorAsState(
        targetValue = when {
            active -> HallAccent.copy(alpha = 0.14f)
            hovered -> Color(0x18FFFFFF)
            else -> HallSurfaceLow
        },
        animationSpec = tween(if (animated) 140 else 0),
        label = "HallFilterBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            active -> HallAccent.copy(alpha = 0.42f)
            hovered -> HallHairlineStrong
            else -> HallHairline
        },
        animationSpec = tween(if (animated) 140 else 0),
        label = "HallFilterBorder",
    )
    Box {
        Surface(
            onClick = { expanded = true },
            interactionSource = interactionSource,
            color = bgColor,
            shape = CircleShape,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.testTag("hall-filter-$id"),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .padding(start = 12.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("$label:", color = HallInkTertiary, fontSize = 12.sp)
                Text(
                    text = value,
                    color = if (active) HallAccentBright else HallInkPrimary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (active) HallAccentBright else HallInkTertiary,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = HallSurfaceElevated,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(title(option), color = HallInkPrimary, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** 0 个上架网游时的独立舞台（加载中 / 拉取失败 / 暂无上架）：视觉完整、信息明确，绝不虚构玩法。 */
@Composable
private fun HallDirectoryZeroStage(
    loading: Boolean,
    loadFailed: Boolean,
    compact: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        loadFailed -> HallCoral
        loading -> HallAmber
        else -> HallAccent
    }
    val titleKey = when {
        loading -> "loadingDirectoryTitle"
        loadFailed -> "failedDirectoryTitle"
        else -> "emptyDirectoryTitle"
    }
    val statusKey = when {
        loading -> "directoryLoading"
        loadFailed -> "directoryFailed"
        else -> "directoryEmpty"
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 230.dp else 360.dp)
            .clip(HallHeroShape)
            .background(HallSurfaceCard)
            .drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.44f)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                        center = center,
                        radius = (size.maxDimension * 0.46f).coerceAtLeast(140.dp.toPx()),
                    ),
                )
                listOf(64.dp.toPx(), 112.dp.toPx(), 164.dp.toPx()).forEachIndexed { idx, radius ->
                    drawCircle(
                        color = accent.copy(alpha = (0.14f - idx * 0.035f).coerceAtLeast(0.03f)),
                        radius = radius,
                        center = center,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.38f), Color.Transparent),
                    ),
                    start = Offset(24.dp.toPx(), 1.dp.toPx()),
                    end = Offset(size.width - 24.dp.toPx(), 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .border(1.dp, HallHairline, HallHeroShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(horizontal = 24.dp, vertical = if (compact) 20.dp else 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 46.dp else 56.dp)
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .border(1.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (loadFailed) Res.drawable.public_30 else Res.drawable.extension_30),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp),
                )
            }
            Text(
                text = hallText(titleKey),
                color = HallInkPrimary,
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = hallText(statusKey),
                color = if (loadFailed) HallCoral.copy(alpha = 0.92f) else HallInkSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("hall-directory-status"),
            )
            if (!loading && !loadFailed) Surface(
                onClick = onRefresh,
                shape = CircleShape,
                color = HallAccent.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, HallAccent.copy(alpha = 0.44f)),
                modifier = Modifier.padding(top = 4.dp).testTag("hall-refresh"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = HallAccentBright)
                    Text(
                        text = hallText("refresh"),
                        color = HallAccentBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HallEmptyStateCard(
    isFavorites: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HallCardShape)
            .background(HallSurfaceLow)
            .border(1.dp, HallHairline, HallCardShape)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0x10FFFFFF), CircleShape)
                .border(1.dp, HallHairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isFavorites) Icons.Default.Star else Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = HallInkTertiary,
            )
        }
        Text(
            text = hallText(if (isFavorites) "emptyFavorites" else "empty"),
            color = HallInkSecondary,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HallCardFavoriteButton(
    favorite: Boolean,
    entryId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated = uiAnimationsEnabled()
    val starColor by animateUiColorAsState(
        targetValue = if (favorite) HallAmber else HallInkPrimary,
        animationSpec = tween(if (animated) 180 else 0),
        label = "HallCardStarColor",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(38.dp)
            .background(HallGlassDark, CircleShape)
            .border(1.dp, if (favorite) HallAmber.copy(alpha = 0.45f) else HallHairline, CircleShape)
            .testTag("hall-favorite-$entryId"),
    ) {
        HallStar(
            selected = favorite,
            description = hallText(if (favorite) "unfavorite" else "favorite"),
            color = starColor,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun HallMetaGlassChip(text: String, accent: Color) {
    Text(
        text = text,
        color = accent,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(accent.copy(alpha = 0.11f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.20f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HallCover(entry: HallEntry, modifier: Modifier) {
    Box(modifier) {
        HallBattlefield(entry.scene, Modifier.matchParentSize())
        entry.game.coverUrl.takeIf(String::isNotBlank)?.let { url ->
            AsyncImage(url, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun HallStar(selected: Boolean, description: String?, color: Color, modifier: Modifier = Modifier) {
    val animated = uiAnimationsEnabled()
    val scale = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, animated) {
        if (selected && !wasSelected && animated) {
            scale.snapTo(1f)
            scale.animateTo(1.22f, tween(95, easing = StandardDecelerateEasing))
            scale.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 550f))
        } else if (scale.value != 1f) {
            scale.snapTo(1f)
        }
        wasSelected = selected
    }
    val iconModifier = if (animated) {
        modifier.graphicsLayer {
            val value = scale.value
            scaleX = value
            scaleY = value
        }
    } else modifier
    if (selected) Icon(Icons.Default.Star, description, iconModifier, tint = color)
    else Icon(painterResource(Res.drawable.gameplay_favorite_outline), description, iconModifier, tint = color)
}

private const val BATTLEFIELD_BITMAP_WIDTH = 960
private const val BATTLEFIELD_BITMAP_HEIGHT = 336
private val hallBattlefieldCacheLock = Any()
private val hallBattlefieldCache = mutableMapOf<Int, ImageBitmap>()

private fun cachedHallBattlefieldBitmap(scene: Int): ImageBitmap {
    val key = when (scene) {
        1 -> 1
        2 -> 2
        else -> 0
    }
    synchronized(hallBattlefieldCacheLock) {
        hallBattlefieldCache[key]?.let { return it }
    }
    val bitmap = ImageBitmap(BATTLEFIELD_BITMAP_WIDTH, BATTLEFIELD_BITMAP_HEIGHT)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(bitmap),
        size = Size(BATTLEFIELD_BITMAP_WIDTH.toFloat(), BATTLEFIELD_BITMAP_HEIGHT.toFloat()),
    ) {
        drawHallBattlefieldScene(key)
    }
    synchronized(hallBattlefieldCacheLock) {
        return hallBattlefieldCache.getOrPut(key) { bitmap }
    }
}

internal fun prewarmHallBattlefieldBitmaps() {
    for (scene in 0..2) cachedHallBattlefieldBitmap(scene)
}

/** 静态战术沙盘兜底插画：当网游未提供封面图或图片加载中时显示，离屏位图缓存、零逐帧重绘开销。 */
@Composable
private fun HallBattlefield(scene: Int, modifier: Modifier) {
    val bitmap = remember(scene) { cachedHallBattlefieldBitmap(scene) }
    Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

private fun DrawScope.drawHallBattlefieldScene(scene: Int) {
    withTransform({ scale(size.width / 800f, size.height / 280f, pivot = Offset.Zero) }) {
        when (scene) {
            1 -> drawSurvivalCitadelScene()
            2 -> drawCampaignReconScene()
            else -> drawMultiplayerFrontlineScene()
        }
    }
}

private fun DrawScope.drawTacticalGrid(lineColor: Color) {
    for (x in 0..800 step 40) {
        drawLine(lineColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), 280f), 1f)
    }
    for (y in 0..280 step 40) {
        drawLine(lineColor, Offset(0f, y.toFloat()), Offset(800f, y.toFloat()), 1f)
    }
}

private fun DrawScope.drawMultiplayerFrontlineScene() {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0E1B1A), Color(0xFF132622), Color(0xFF161E24)),
            start = Offset.Zero,
            end = Offset(800f, 280f),
        ),
        size = Size(800f, 280f),
    )
    drawTacticalGrid(Color(0x0AFFFFFF))

    repeat(4) { i ->
        drawCircle(
            color = Color(0x103CE38A),
            radius = 120f + i * 54f,
            center = Offset(170f, 110f),
            style = Stroke(1.2f),
        )
        drawCircle(
            color = Color(0x10E88257),
            radius = 110f + i * 50f,
            center = Offset(660f, 185f),
            style = Stroke(1.2f),
        )
    }

    val river = Path().apply {
        moveTo(355f, -20f)
        cubicTo(285f, 95f, 495f, 135f, 400f, 305f)
        lineTo(468f, 305f)
        cubicTo(555f, 135f, 355f, 95f, 422f, -20f)
        close()
    }
    drawPath(river, Color(0xFF1D4F63), style = Stroke(12f))
    drawPath(river, Color(0xFF0D2835))

    drawRoundRect(Color(0xFF28373E), Offset(375f, 118f), Size(92f, 32f), CornerRadius(4f))
    drawRoundRect(Color(0xFF47636E), Offset(375f, 118f), Size(92f, 32f), CornerRadius(4f), style = Stroke(1.5f))
    repeat(7) { i ->
        drawLine(Color(0xFF1A252B), Offset(387f + i * 11f, 121f), Offset(387f + i * 11f, 147f), 1.6f)
    }

    val teams = listOf(
        Triple(Offset(175f, 96f), Color(0xFF56AEE8), 108f),
        Triple(Offset(640f, 188f), Color(0xFFE88257), -72f),
    )
    teams.forEachIndexed { idx, (center, color, angle) ->
        drawCircle(color.copy(alpha = 0.14f), 64f, center)
        drawRoundRect(Color(0xFF0C1418), center - Offset(34f, 28f), Size(68f, 56f), CornerRadius(7f))
        drawRoundRect(color.copy(alpha = 0.72f), center - Offset(34f, 28f), Size(68f, 56f), CornerRadius(7f), style = Stroke(2f))
        drawRoundRect(color.copy(alpha = 0.24f), center - Offset(22f, 18f), Size(44f, 36f), CornerRadius(4f))
        drawCircle(color, 7.5f, center)
        repeat(6) { unit ->
            val point = Offset(
                if (idx == 0) 252f + (unit % 3) * 32f else 512f + (unit % 3) * 32f,
                72f + (unit / 3) * 58f + idx * 38f,
            )
            rotate(angle, point) {
                drawRoundRect(Color(0xFF0D171B), point - Offset(9f, 13f), Size(18f, 26f), CornerRadius(3f))
                drawRect(color.copy(alpha = 0.9f), point - Offset(5.5f, 9f), Size(11f, 18f))
                drawLine(Color(0xFFD8F0FF), point, point - Offset(0f, 19f), 2.2f)
            }
        }
    }

    val arc1 = Path().apply {
        moveTo(318f, 96f)
        quadraticTo(425f, 64f, 520f, 128f)
    }
    val arc2 = Path().apply {
        moveTo(525f, 162f)
        quadraticTo(420f, 198f, 326f, 144f)
    }
    drawPath(arc1, Color(0xAA78D8FF), style = Stroke(1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
    drawPath(arc2, Color(0xAAF7B267), style = Stroke(1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))

    listOf(Offset(422f, 134f), Offset(486f, 108f), Offset(374f, 160f)).forEachIndexed { idx, p ->
        val glow = if (idx == 0) Color(0xFFF6C869) else Color(0xFF69E6A6)
        drawCircle(glow.copy(alpha = 0.16f), 26f, p)
        drawCircle(glow.copy(alpha = 0.42f), 11f, p)
        drawCircle(Color(0xFFFFF5D9), 4f, p)
    }
}

private fun DrawScope.drawSurvivalCitadelScene() {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0B1715), Color(0xFF10241E), Color(0xFF171E1D)),
            start = Offset.Zero,
            end = Offset(800f, 280f),
        ),
        size = Size(800f, 280f),
    )
    drawTacticalGrid(Color(0x093CE38A))

    val nexus = Offset(510f, 145f)
    listOf(48f, 94f, 146f, 202f, 260f).forEachIndexed { i, r ->
        drawCircle(
            color = HallAccent.copy(alpha = (0.24f - i * 0.036f).coerceAtLeast(0.05f)),
            radius = r,
            center = nexus,
            style = Stroke(if (i == 1) 1.6f else 1.1f),
        )
    }
    drawArc(
        brush = Brush.radialGradient(
            colors = listOf(HallAccent.copy(alpha = 0.24f), HallAccent.copy(alpha = 0.05f), Color.Transparent),
            center = nexus,
            radius = 230f,
        ),
        startAngle = -148f,
        sweepAngle = 56f,
        useCenter = true,
        topLeft = Offset(nexus.x - 230f, nexus.y - 230f),
        size = Size(460f, 460f),
    )

    drawCircle(HallAccent.copy(alpha = 0.18f), 42f, nexus)
    drawRoundRect(Color(0xFF0C1814), nexus - Offset(24f, 24f), Size(48f, 48f), CornerRadius(10f))
    drawRoundRect(HallAccent, nexus - Offset(24f, 24f), Size(48f, 48f), CornerRadius(10f), style = Stroke(2f))
    drawCircle(HallAccentBright, 8f, nexus)

    repeat(6) { idx ->
        val rad = (idx * 60.0 + 18.0) * PI / 180.0
        val turret = Offset(nexus.x + (cos(rad) * 76f).toFloat(), nexus.y + (sin(rad) * 76f).toFloat())
        drawCircle(Color(0xFF10251F), 10f, turret)
        drawCircle(HallAccent.copy(alpha = 0.78f), 10f, turret, style = Stroke(1.6f))
        drawCircle(HallAccentBright, 3.5f, turret)
    }

    val threats = listOf(
        Offset(240f, 44f), Offset(285f, 92f), Offset(230f, 165f),
        Offset(295f, 224f), Offset(695f, 52f), Offset(725f, 198f),
    )
    threats.forEach { t ->
        drawLine(
            brush = Brush.linearGradient(listOf(Color(0xAAEE7B75), Color.Transparent), t, nexus),
            start = t,
            end = Offset(t.x + (nexus.x - t.x) * 0.48f, t.y + (nexus.y - t.y) * 0.48f),
            strokeWidth = 1.6f,
        )
        drawCircle(Color(0x33EE7B75), 14f, t)
        drawCircle(Color(0xFFEE7B75), 5f, t)
    }
}

private fun DrawScope.drawCampaignReconScene() {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0C151D), Color(0xFF11202B), Color(0xFF121C22)),
            start = Offset.Zero,
            end = Offset(800f, 280f),
        ),
        size = Size(800f, 280f),
    )
    drawTacticalGrid(Color(0x0A7ADBF0))

    val contourCenters = listOf(Offset(280f, 180f), Offset(565f, 128f))
    contourCenters.forEach { c ->
        repeat(4) { i ->
            drawOval(
                color = HallCyan.copy(alpha = 0.14f - i * 0.024f),
                topLeft = Offset(c.x - (60f + i * 36f), c.y - (34f + i * 22f)),
                size = Size((60f + i * 36f) * 2f, (34f + i * 22f) * 2f),
                style = Stroke(1.2f),
            )
        }
    }

    val waypoints = listOf(Offset(210f, 212f), Offset(360f, 134f), Offset(468f, 174f), Offset(572f, 126f))
    val route = Path().apply {
        moveTo(waypoints.first().x, waypoints.first().y)
        waypoints.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = route,
        color = HallCyan.copy(alpha = 0.72f),
        style = Stroke(2.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 7f))),
    )
    waypoints.dropLast(1).forEach { wp ->
        drawCircle(Color(0xFF0F212B), 8f, wp)
        drawCircle(HallCyan, 8f, wp, style = Stroke(1.8f))
        drawCircle(HallCyan, 3f, wp)
    }

    val target = waypoints.last()
    drawCircle(HallAmber.copy(alpha = 0.15f), 50f, target)
    drawCircle(HallAmber.copy(alpha = 0.78f), 32f, target, style = Stroke(1.8f))
    drawCircle(HallCyan.copy(alpha = 0.45f), 46f, target, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
    drawLine(HallAmber, Offset(target.x - 42f, target.y), Offset(target.x - 16f, target.y), 2f)
    drawLine(HallAmber, Offset(target.x + 16f, target.y), Offset(target.x + 42f, target.y), 2f)
    drawLine(HallAmber, Offset(target.x, target.y - 42f), Offset(target.x, target.y - 16f), 2f)
    drawLine(HallAmber, Offset(target.x, target.y + 16f), Offset(target.x, target.y + 42f), 2f)
    drawCircle(HallAmberSoft, 5.5f, target)
}
