/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.rwpp.account.AccountSession
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.pollWhileVisible
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameClient
import io.github.rwpp.net.games.isOnlineGamePortalUrl
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.dns_30
import io.github.rwpp.rwpp_core.generated.resources.extension_30
import io.github.rwpp.rwpp_core.generated.resources.gameplay_favorite_outline
import io.github.rwpp.rwpp_core.generated.resources.public_30
import io.github.rwpp.rwpp_core.generated.resources.shield_30
import io.github.rwpp.widget.LocalDialogInteractive
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.animateUiDpAsState
import io.github.rwpp.widget.animateUiFloatAsState
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// 深空黑曜石 + 翡翠微光色彩体系：以中性暗面承载画面层次，仅在焦点与主操作点亮品牌绿。
private val HallCanvasScrim = Color(0xEB080C0F)
private val HallSurfaceLow = Color(0xCC0F161B)
private val HallSurfaceCard = Color(0xF0131C22)
private val HallSurfaceCardHover = Color(0xF519252D)
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
private val HallHairlineSoft = Color(0x10FFFFFF)
private val HallHairlineStrong = Color(0x2EFFFFFF)

private val HallHeroShape = RoundedCornerShape(22.dp)
private val HallCardShape = RoundedCornerShape(18.dp)
private val HallControlShape = RoundedCornerShape(12.dp)

private enum class HallCategory(val key: String) {
    Recommended("recommended"),
    All("all"),
    Favorites("favorites"),
}

private data class HallEntry(
    val id: String,
    val scene: Int,
    val game: OnlineGame,
) {
    val available: Boolean get() = game.available
    val canOpen: Boolean get() = isOnlineGamePortalUrl(game.portalUrl) && game.portalRevision > 0
}

private data class OnlineGameTelemetry(
    val onlineServers: Int,
    val totalServers: Int,
    val players: Int,
    val maxPlayers: Int,
    val hasExpansion: Boolean,
    val hasArtPack: Boolean,
    val artVersion: String,
)

private fun OnlineGame.telemetry(): OnlineGameTelemetry {
    val active = servers.filter { it.online && it.available }
    val onlineList = servers.filter { it.online }
    return OnlineGameTelemetry(
        onlineServers = active.size,
        totalServers = servers.size,
        players = onlineList.sumOf { it.currentPlayers },
        maxPlayers = onlineList.sumOf { it.maxPlayers },
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

/** 按条目数量与可用宽度智能排布列数：1 项走全宽横版展卡，2/4 项走双列消除孤卡，3/5+ 项走三列画廊。 */
private fun adaptiveHallColumns(itemCount: Int, availableWidth: Dp, narrow: Boolean): Int {
    if (narrow || itemCount <= 1) return 1
    val maxCols = when {
        availableWidth >= 820.dp -> 3
        availableWidth >= 480.dp -> 2
        else -> 1
    }
    return when (itemCount) {
        2, 4 -> minOf(2, maxCols)
        else -> minOf(itemCount, maxCols)
    }
}

internal const val HALL_DEV_UNLOCK_KEY_COUNT = 5
internal const val HALL_DEV_UNLOCK_WINDOW_MS = 4_000L

/**
 * 开发者测试解锁状态机：第二主界面默认由全屏毛玻璃遮罩覆盖，
 * 连续按下 5 次 Q 键后放行，供开发与测试验证实际大厅内容。
 */
internal class HallDevUnlockTracker(
    private val requiredCount: Int = HALL_DEV_UNLOCK_KEY_COUNT,
    private val timeoutMillis: Long = HALL_DEV_UNLOCK_WINDOW_MS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var count = 0
    private var lastPressMs = Long.MIN_VALUE
    private var keyHeld = false

    fun onQKeyDown(): Boolean {
        if (keyHeld) return false
        keyHeld = true
        return recordQPress()
    }

    fun onQKeyUp() {
        keyHeld = false
    }

    fun onFallbackQPress(): Boolean = recordQPress()

    fun onOtherKeyDown() {
        reset()
    }

    fun onFocusLost() {
        keyHeld = false
    }

    fun reset() {
        count = 0
        lastPressMs = Long.MIN_VALUE
        keyHeld = false
    }

    private fun recordQPress(): Boolean {
        val now = nowMillis()
        count = if (lastPressMs != Long.MIN_VALUE && now - lastPressMs <= timeoutMillis) {
            count + 1
        } else {
            1
        }
        lastPressMs = now
        if (count >= requiredCount) {
            reset()
            return true
        }
        return false
    }
}

private fun isHallModifierKey(key: Key): Boolean = when (key) {
    Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
    Key.CapsLock, Key.NumLock, Key.ScrollLock -> true
    else -> false
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
    initialDevUnlocked: Boolean = false,
    devUnlockClockMillis: () -> Long = { System.currentTimeMillis() },
) {
    val animated = uiAnimationsEnabled()
    val interactive = LocalDialogInteractive.current
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
    var category by rememberSaveable { mutableStateOf(HallCategory.Recommended) }
    var query by rememberSaveable { mutableStateOf("") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(listOf<String>()) }
    var featuredIndex by rememberSaveable { mutableIntStateOf(0) }
    var devUnlocked by rememberSaveable { mutableStateOf(initialDevUnlocked) }
    val contentListState = rememberLazyListState()
    var pendingPortal by remember { mutableStateOf<String?>(null) }
    var games by remember { mutableStateOf(cachedInitial) }
    var loading by remember { mutableStateOf(cachedInitial.isEmpty()) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val latestLoad by rememberUpdatedState(loadGames)
    LaunchedEffect(devUnlocked, retry, platformUrl) {
        if (!devUnlocked) return@LaunchedEffect
        LauncherVisibility.visible.pollWhileVisible(30_000) {
            try {
                val loaded = latestLoad()
                games = loaded
                directoryCache?.let {
                    it.platformUrl = platformUrl
                    it.games = loaded
                }
                loadFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                games = emptyList()
                directoryCache?.takeIf { it.platformUrl == platformUrl }?.games = emptyList()
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    // 第二主界面纯粹聚焦已审核上架的新版网游玩法，不再混入原版入口。
    val allEntries = games.mapIndexed { index, game ->
        HallEntry("game-${game.appId}", index % 3, game)
    }

    val expansionUnavailableText = hallText("expansionUnavailable")
    LaunchedEffect(devUnlocked, UI.pendingExpansionGameAppId) {
        if (!devUnlocked) return@LaunchedEffect
        val appId = UI.pendingExpansionGameAppId ?: return@LaunchedEffect
        try {
            val approved = refreshGame(appId)
            if (UI.pendingExpansionGameAppId != appId) return@LaunchedEffect
            if (approved?.expansionBinding == null || !isOnlineGamePortalUrl(approved.portalUrl)) UI.showWarning(expansionUnavailableText)
            else if (loggedIn) onOpenPage(LauncherPage.GamePortal(approved.appId))
            else {
                pendingPortal = approved.appId
                onLogin()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UI.showWarning(expansionUnavailableText)
        } finally {
            if (UI.pendingExpansionGameAppId == appId) UI.pendingExpansionGameAppId = null
        }
    }

    val featuredEntries = allEntries.take(4)
    val selectedFeatured = if (featuredEntries.isEmpty()) 0 else featuredIndex.coerceIn(featuredEntries.indices)
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
        val game = entry.game
        if (!isOnlineGamePortalUrl(game.portalUrl) || game.portalRevision <= 0) UI.showWarning(portalUnavailable)
        else if (loggedIn) onOpenPage(LauncherPage.GamePortal(game.appId))
        else {
            pendingPortal = game.appId
            onLogin()
        }
    }
    val favorite: (HallEntry) -> Unit = { entry ->
        favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
    }

    val hasFilters = query.isNotBlank() || availableOnly
    val entries = allEntries.filter { entry ->
        val matchesCategory = when (category) {
            HallCategory.Favorites -> entry.id in favorites
            else -> true
        }
        val trimmedQuery = query.trim()
        val matchesQuery = trimmedQuery.isEmpty() ||
            entry.game.name.contains(trimmedQuery, ignoreCase = true) ||
            entry.game.description.contains(trimmedQuery, ignoreCase = true)
        matchesCategory && (!availableOnly || entry.available) && matchesQuery
    }

    val showFeatured = category == HallCategory.Recommended && !hasFilters && featuredEntries.isNotEmpty()
    val singleOnlinePremiere = showFeatured && allEntries.size == 1

    val categoryCounts = mapOf(
        HallCategory.All to allEntries.size,
        HallCategory.Favorites to favorites.count { favId -> allEntries.any { it.id == favId } },
    )

    BoxWithConstraints(Modifier.fillMaxSize().testTag("gameplay-hall")) {
        val narrow = maxWidth < 700.dp
        val compact = maxHeight < 500.dp
        val mediumHeight = maxHeight < 680.dp
        val edge = if (narrow || compact) 12.dp else 24.dp

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

        if (devUnlocked) Column(Modifier.fillMaxSize()) {
            // 顶部极简指挥栏
            Column(
                Modifier
                    .fillMaxWidth()
                    .hallHeaderMotion(motion)
                    .drawBehind {
                        drawLine(
                            color = HallHairlineSoft,
                            start = Offset(0f, size.height - 0.5.dp.toPx()),
                            end = Offset(size.width, size.height - 0.5.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(horizontal = edge),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compact) 52.dp else 60.dp)
                        .padding(vertical = if (compact) 6.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                ) {
                    HallIconButton(onExit, "back", compact = compact) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = hallText("back"),
                            tint = HallInkPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = hallText("title"),
                                color = HallInkPrimary,
                                fontSize = if (compact || narrow) 20.sp else 23.sp,
                                lineHeight = if (compact || narrow) 24.sp else 28.sp,
                                letterSpacing = (-0.3).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            HallDevStatusPill(
                                expanded = false,
                                compact = compact || narrow,
                                onClick = { devUnlocked = false },
                            )
                        }
                        if (!compact) {
                            Text(
                                text = hallText("subtitle"),
                                color = HallInkTertiary,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!narrow) {
                        HallSearch(
                            value = query,
                            onChange = { query = it },
                            compact = compact,
                            modifier = Modifier.widthIn(min = 210.dp, max = 340.dp).weight(0.95f),
                        )
                    }
                    HallIconButton(
                        onClick = {
                            category = if (category == HallCategory.Favorites) HallCategory.Recommended else HallCategory.Favorites
                        },
                        id = "favorites",
                        active = category == HallCategory.Favorites,
                        compact = compact,
                    ) {
                        val favActive = category == HallCategory.Favorites
                        Box(contentAlignment = Alignment.Center) {
                            HallStar(
                                selected = favActive,
                                description = hallText("categories.favorites"),
                                color = if (favActive) HallAmber else HallInkSecondary,
                                modifier = Modifier.size(19.dp),
                            )
                            if (!favActive && favorites.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(6.dp)
                                        .background(HallAmber, CircleShape),
                                )
                            }
                        }
                    }
                }
                if (narrow) {
                    HallSearch(
                        value = query,
                        onChange = { query = it },
                        compact = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HallCategory.entries.forEach { item ->
                            HallCategoryChip(
                                category = item,
                                selected = category == item,
                                count = categoryCounts[item],
                                onClick = { category = item },
                            )
                        }
                    }
                }
            }

            // 主体内容区：左侧极简导轨 + 右侧全数量自适应舞台
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .hallBodyMotion(motion)
                    .padding(horizontal = edge),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
            ) {
                if (!narrow) {
                    Column(
                        modifier = Modifier
                            .width(if (compact) 162.dp else 192.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                drawLine(
                                    color = HallHairlineSoft,
                                    start = Offset(size.width + (if (compact) 8.dp else 11.dp).toPx(), 10.dp.toPx()),
                                    end = Offset(size.width + (if (compact) 8.dp else 11.dp).toPx(), size.height - 16.dp.toPx()),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            .verticalScroll(rememberScrollState())
                            .padding(top = if (compact) 10.dp else 14.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HallCategory.entries.forEach { item ->
                            HallNavRailItem(
                                category = item,
                                selected = category == item,
                                count = categoryCounts[item],
                                compact = compact,
                                onClick = { category = item },
                            )
                        }
                        if (!compact) {
                            Spacer(Modifier.height(14.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp)
                                    .height(1.dp)
                                    .background(HallHairlineSoft),
                            )
                            Text(
                                text = hallText("discoveryNote"),
                                color = HallInkTertiary.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                lineHeight = 16.5.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                            )
                        }
                    }
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val contentWidth = maxWidth
                    val itemSpacing = if (compact) 10.dp else 12.dp
                    val triggerRefresh: () -> Unit = {
                        loading = true
                        retry++
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("hall-content"),
                        state = contentListState,
                        verticalArrangement = Arrangement.spacedBy(itemSpacing),
                        contentPadding = PaddingValues(
                            top = if (compact) 10.dp else 12.dp,
                            bottom = 24.dp,
                        ),
                    ) {
                        when {
                            // 0 个上架网游（加载中 / 拉取失败 / 暂无上架）：呈现独立黑曜石空态舞台，绝不捏造演示玩法。
                            allEntries.isEmpty() -> {
                                item("zero-games-stage") {
                                    HallDirectoryZeroStage(
                                        loading = loading,
                                        loadFailed = loadFailed,
                                        compact = compact,
                                        onRefresh = triggerRefresh,
                                        modifier = Modifier.hallFeedMotion(motion),
                                    )
                                }
                            }

                            // 刚开业仅 1 个网游 · 推荐页：沉浸式旗舰首发主舞台 + 三格真实态势 Bento 面板 + 极简目录尾条。
                            singleOnlinePremiere -> {
                                val flagship = featuredEntries.first()
                                item("featured") {
                                    HallFeaturedStage(
                                        entries = featuredEntries,
                                        index = 0,
                                        singleOnlinePremiere = true,
                                        isFavorite = flagship.id in favorites,
                                        onToggleFavorite = { favorite(flagship) },
                                        compact = compact,
                                        mediumHeight = mediumHeight,
                                        narrow = narrow,
                                        onSelect = {},
                                        onOpen = { open(flagship) },
                                    )
                                }
                                item("single-intel-deck") {
                                    HallSingleGameIntelDeck(
                                        entry = flagship,
                                        compact = compact,
                                        narrow = narrow,
                                        modifier = Modifier.hallFeedMotion(motion),
                                    )
                                }
                                item("single-directory-bar") {
                                    HallDirectoryStatusStrip(
                                        loading = loading,
                                        loadFailed = loadFailed,
                                        hasOnlineGames = true,
                                        narrow = narrow,
                                        onRefresh = triggerRefresh,
                                        modifier = Modifier.hallFeedMotion(motion),
                                    )
                                }
                            }

                            // 后续 2+ 个网游（推荐页），以及全部玩法 / 收藏 / 搜索筛选态
                            else -> {
                                if (showFeatured) {
                                    item("featured") {
                                        val currentFeatured = featuredEntries[selectedFeatured]
                                        HallFeaturedStage(
                                            entries = featuredEntries,
                                            index = selectedFeatured,
                                            singleOnlinePremiere = false,
                                            isFavorite = currentFeatured.id in favorites,
                                            onToggleFavorite = { favorite(currentFeatured) },
                                            compact = compact,
                                            mediumHeight = mediumHeight,
                                            narrow = narrow,
                                            onSelect = { featuredIndex = it },
                                            onOpen = { open(currentFeatured) },
                                        )
                                    }
                                }

                                item("controls-header") {
                                    val sectionTitle = when (category) {
                                        HallCategory.Recommended -> hallText("onlineSection")
                                        else -> hallText("categories.${category.key}")
                                    }
                                    HallSectionControlBar(
                                        title = sectionTitle,
                                        count = entries.size,
                                        availableOnly = availableOnly,
                                        hasFilters = hasFilters,
                                        onSelectAvailable = { availableOnly = it },
                                        onResetFilters = {
                                            query = ""
                                            availableOnly = false
                                        },
                                        loading = loading,
                                        loadFailed = loadFailed,
                                        hasOnlineGames = games.isNotEmpty(),
                                        onRefresh = triggerRefresh,
                                        narrow = narrow,
                                        compact = compact,
                                        modifier = Modifier.hallFeedMotion(motion),
                                    )
                                }

                                if (entries.isEmpty()) {
                                    item("empty") {
                                        HallEmptyStateCard(
                                            isFavorites = category == HallCategory.Favorites && !hasFilters,
                                            modifier = Modifier.hallFeedMotion(motion),
                                        )
                                    }
                                } else {
                                    hallAdaptiveSectionItems(
                                        sectionKey = "online",
                                        items = entries,
                                        availableWidth = contentWidth,
                                        narrow = narrow,
                                        compact = compact,
                                        favorites = favorites,
                                        onFavorite = favorite,
                                        onOpen = open,
                                        motion = motion,
                                    )
                                    if (category == HallCategory.All && !hasFilters && entries.size == 1) {
                                        item("single-all-more-coming") {
                                            HallEcosystemFooterNote(
                                                compact = compact,
                                                modifier = Modifier.hallFeedMotion(motion),
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

        AnimatedVisibility(
            visible = !devUnlocked,
            enter = if (animated) fadeIn(tween(180, easing = StandardDecelerateEasing)) else EnterTransition.None,
            exit = if (animated) fadeOut(tween(220, easing = StandardDecelerateEasing)) else ExitTransition.None,
            modifier = Modifier.matchParentSize(),
        ) {
            HallUnderDevelopmentOverlay(
                active = !devUnlocked && interactive,
                compact = compact,
                narrow = narrow,
                edge = edge,
                motion = motion,
                onExit = onExit,
                onUnlock = { devUnlocked = true },
                clockMillis = devUnlockClockMillis,
            )
        }
    }
}

private fun LazyListScope.hallAdaptiveSectionItems(
    sectionKey: String,
    items: List<HallEntry>,
    availableWidth: Dp,
    narrow: Boolean,
    compact: Boolean,
    favorites: List<String>,
    onFavorite: (HallEntry) -> Unit,
    onOpen: (HallEntry) -> Unit,
    motion: GameplayHallMotionStates,
) {
    if (items.isEmpty()) return
    // 单个条目在非窄屏下升级为全宽横版精品展卡，避免 1/3 卡片配大片空荡。
    if (items.size == 1 && !narrow) {
        val single = items.first()
        item(key = "$sectionKey-spotlight-${single.id}") {
            HallSpotlightCard(
                entry = single,
                favorite = single.id in favorites,
                onFavorite = { onFavorite(single) },
                onOpen = { onOpen(single) },
                compact = compact,
                modifier = Modifier.fillMaxWidth().hallFeedMotion(motion),
            )
        }
        return
    }
    val columns = adaptiveHallColumns(items.size, availableWidth, narrow)
    items(items.chunked(columns), key = { row -> "$sectionKey-" + row.joinToString("-") { it.id } }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .hallFeedMotion(motion),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
        ) {
            row.forEach { entry ->
                HallGameCard(
                    entry = entry,
                    favorite = entry.id in favorites,
                    onFavorite = { onFavorite(entry) },
                    onOpen = { onOpen(entry) },
                    compact = compact,
                    wideAspect = columns == 2 && !narrow,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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
private fun HallDevStatusPill(
    expanded: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val scale = animateUiFloatAsState(
        targetValue = if (animated && pressed) 0.95f else 1f,
        animationSpec = tween(if (animated) 110 else 0, easing = StandardDecelerateEasing),
        label = "HallDevPillScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = when {
            pressed -> HallAmber.copy(alpha = 0.24f)
            hovered -> HallAmber.copy(alpha = 0.18f)
            expanded -> HallAmber.copy(alpha = 0.11f)
            else -> HallAmber.copy(alpha = 0.15f)
        },
        animationSpec = tween(if (animated) 150 else 0),
        label = "HallDevPillBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            hovered || pressed -> HallAmber.copy(alpha = 0.68f)
            expanded -> HallAmber.copy(alpha = 0.30f)
            else -> HallAmber.copy(alpha = 0.45f)
        },
        animationSpec = tween(if (animated) 150 else 0),
        label = "HallDevPillBorder",
    )
    val description = hallText(if (expanded) "devDismiss" else "devExpand")
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = scale.value
                        scaleX = value
                        scaleY = value
                    }
                } else it
            }
            .semantics { contentDescription = description }
            .testTag("hall-dev-badge"),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 9.dp,
                vertical = if (compact) 2.5.dp else 3.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(7.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).background(HallAmber.copy(alpha = 0.28f), CircleShape))
                Box(Modifier.size(3.5.dp).background(HallAmber, CircleShape))
            }
            Text(
                text = hallText("devBadge"),
                color = HallAmber,
                fontSize = if (compact) 10.sp else 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun HallUnderDevelopmentOverlay(
    active: Boolean,
    compact: Boolean,
    narrow: Boolean,
    edge: Dp,
    motion: GameplayHallMotionStates,
    onExit: () -> Unit,
    onUnlock: () -> Unit,
    clockMillis: () -> Long,
) {
    val lockFocusRequester = remember { FocusRequester() }
    val backdropInteraction = remember { MutableInteractionSource() }
    val unlockTracker = remember(clockMillis) { HallDevUnlockTracker(nowMillis = clockMillis) }
    val latestOnUnlock by rememberUpdatedState(onUnlock)
    val latestTracker by rememberUpdatedState(unlockTracker)

    LaunchedEffect(active) {
        unlockTracker.reset()
        if (active) {
            runCatching { lockFocusRequester.requestFocus() }
        }
    }

    val qFallbackKeyCode = remember {
        val qCode = Key.Q.keyCode.toInt()
        val aCode = Key.A.keyCode.toInt()
        if (qCode != 0 && qCode != aCode) qCode else null
    }
    val modifierFallbackKeyCodes = remember(qFallbackKeyCode) {
        if (qFallbackKeyCode == null) {
            emptySet()
        } else {
            setOf(
                Key.ShiftLeft.keyCode.toInt(),
                Key.ShiftRight.keyCode.toInt(),
                Key.CtrlLeft.keyCode.toInt(),
                Key.CtrlRight.keyCode.toInt(),
                Key.AltLeft.keyCode.toInt(),
                Key.AltRight.keyCode.toInt(),
                Key.MetaLeft.keyCode.toInt(),
                Key.MetaRight.keyCode.toInt(),
                Key.CapsLock.keyCode.toInt(),
                Key.NumLock.keyCode.toInt(),
                Key.ScrollLock.keyCode.toInt(),
            )
        }
    }
    DisposableEffect(active, qFallbackKeyCode) {
        if (!active || qFallbackKeyCode == null) {
            onDispose { }
        } else {
            val channel = GlobalEventChannel.filter(KeyboardEvent::class)
            val listener = channel.subscribeAlways(Dispatchers.Main.immediate) { event ->
                if (event.keyCode == qFallbackKeyCode) {
                    event.intercept()
                    if (latestTracker.onFallbackQPress()) {
                        latestOnUnlock()
                    }
                } else if (event.keyCode !in modifierFallbackKeyCodes) {
                    latestTracker.onOtherKeyDown()
                }
            }
            onDispose { channel.unregisterListener(listener) }
        }
    }

    val frostGrainBrush = remember {
        ShaderBrush(ImageShader(cachedHallFrostGrainBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    val cardShape = RoundedCornerShape(if (compact) 20.dp else 26.dp)
    val cardBorder = remember {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.32f),
                HallAmber.copy(alpha = 0.36f),
                HallAccent.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.12f),
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(lockFocusRequester)
            .onFocusChanged { state ->
                if (!state.hasFocus) unlockTracker.onFocusLost()
            }
            .onPreviewKeyEvent { event ->
                if (!active) return@onPreviewKeyEvent false
                val key = event.key
                if (key == Key.Q) {
                    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                        if (event.type == KeyEventType.KeyDown) unlockTracker.onOtherKeyDown()
                        return@onPreviewKeyEvent false
                    }
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (unlockTracker.onQKeyDown()) {
                                latestOnUnlock()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            unlockTracker.onQKeyUp()
                            true
                        }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyDown && !isHallModifierKey(key)) {
                    unlockTracker.onOtherKeyDown()
                    false
                } else {
                    false
                }
            }
            .focusable(enabled = active)
            .testTag("hall-dev-overlay"),
    ) {
        // 第 1 层：强高斯模糊的抽象氛围光场（不含任何真实大厅内容，确保 Android 8-11 无 RenderEffect 也不泄露底层界面）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .blur(radius = 48.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .drawBehind {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(0xE80C151B), Color(0xEC091116), Color(0xEA0C141A)),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    // 抽象柔光色斑：经大半径模糊后呈现真实厚毛玻璃的色散透光感
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(HallAccent.copy(alpha = 0.34f), Color(0x1A1A5E42), Color.Transparent),
                            center = Offset(size.width * 0.16f, size.height * 0.18f),
                            radius = (size.maxDimension * 0.44f).coerceAtLeast(180.dp.toPx()),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(HallAmber.copy(alpha = 0.28f), Color(0x16594218), Color.Transparent),
                            center = Offset(size.width * 0.56f, size.height * 0.42f),
                            radius = (size.maxDimension * 0.40f).coerceAtLeast(160.dp.toPx()),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(HallCyan.copy(alpha = 0.28f), Color(0x16164961), Color.Transparent),
                            center = Offset(size.width * 0.84f, size.height * 0.82f),
                            radius = (size.maxDimension * 0.46f).coerceAtLeast(180.dp.toPx()),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x28DCEEE8), Color(0x0E9BB5AE), Color.Transparent),
                            center = Offset(size.width * 0.28f, size.height * 0.80f),
                            radius = (size.maxDimension * 0.36f).coerceAtLeast(140.dp.toPx()),
                        ),
                    )
                    // 纯抽象柔边散射光晕（使用径向渐变自然衰减，确保 Android 8-11 无 RenderEffect 时同样柔和无生硬边缘）
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.03f), Color.Transparent),
                            center = Offset(size.width * 0.24f, size.height * 0.34f),
                            radius = (size.maxDimension * 0.34f).coerceAtLeast(130.dp.toPx()),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(HallAccent.copy(alpha = 0.16f), HallCyan.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.74f, size.height * 0.64f),
                            radius = (size.maxDimension * 0.38f).coerceAtLeast(150.dp.toPx()),
                        ),
                    )
                },
        )

        // 第 2 层：全屏磨砂玻璃介质（半透明冰雾面 + 斜向玻璃反光 + 细微磨砂颗粒 + 四周柔暗晕）
        // 作为底层独立兄弟节点承接空白点击，既拦截穿透并维持焦点，又不把前景文案合并为单个可点击节点。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (active) {
                        Modifier.clickable(
                            interactionSource = backdropInteraction,
                            indication = null,
                            onClick = { runCatching { lockFocusRequester.requestFocus() } },
                        )
                    } else {
                        Modifier
                    },
                )
                .drawBehind {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0x6E17252D),
                                Color(0x62111C23),
                                Color(0x720E171D),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color(0x14E3F2EE),
                                Color.White.copy(alpha = 0.03f),
                                Color.White.copy(alpha = 0.08f),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width * 0.92f, size.height),
                        ),
                    )
                    drawRect(brush = frostGrainBrush)
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color(0x4604080B)),
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            radius = (size.maxDimension * 0.72f).coerceAtLeast(200.dp.toPx()),
                        ),
                    )
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.20f), Color.Transparent),
                        ),
                        start = Offset(0f, 0.75.dp.toPx()),
                        end = Offset(size.width, 0.75.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        )

        // 中央悬浮毛玻璃提示卡片（无关闭入口、无开发者触发提示）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (narrow) 18.dp else 32.dp,
                    end = if (narrow) 18.dp else 32.dp,
                    top = if (compact) 54.dp else 72.dp,
                    bottom = if (compact) 20.dp else 44.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .hallBodyMotion(motion)
                    .widthIn(max = if (compact) 440.dp else 500.dp)
                    .fillMaxWidth()
                    .clip(cardShape)
                    .drawBehind {
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xC2192831),
                                    Color(0xB2121D24),
                                    Color(0xBE0F1920),
                                ),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                        )
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    Color.White.copy(alpha = 0.045f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.06f),
                                ),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height),
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(HallAmber.copy(alpha = 0.18f), Color.Transparent),
                                center = Offset(size.width * 0.5f, size.height * 0.16f),
                                radius = (size.maxDimension * 0.56f).coerceAtLeast(160.dp.toPx()),
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(HallAccent.copy(alpha = 0.11f), Color.Transparent),
                                center = Offset(size.width * 0.86f, size.height * 0.92f),
                                radius = (size.maxDimension * 0.42f).coerceAtLeast(120.dp.toPx()),
                            ),
                        )
                        drawRect(brush = frostGrainBrush, alpha = 0.72f)
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.38f),
                                    HallAmber.copy(alpha = 0.46f),
                                    Color.White.copy(alpha = 0.24f),
                                    Color.Transparent,
                                ),
                            ),
                            start = Offset(24.dp.toPx(), 0.75.dp.toPx()),
                            end = Offset(size.width - 24.dp.toPx(), 0.75.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .border(1.dp, cardBorder, cardShape)
                    .testTag("hall-dev-notice"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = when {
                                narrow -> 24.dp
                                compact -> 28.dp
                                else -> 38.dp
                            },
                            vertical = if (compact) 22.dp else 34.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 15.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(if (compact) 68.dp else 86.dp)
                                .drawBehind {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            listOf(HallAmber.copy(alpha = 0.24f), HallAccent.copy(alpha = 0.08f), Color.Transparent),
                                        ),
                                    )
                                },
                        )
                        HallDevSparkEmblem(size = if (compact) 44.dp else 54.dp)
                    }

                    Row(
                        modifier = Modifier
                            .background(HallAmber.copy(alpha = 0.14f), CircleShape)
                            .border(1.dp, HallAmber.copy(alpha = 0.34f), CircleShape)
                            .padding(horizontal = 11.dp, vertical = 4.dp)
                            .testTag("hall-dev-badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(7.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(7.dp).background(HallAmber.copy(alpha = 0.30f), CircleShape))
                            Box(Modifier.size(3.5.dp).background(HallAmber, CircleShape))
                        }
                        Text(
                            text = hallText("devBadge"),
                            color = HallAmberSoft,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    Text(
                        text = hallText("devTitle"),
                        color = HallInkPrimary,
                        fontSize = when {
                            compact -> 19.sp
                            narrow -> 21.sp
                            else -> 24.sp
                        },
                        lineHeight = when {
                            compact -> 25.sp
                            narrow -> 28.sp
                            else -> 32.sp
                        },
                        letterSpacing = (-0.2).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = hallText("devSubtitle"),
                        color = HallInkSecondary,
                        fontSize = if (compact) 12.5.sp else 13.5.sp,
                        lineHeight = if (compact) 19.sp else 21.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )

                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .padding(top = if (compact) 2.dp else 4.dp)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, HallHairlineStrong, Color.Transparent),
                                ),
                            ),
                    )

                    HallFrostedReturnButton(
                        text = hallText("back"),
                        compact = compact,
                        onClick = onExit,
                    )
                }
            }
        }

        // 顶部返回按钮置于滚动容器之上，保持左上固定命中区不被遮挡。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .hallHeaderMotion(motion)
                .padding(horizontal = edge, vertical = if (compact) 8.dp else 14.dp),
        ) {
            HallIconButton(onExit, "back", compact = compact) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = hallText("back"),
                    tint = HallInkPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun HallFrostedReturnButton(
    text: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val scale = animateUiFloatAsState(
        targetValue = if (animated && pressed) 0.96f else 1f,
        animationSpec = tween(if (animated) 110 else 0, easing = StandardDecelerateEasing),
        label = "HallFrostedReturnScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = when {
            pressed -> Color.White.copy(alpha = 0.22f)
            hovered -> Color.White.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        animationSpec = tween(if (animated) 140 else 0),
        label = "HallFrostedReturnBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            hovered || pressed -> Color.White.copy(alpha = 0.42f)
            else -> Color.White.copy(alpha = 0.22f)
        },
        animationSpec = tween(if (animated) 140 else 0),
        label = "HallFrostedReturnBorder",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = scale.value
                        scaleX = value
                        scaleY = value
                    }
                } else it
            }
            .heightIn(min = if (compact) 38.dp else 44.dp)
            .testTag("hall-dev-back"),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 18.dp else 22.dp,
                vertical = if (compact) 8.dp else 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = HallInkPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                color = HallInkPrimary,
                fontSize = if (compact) 13.sp else 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun HallDevSparkEmblem(size: Dp) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(HallAmber.copy(alpha = 0.22f), HallAccent.copy(alpha = 0.12f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(HallAmber.copy(alpha = 0.56f), HallAccent.copy(alpha = 0.26f)),
                ),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w * 0.45f
            val cy = h * 0.54f
            val r = minOf(w, h) * 0.38f
            drawArc(
                color = HallAmber.copy(alpha = 0.42f),
                startAngle = 140f,
                sweepAngle = 215f,
                useCenter = false,
                topLeft = Offset(cx - r * 1.18f, cy - r * 1.18f),
                size = Size(r * 2.36f, r * 2.36f),
                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
            )
            val star = Path().apply {
                moveTo(cx, cy - r)
                quadraticTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
                quadraticTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
                quadraticTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
                quadraticTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
                close()
            }
            drawPath(
                path = star,
                brush = Brush.radialGradient(
                    colors = listOf(HallAmberSoft, HallAmber),
                    center = Offset(cx, cy),
                    radius = r,
                ),
            )
            val sx = w * 0.78f
            val sy = h * 0.24f
            val sr = minOf(w, h) * 0.17f
            val miniStar = Path().apply {
                moveTo(sx, sy - sr)
                quadraticTo(sx + sr * 0.22f, sy - sr * 0.22f, sx + sr, sy)
                quadraticTo(sx + sr * 0.22f, sy + sr * 0.22f, sx, sy + sr)
                quadraticTo(sx - sr * 0.22f, sy + sr * 0.22f, sx - sr, sy)
                quadraticTo(sx - sr * 0.22f, sy - sr * 0.22f, sx, sy - sr)
                close()
            }
            drawPath(path = miniStar, color = HallAccent)
        }
    }
}

@Composable
private fun HallIconButton(
    onClick: () -> Unit,
    id: String,
    active: Boolean = false,
    compact: Boolean = false,
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
            active -> HallAmber.copy(alpha = 0.16f)
            pressed -> Color(0x24FFFFFF)
            hovered -> Color(0x16FFFFFF)
            else -> HallSurfaceLow
        },
        animationSpec = tween(if (animated) 150 else 0),
        label = "HallIconBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            active -> HallAmber.copy(alpha = 0.50f)
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
            .testTag("hall-$id"),
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
private fun HallNavRailItem(
    category: HallCategory,
    selected: Boolean,
    count: Int?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val scale = animateUiFloatAsState(
        targetValue = if (animated && pressed) 0.985f else 1f,
        animationSpec = tween(if (animated) 110 else 0, easing = StandardDecelerateEasing),
        label = "HallNavScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = when {
            selected -> HallAccent.copy(alpha = 0.14f)
            pressed -> Color(0x16FFFFFF)
            hovered -> Color(0x0DFFFFFF)
            else -> Color.Transparent
        },
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallNavBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            selected -> HallAccent.copy(alpha = 0.30f)
            hovered -> HallHairlineSoft
            else -> Color.Transparent
        },
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallNavBorder",
    )
    val contentColor by animateUiColorAsState(
        targetValue = when {
            selected -> HallInkPrimary
            hovered -> HallInkPrimary.copy(alpha = 0.92f)
            else -> HallInkSecondary
        },
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallNavText",
    )
    val iconColor by animateUiColorAsState(
        targetValue = when {
            selected -> HallAccentBright
            hovered -> HallInkPrimary
            else -> HallInkTertiary
        },
        animationSpec = tween(if (animated) 160 else 0),
        label = "HallNavIcon",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = HallControlShape,
        color = bgColor,
        border = if (borderColor == Color.Transparent) null else BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = scale.value
                        scaleX = value
                        scaleY = value
                    }
                } else it
            }
            .testTag("hall-category-${category.key}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 40.dp else 44.dp)
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (selected) 18.dp else 0.dp)
                    .background(if (selected) HallAccent else Color.Transparent, CircleShape),
            )
            HallCategoryIcon(category, selected, iconColor, Modifier.size(19.dp))
            Text(
                text = hallText("categories.${category.key}"),
                color = contentColor,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    color = if (selected) HallAccentBright else HallInkTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(
                            if (selected) HallAccent.copy(alpha = 0.18f) else Color(0x10FFFFFF),
                            CircleShape,
                        )
                        .padding(horizontal = 7.dp, vertical = 1.5.dp),
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
        HallCategory.Recommended -> Icon(Icons.Default.Star, null, modifier, tint = tint)
        HallCategory.All -> Icon(painterResource(Res.drawable.dns_30), null, modifier, tint = tint)
        HallCategory.Favorites -> HallStar(selected, null, tint, modifier)
    }
}

@Composable
private fun HallDirectoryStatusStrip(
    loading: Boolean,
    loadFailed: Boolean,
    hasOnlineGames: Boolean,
    narrow: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusDotColor = when {
        loadFailed -> HallCoral
        loading -> HallAmber
        hasOnlineGames -> HallAccent
        else -> HallInkTertiary
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).background(statusDotColor, CircleShape))
        Text(
            text = hallText(
                when {
                    loading -> "directoryLoading"
                    loadFailed -> "directoryFailed"
                    !hasOnlineGames -> "directoryEmpty"
                    else -> "directoryReady"
                },
            ),
            color = if (loadFailed) HallCoral.copy(alpha = 0.90f) else HallInkTertiary,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            maxLines = if (narrow) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("hall-directory-status"),
        )
        HallRefreshButton(onClick = onRefresh)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HallSectionControlBar(
    title: String,
    count: Int,
    availableOnly: Boolean,
    hasFilters: Boolean,
    onSelectAvailable: (Boolean) -> Unit,
    onResetFilters: () -> Unit,
    loading: Boolean,
    loadFailed: Boolean,
    hasOnlineGames: Boolean,
    onRefresh: () -> Unit,
    narrow: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(17.dp)
                        .background(
                            Brush.verticalGradient(listOf(HallAccentBright, HallAccent)),
                            CircleShape,
                        ),
                )
                Text(
                    text = title,
                    color = HallInkPrimary,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                )
                Text(
                    text = "$count " + hallText("entries"),
                    color = HallInkSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .background(Color(0x12FFFFFF), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                HallFilterPill(
                    label = hallText("status"),
                    value = hallText(if (availableOnly) "available" else "any"),
                    id = "status",
                    active = availableOnly,
                    options = listOf(false, true),
                    title = { hallText(if (it) "available" else "any") },
                    onSelect = onSelectAvailable,
                )
                if (hasFilters) {
                    Surface(
                        onClick = onResetFilters,
                        shape = CircleShape,
                        color = HallAccent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, HallAccent.copy(alpha = 0.34f)),
                        modifier = Modifier.testTag("hall-reset"),
                    ) {
                        Text(
                            text = hallText("reset"),
                            color = HallAccentBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        HallDirectoryStatusStrip(
            loading = loading,
            loadFailed = loadFailed,
            hasOnlineGames = hasOnlineGames,
            narrow = narrow,
            onRefresh = onRefresh,
        )
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

/**
 * 顶部主视觉舞台：
 * - 单网游首发态：升级为沉浸式大画幅「旗舰首发主舞台」，完整释放封面美术，并直接承载卡片语义与收藏入口。
 * - 多网游态：紧凑影院轮播舞台 + 高可读切换胶囊，与下方自适应画廊自然衔接。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HallFeaturedStage(
    entries: List<HallEntry>,
    index: Int,
    singleOnlinePremiere: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    compact: Boolean,
    mediumHeight: Boolean,
    narrow: Boolean,
    onSelect: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    val entry = entries[index]
    val animated = uiAnimationsEnabled()
    val interactive = LocalDialogInteractive.current
    val stageInteraction = remember { MutableInteractionSource() }
    val stageHovered by stageInteraction.collectIsHoveredAsState()
    val openInteraction = remember { MutableInteractionSource() }
    val openHovered by openInteraction.collectIsHoveredAsState()
    val openPressed by openInteraction.collectIsPressedAsState()

    val openScale = animateUiFloatAsState(
        targetValue = if (animated && openPressed && entry.canOpen) 0.965f else 1f,
        animationSpec = tween(if (animated) 110 else 0, easing = StandardDecelerateEasing),
        label = "HallFeaturedOpenScale",
    )
    val coverZoom = animateUiFloatAsState(
        targetValue = if (animated && interactive && (stageHovered || openHovered) && entry.canOpen) 1.028f else 1f,
        animationSpec = tween(if (animated) 260 else 0, easing = StandardDecelerateEasing),
        label = "HallFeaturedCoverZoom",
    )
    val arrowShift = animateUiFloatAsState(
        targetValue = if (animated && interactive && openHovered && entry.canOpen) 1f else 0f,
        animationSpec = tween(if (animated) 180 else 0, easing = StandardDecelerateEasing),
        label = "HallFeaturedArrowShift",
    )

    val heroHeight = when {
        narrow && singleOnlinePremiere -> 320.dp
        narrow -> 244.dp
        compact && singleOnlinePremiere -> 208.dp
        compact -> 176.dp
        singleOnlinePremiere && mediumHeight -> 296.dp
        singleOnlinePremiere -> 352.dp
        else -> 192.dp
    }

    val stageBorderBrush = remember(singleOnlinePremiere) {
        Brush.verticalGradient(
            listOf(
                if (singleOnlinePremiere) HallAccent.copy(alpha = 0.44f) else Color(0x30FFFFFF),
                Color(0x18FFFFFF),
                Color(0x0BFFFFFF),
            ),
        )
    }

    val stageContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = heroHeight)
                .clip(HallHeroShape)
                .border(1.dp, stageBorderBrush, HallHeroShape),
        ) {
            AnimatedContent(
                targetState = index,
                modifier = Modifier.matchParentSize(),
                transitionSpec = {
                    if (!animated) {
                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                    } else {
                        (fadeIn(tween(280, easing = StandardDecelerateEasing)) togetherWith fadeOut(tween(220))).using(null)
                    }
                },
                label = "HallFeaturedCover",
            ) { targetIndex ->
                val targetEntry = entries.getOrNull(targetIndex) ?: entry
                HallCover(
                    entry = targetEntry,
                    modifier = Modifier
                        .fillMaxSize()
                        .let {
                            if (animated) {
                                it.graphicsLayer {
                                    val z = coverZoom.value
                                    scaleX = z
                                    scaleY = z
                                }
                            } else it
                        },
                )
            }

            // 定向暗面渐变：左侧清晰承载文案，右侧完整透出原画细节。
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(
                                0.00f to Color(0xF6090F13),
                                0.34f to Color(0xDE0A1116),
                                0.60f to Color(0x660A1116),
                                0.85f to Color(0x100A1116),
                                1.00f to Color(0x34080D10),
                            ),
                        )
                        drawRect(
                            Brush.verticalGradient(
                                0.00f to Color(0x26080D10),
                                0.48f to Color.Transparent,
                                1.00f to Color(0xDC080D10),
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(HallAccent.copy(alpha = if (singleOnlinePremiere) 0.18f else 0.11f), Color.Transparent),
                                center = Offset(size.width * 0.08f, size.height * 0.14f),
                                radius = (size.width * 0.46f).coerceAtLeast(140.dp.toPx()),
                            ),
                        )
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    if (singleOnlinePremiere) HallAccentBright.copy(alpha = 0.54f) else Color(0x36FFFFFF),
                                    Color(0x18FFFFFF),
                                    Color.Transparent,
                                ),
                            ),
                            start = Offset(22.dp.toPx(), 1.dp.toPx()),
                            end = Offset(size.width - 22.dp.toPx(), 1.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            )

            if (singleOnlinePremiere) {
                HallCardFavoriteButton(
                    favorite = isFavorite,
                    entryId = entry.id,
                    onClick = onToggleFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(if (compact) 12.dp else 18.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = heroHeight)
                    .padding(
                        horizontal = if (compact || narrow) 16.dp else if (singleOnlinePremiere) 28.dp else 24.dp,
                        vertical = if (compact) 14.dp else if (singleOnlinePremiere) 24.dp else 17.dp,
                    ),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶部眉标与在线态势
                val currentTelemetry = entry.game.telemetry()
                val currentTelemetryText = HallTelemetryLabel(currentTelemetry)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(if (singleOnlinePremiere) 0.86f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    HallEyebrowBadge(
                        text = hallText(if (singleOnlinePremiere) "premiereBadge" else "onlineSection"),
                        accent = if (singleOnlinePremiere) HallAccentBright else HallInkPrimary,
                        highlight = singleOnlinePremiere,
                    )
                    if (entry.canOpen && entry.available && currentTelemetryText != null) {
                        HallLiveTelemetryBadge(text = currentTelemetryText)
                    } else {
                        HallStatusGlassBadge(
                            canOpen = entry.canOpen,
                            available = entry.available,
                        )
                    }
                }

                // 中段主标题与玩法简介
                AnimatedContent(
                    targetState = index,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (singleOnlinePremiere && !compact) 14.dp else 6.dp),
                    transitionSpec = {
                        if (!animated) {
                            (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        } else {
                            val forward = targetState >= initialState
                            val enterSlide = if (forward) 22 else -22
                            val exitSlide = if (forward) -14 else 14
                            (
                                fadeIn(tween(220, delayMillis = 35, easing = StandardDecelerateEasing)) +
                                    slideInHorizontally(tween(300, easing = EmphasizedDecelerateEasing)) { enterSlide }
                                togetherWith
                                fadeOut(tween(130, easing = EmphasizedAccelerateEasing)) +
                                    slideOutHorizontally(tween(160, easing = EmphasizedAccelerateEasing)) { exitSlide }
                            ).using(null)
                        }
                    },
                    label = "HallFeaturedContent",
                ) { targetIndex ->
                    val targetEntry = entries.getOrNull(targetIndex) ?: entry
                    val targetTelemetry = targetEntry.game.telemetry()
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else if (singleOnlinePremiere) 10.dp else 7.dp)) {
                        Text(
                            text = targetEntry.game.name,
                            color = HallInkPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = when {
                                compact -> 26.sp
                                singleOnlinePremiere && !narrow -> 38.sp
                                narrow -> 30.sp
                                else -> 30.sp
                            },
                            lineHeight = when {
                                compact -> 31.sp
                                singleOnlinePremiere && !narrow -> 44.sp
                                narrow -> 36.sp
                                else -> 35.sp
                            },
                            letterSpacing = (-0.6).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.58f),
                        )
                        if (targetEntry.game.description.isNotBlank()) {
                            Text(
                                text = targetEntry.game.description,
                                color = HallInkSecondary,
                                fontSize = if (singleOnlinePremiere && !compact) 14.sp else 13.sp,
                                lineHeight = if (singleOnlinePremiere && !compact) 21.sp else 18.5.sp,
                                maxLines = if (singleOnlinePremiere && !compact) 3 else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.54f),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            HallMetaGlassChip(text = hallText("categories.extended"), accent = HallAccentBright)
                            if (targetTelemetry.hasExpansion) {
                                HallMetaGlassChip(text = hallText("expansionTag"), accent = HallAmber)
                            } else if (targetTelemetry.hasArtPack) {
                                HallMetaGlassChip(text = hallText("artPackTag"), accent = HallCyan)
                            }
                        }
                    }
                }

                // 底部切换条与主行动按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (entries.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (narrow || compact) 2.dp else 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            entries.forEachIndexed { dot, item ->
                                val activeDot = index == dot
                                val itemTitle = item.game.name
                                if (!narrow && !compact && entries.size <= 4) {
                                    HallHeroSwitcherTab(
                                        index = dot,
                                        title = itemTitle,
                                        active = activeDot,
                                        onClick = { onSelect(dot) },
                                    )
                                } else {
                                    val dotWidth by animateUiDpAsState(
                                        targetValue = if (activeDot) 24.dp else 7.dp,
                                        animationSpec = tween(if (animated) 220 else 0, easing = EmphasizedDecelerateEasing),
                                        label = "HallFeaturedDotWidth",
                                    )
                                    val dotColor by animateUiColorAsState(
                                        targetValue = if (activeDot) HallAccent else Color(0x55FFFFFF),
                                        animationSpec = tween(if (animated) 180 else 0),
                                        label = "HallFeaturedDotColor",
                                    )
                                    Surface(
                                        onClick = { onSelect(dot) },
                                        color = Color.Transparent,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("hall-featured-$dot")
                                            .semantics { contentDescription = itemTitle },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Box(
                                                Modifier
                                                    .width(dotWidth)
                                                    .height(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 单旗舰态左下角显示平台认证微标，平衡右下主按钮视觉重量。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Icon(
                                painterResource(Res.drawable.shield_30),
                                contentDescription = null,
                                tint = HallAccentBright.copy(alpha = 0.85f),
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = hallText("intelPlatformVerified"),
                                color = HallInkSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    val ctaBrush = remember(entry.canOpen, openHovered, openPressed) {
                        when {
                            !entry.canOpen -> SolidColor(Color(0xAA253238))
                            openPressed -> Brush.horizontalGradient(listOf(Color(0xFF2FC475), Color(0xFF22A661)))
                            openHovered -> Brush.horizontalGradient(listOf(Color(0xFF5CF3A3), Color(0xFF37DC85)))
                            else -> Brush.horizontalGradient(listOf(Color(0xFF47EB95), Color(0xFF28C673)))
                        }
                    }
                    Surface(
                        onClick = onOpen,
                        enabled = entry.canOpen,
                        interactionSource = openInteraction,
                        color = Color.Transparent,
                        shape = CircleShape,
                        border = BorderStroke(
                            1.dp,
                            if (entry.canOpen) Color(0x55FFFFFF) else HallHairline,
                        ),
                        modifier = Modifier
                            .let {
                                if (animated) {
                                    it.graphicsLayer {
                                        val value = openScale.value
                                        scaleX = value
                                        scaleY = value
                                    }
                                } else it
                            }
                            .background(ctaBrush, CircleShape)
                            .testTag("hall-featured-open"),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = if (compact) 40.dp else if (singleOnlinePremiere) 48.dp else 44.dp)
                                .padding(horizontal = if (compact) 18.dp else if (singleOnlinePremiere) 26.dp else 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(
                                text = if (entry.canOpen) hallText("enter") else readI18n("gamePortal.maintenance"),
                                color = if (entry.canOpen) HallAccentDeep else HallInkSecondary,
                                fontSize = if (compact) 13.5.sp else if (singleOnlinePremiere) 15.sp else 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .let {
                                        if (animated) it.graphicsLayer { translationX = arrowShift.value * 3.dp.toPx() } else it
                                    },
                                tint = if (entry.canOpen) HallAccentDeep else HallInkTertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().testTag("hall-featured")) {
        if (singleOnlinePremiere) {
            Surface(
                onClick = onOpen,
                enabled = entry.canOpen,
                interactionSource = stageInteraction,
                color = Color.Transparent,
                shape = HallHeroShape,
                modifier = Modifier.fillMaxWidth().testTag("hall-card-${entry.id}"),
            ) {
                stageContent()
            }
        } else {
            stageContent()
        }
    }
}

/**
 * 单网游首发态三格 Bento 情报卡：全部基于真实目录数据呈现，不捏造任何演示玩法，
 * 让仅有 1 个网游的大厅在宽屏与移动端同样丰满、平衡、有高级感。
 */
@Composable
private fun HallSingleGameIntelDeck(
    entry: HallEntry,
    compact: Boolean,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val telemetry = entry.game.telemetry()
    val statusHeadline = when {
        !entry.canOpen -> readI18n("gamePortal.maintenance")
        telemetry.onlineServers == 1 -> hallText("serverOnlineSingle")
        telemetry.onlineServers > 1 -> hallText("serversOnline").replace("{0}", telemetry.onlineServers.toString())
        telemetry.totalServers > 0 -> hallText("intelServerOffline")
        else -> hallText("intelPortalReady")
    }
    val statusSubline = when {
        !entry.canOpen -> hallText("intelPortalMaintenanceHint")
        telemetry.onlineServers > 0 && telemetry.maxPlayers > 0 ->
            hallText("intelPlayersCapacity")
                .replace("{0}", telemetry.players.toString())
                .replace("{1}", telemetry.maxPlayers.toString())
        telemetry.onlineServers > 0 && telemetry.players > 0 ->
            hallText("playersOnline").replace("{0}", telemetry.players.toString())
        telemetry.onlineServers > 0 -> hallText("intelServerReady")
        telemetry.totalServers > 0 -> hallText("intelServerOfflineHint")
        else -> hallText("intelPortalReadyHint")
    }
    val statusAccent = if (entry.canOpen && (telemetry.onlineServers > 0 || telemetry.totalServers == 0)) HallAccent else HallAmber

    val contentHeadline = when {
        telemetry.hasExpansion -> hallText("expansionTag")
        telemetry.hasArtPack && telemetry.artVersion.isNotBlank() -> hallText("artPackTag") + " · v${telemetry.artVersion}"
        telemetry.hasArtPack -> hallText("artPackTag")
        else -> hallText("intelPlatformVerified")
    }
    val contentSubline = when {
        telemetry.hasExpansion -> hallText("intelExpansionDesc")
        telemetry.hasArtPack -> hallText("intelArtPackDesc")
        else -> hallText("intelPlatformVerifiedDesc")
    }
    val contentAccent = when {
        telemetry.hasExpansion -> HallAmber
        telemetry.hasArtPack -> HallCyan
        else -> HallAccentBright
    }

    if (narrow) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HallIntelBentoTile(
                eyebrow = hallText("intelStatusTitle"),
                headline = statusHeadline,
                subline = statusSubline,
                accent = statusAccent,
                iconRes = Res.drawable.public_30,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HallIntelBentoTile(
                eyebrow = hallText("intelContentTitle"),
                headline = contentHeadline,
                subline = contentSubline,
                accent = contentAccent,
                iconRes = Res.drawable.shield_30,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HallIntelBentoTile(
                eyebrow = hallText("intelEcosystemTitle"),
                headline = hallText("moreModesTitle"),
                subline = hallText("intelEcosystemDesc"),
                accent = HallInkSecondary,
                iconRes = Res.drawable.extension_30,
                compact = true,
                dashed = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        ) {
            HallIntelBentoTile(
                eyebrow = hallText("intelStatusTitle"),
                headline = statusHeadline,
                subline = statusSubline,
                accent = statusAccent,
                iconRes = Res.drawable.public_30,
                compact = compact,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            HallIntelBentoTile(
                eyebrow = hallText("intelContentTitle"),
                headline = contentHeadline,
                subline = contentSubline,
                accent = contentAccent,
                iconRes = Res.drawable.shield_30,
                compact = compact,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            HallIntelBentoTile(
                eyebrow = hallText("intelEcosystemTitle"),
                headline = hallText("moreModesTitle"),
                subline = hallText("intelEcosystemDesc"),
                accent = HallAccentBright,
                iconRes = Res.drawable.extension_30,
                compact = compact,
                dashed = true,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun HallIntelBentoTile(
    eyebrow: String,
    headline: String,
    subline: String,
    accent: Color,
    iconRes: org.jetbrains.compose.resources.DrawableResource,
    compact: Boolean,
    dashed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (dashed) Color(0x9E10171C) else HallSurfaceCard)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val half = strokeWidth / 2f
                val corner = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                if (dashed) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)
                    drawRoundRect(
                        color = HallHairline,
                        topLeft = Offset(half, half),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = corner,
                        style = Stroke(width = strokeWidth, pathEffect = dash),
                    )
                } else {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.14f, size.height * 0.12f),
                            radius = (size.width * 0.72f).coerceAtLeast(100.dp.toPx()),
                        ),
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x0AFFFFFF))),
                        topLeft = Offset(half, half),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = corner,
                        style = Stroke(width = strokeWidth),
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 13.dp else 16.dp,
                    vertical = if (compact) 10.dp else 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(accent.copy(alpha = 0.13f), RoundedCornerShape(7.dp))
                        .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(iconRes),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = eyebrow,
                    color = HallInkTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = headline,
                color = HallInkPrimary,
                fontSize = if (compact) 14.sp else 15.5.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subline,
                color = HallInkSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
            Surface(
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
                        text = hallText(if (loadFailed) "retry" else "refresh"),
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
private fun HallHeroSwitcherTab(
    index: Int,
    title: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val animated = uiAnimationsEnabled()
    val bgColor by animateUiColorAsState(
        targetValue = when {
            active -> Color(0xCC121E24)
            hovered -> Color(0x88101A20)
            else -> Color(0x580B1216)
        },
        animationSpec = tween(if (animated) 180 else 0),
        label = "HallSwitcherBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            active -> HallAccent.copy(alpha = 0.55f)
            hovered -> HallHairlineStrong
            else -> HallHairlineSoft
        },
        animationSpec = tween(if (animated) 180 else 0),
        label = "HallSwitcherBorder",
    )
    val barWidth by animateUiDpAsState(
        targetValue = if (active) 16.dp else 6.dp,
        animationSpec = tween(if (animated) 220 else 0, easing = EmphasizedDecelerateEasing),
        label = "HallSwitcherBar",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .heightIn(min = 34.dp)
            .testTag("hall-featured-$index")
            .semantics { contentDescription = title },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .width(barWidth)
                    .height(4.dp)
                    .background(if (active) HallAccent else Color(0x66FFFFFF), CircleShape),
            )
            Text(
                text = title,
                color = if (active) HallInkPrimary else HallInkSecondary,
                fontSize = 11.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}

/**
 * 全宽横版精品展卡：当「全部玩法」「我的收藏」或搜索筛选结果只有 1 个网游时自动启用，
 * 以左图右文的杂志级构图填满空间，彻底消除单个小卡的空旷感。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HallSpotlightCard(
    entry: HallEntry,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val interactive = LocalDialogInteractive.current

    val cardScale = animateUiFloatAsState(
        targetValue = when {
            !animated || !interactive -> 1f
            pressed && entry.canOpen -> 0.99f
            hovered && entry.canOpen -> 1.006f
            else -> 1f
        },
        animationSpec = tween(if (animated) 160 else 0, easing = StandardDecelerateEasing),
        label = "HallSpotlightScale",
    )
    val coverScale = animateUiFloatAsState(
        targetValue = if (animated && interactive && hovered && entry.canOpen) 1.038f else 1f,
        animationSpec = tween(if (animated) 240 else 0, easing = StandardDecelerateEasing),
        label = "HallSpotlightCoverScale",
    )
    val arrowOffset = animateUiFloatAsState(
        targetValue = if (animated && interactive && hovered && entry.canOpen) 1f else 0f,
        animationSpec = tween(if (animated) 180 else 0, easing = StandardDecelerateEasing),
        label = "HallSpotlightArrow",
    )
    val borderColor = animateUiColorAsState(
        targetValue = if (interactive && hovered && entry.canOpen) HallAccent.copy(alpha = 0.56f) else HallHairline,
        animationSpec = tween(if (animated) 180 else 0),
        label = "HallSpotlightBorder",
    )
    val telemetry = entry.game.telemetry()
    val cardHeight = if (compact) 172.dp else 218.dp

    Surface(
        onClick = onOpen,
        enabled = entry.canOpen,
        interactionSource = interactionSource,
        color = if (interactive && hovered && entry.canOpen) HallSurfaceCardHover else HallSurfaceCard,
        shape = HallCardShape,
        border = if (animated) null else BorderStroke(1.dp, HallHairline),
        modifier = modifier
            .height(cardHeight)
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = cardScale.value
                        scaleX = value
                        scaleY = value
                    }.drawWithContent {
                        drawContent()
                        val strokeWidth = 1.dp.toPx()
                        val half = strokeWidth / 2f
                        val radius = (18.dp.toPx() - half).coerceAtLeast(0f)
                        drawRoundRect(
                            color = borderColor.value,
                            topLeft = Offset(half, half),
                            size = Size(
                                (size.width - strokeWidth).coerceAtLeast(0f),
                                (size.height - strokeWidth).coerceAtLeast(0f),
                            ),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = strokeWidth),
                        )
                    }
                } else it
            }
            .testTag("hall-card-${entry.id}"),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)),
            ) {
                HallCover(
                    entry = entry,
                    modifier = Modifier
                        .matchParentSize()
                        .let {
                            if (animated) {
                                it.graphicsLayer {
                                    val z = coverScale.value
                                    scaleX = z
                                    scaleY = z
                                }
                            } else it
                        },
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1.0f to HallSurfaceCard.copy(alpha = 0.88f),
                            ),
                        ),
                )
                Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    HallStatusGlassBadge(canOpen = entry.canOpen, available = entry.available)
                }
            }

            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight()
                    .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 14.dp else 20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            HallMetaGlassChip(text = hallText("categories.extended"), accent = HallAccentBright)
                            if (telemetry.hasExpansion) {
                                HallMetaGlassChip(text = hallText("expansionTag"), accent = HallAmber)
                            } else if (telemetry.hasArtPack) {
                                HallMetaGlassChip(text = hallText("artPackTag"), accent = HallCyan)
                            }
                        }
                        HallCardFavoriteButton(
                            favorite = favorite,
                            entryId = entry.id,
                            onClick = onFavorite,
                        )
                    }

                    Text(
                        text = entry.game.name,
                        color = HallInkPrimary,
                        fontSize = if (compact) 20.sp else 24.sp,
                        lineHeight = if (compact) 24.sp else 29.sp,
                        letterSpacing = (-0.3).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (entry.game.description.isNotBlank()) {
                        Text(
                            text = entry.game.description,
                            color = HallInkSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val telemetryText = HallTelemetryLabel(telemetry)
                    if (telemetryText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Box(Modifier.size(6.dp).background(HallAccent, CircleShape))
                            Text(
                                text = telemetryText,
                                color = HallCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }

                    Row(
                        modifier = Modifier
                            .background(
                                if (entry.canOpen) HallAccent.copy(alpha = 0.16f) else Color(0x14FFFFFF),
                                CircleShape,
                            )
                            .border(
                                1.dp,
                                if (entry.canOpen) HallAccent.copy(alpha = 0.42f) else HallHairline,
                                CircleShape,
                            )
                            .padding(horizontal = 15.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (entry.canOpen) hallText("enter") else readI18n("gamePortal.maintenance"),
                            color = if (entry.canOpen) HallAccentBright else HallInkTertiary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier
                                .size(15.dp)
                                .let {
                                    if (animated) it.graphicsLayer { translationX = arrowOffset.value * 3.dp.toPx() } else it
                                },
                            tint = if (entry.canOpen) HallAccentBright else HallInkTertiary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HallGameCard(
    entry: HallEntry,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
    compact: Boolean,
    wideAspect: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val animated = uiAnimationsEnabled()
    val interactive = LocalDialogInteractive.current
    val cardScale = animateUiFloatAsState(
        targetValue = when {
            !animated || !interactive -> 1f
            pressed && entry.canOpen -> 0.984f
            hovered && entry.canOpen -> 1.010f
            else -> 1f
        },
        animationSpec = tween(if (animated) 160 else 0, easing = StandardDecelerateEasing),
        label = "HallCardScale",
    )
    val coverScale = animateUiFloatAsState(
        targetValue = if (animated && interactive && hovered && entry.canOpen) 1.042f else 1f,
        animationSpec = tween(if (animated) 240 else 0, easing = StandardDecelerateEasing),
        label = "HallCardCoverScale",
    )
    val arrowOffset = animateUiFloatAsState(
        targetValue = if (animated && interactive && hovered && entry.canOpen) 1f else 0f,
        animationSpec = tween(if (animated) 180 else 0, easing = StandardDecelerateEasing),
        label = "HallCardArrowOffset",
    )
    val borderColor = animateUiColorAsState(
        targetValue = if (interactive && hovered && entry.canOpen) HallAccent.copy(alpha = 0.56f) else HallHairline,
        animationSpec = tween(if (animated) 180 else 0),
        label = "HallCardBorder",
    )

    val telemetry = entry.game.telemetry()
    val coverHeight = when {
        compact -> 86.dp
        wideAspect -> 122.dp
        else -> 114.dp
    }

    Surface(
        onClick = onOpen,
        enabled = entry.canOpen,
        interactionSource = interactionSource,
        color = if (interactive && hovered && entry.canOpen) HallSurfaceCardHover else HallSurfaceCard,
        shape = HallCardShape,
        border = if (animated) null else BorderStroke(1.dp, HallHairline),
        modifier = modifier
            .let {
                if (animated) {
                    it.graphicsLayer {
                        val value = cardScale.value
                        scaleX = value
                        scaleY = value
                    }.drawWithContent {
                        drawContent()
                        val strokeWidth = 1.dp.toPx()
                        val halfStroke = strokeWidth / 2f
                        val radius = (18.dp.toPx() - halfStroke).coerceAtLeast(0f)
                        drawRoundRect(
                            color = borderColor.value,
                            topLeft = Offset(halfStroke, halfStroke),
                            size = Size(
                                (size.width - strokeWidth).coerceAtLeast(0f),
                                (size.height - strokeWidth).coerceAtLeast(0f),
                            ),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = strokeWidth),
                        )
                    }
                } else it
            }
            .testTag("hall-card-${entry.id}"),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            ) {
                HallCover(
                    entry = entry,
                    modifier = Modifier
                        .matchParentSize()
                        .let {
                            if (animated) {
                                it.graphicsLayer {
                                    val value = coverScale.value
                                    scaleX = value
                                    scaleY = value
                                }
                            } else it
                        },
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color(0x28080D10),
                                0.55f to Color.Transparent,
                                1.00f to HallSurfaceCard.copy(alpha = 0.92f),
                            ),
                        ),
                )
                Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    HallStatusGlassBadge(canOpen = entry.canOpen, available = entry.available)
                }
                HallCardFavoriteButton(
                    favorite = favorite,
                    entryId = entry.id,
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 15.dp, vertical = if (compact) 11.dp else 13.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                    Text(
                        text = entry.game.name,
                        color = HallInkPrimary,
                        fontSize = if (compact) 16.sp else 17.5.sp,
                        lineHeight = 22.sp,
                        letterSpacing = (-0.2).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        HallMetaGlassChip(text = hallText("categories.extended"), accent = HallAccentBright)
                        if (telemetry.hasExpansion) {
                            HallMetaGlassChip(text = hallText("expansionTag"), accent = HallAmber)
                        } else if (telemetry.hasArtPack) {
                            HallMetaGlassChip(text = hallText("artPackTag"), accent = HallCyan)
                        }
                        HallTelemetryLabel(telemetry)?.let { label ->
                            HallMetaGlassChip(text = label, accent = HallCyan)
                        }
                    }
                    Text(
                        text = entry.game.description,
                        color = HallInkSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(min = 35.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(HallHairlineSoft))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (entry.canOpen) hallText("enter") else readI18n("gamePortal.maintenance"),
                            color = if (entry.canOpen) HallAccentBright else HallInkTertiary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .let {
                                    if (animated) it.graphicsLayer { translationX = arrowOffset.value * 3.dp.toPx() } else it
                                },
                            tint = if (entry.canOpen) HallAccentBright else HallInkTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HallEcosystemFooterNote(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x9910171C))
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)
                drawRoundRect(
                    color = HallHairline,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = Stroke(width = strokeWidth, pathEffect = dash),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (compact) 11.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(HallAccent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, HallAccent.copy(alpha = 0.26f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.extension_30),
                    contentDescription = null,
                    tint = HallAccentBright,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = hallText("moreModesTitle"),
                    color = HallInkPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hallText("moreModesSubtitle"),
                    color = HallInkTertiary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun HallEyebrowBadge(
    text: String,
    accent: Color,
    highlight: Boolean,
) {
    Row(
        modifier = Modifier
            .background(
                if (highlight) HallAccent.copy(alpha = 0.18f) else HallGlassDark,
                CircleShape,
            )
            .border(
                1.dp,
                if (highlight) HallAccent.copy(alpha = 0.48f) else HallHairlineStrong,
                CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (highlight) {
            Icon(Icons.Default.Star, null, Modifier.size(11.dp), tint = HallAccentBright)
        }
        Text(
            text = text,
            color = accent,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun HallLiveTelemetryBadge(text: String) {
    Row(
        modifier = Modifier
            .background(HallGlassDark, CircleShape)
            .border(1.dp, HallAccent.copy(alpha = 0.36f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(HallAccent, CircleShape))
        Text(
            text = text,
            color = HallInkPrimary,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun HallStatusGlassBadge(
    canOpen: Boolean,
    available: Boolean,
) {
    val dotColor = if (canOpen && available) HallAccent else HallAmber
    val label = if (!canOpen) readI18n("gamePortal.maintenance") else hallText(if (available) "available" else "maintenance")
    Row(
        modifier = Modifier
            .background(HallGlassDark, CircleShape)
            .border(1.dp, dotColor.copy(alpha = 0.34f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Text(
            text = label,
            color = if (available && canOpen) HallInkPrimary else HallAmberSoft,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
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

private const val FROST_GRAIN_TILE_SIZE = 96
private val hallFrostGrainLock = Any()
private var hallFrostGrainBitmap: ImageBitmap? = null

/** 离屏缓存的可平铺细磨砂颗粒纹理，只生成一次，为全屏毛玻璃提供跨平台一致的微颗粒质感。 */
private fun cachedHallFrostGrainBitmap(): ImageBitmap {
    synchronized(hallFrostGrainLock) {
        hallFrostGrainBitmap?.let { return it }
    }
    val bitmap = ImageBitmap(FROST_GRAIN_TILE_SIZE, FROST_GRAIN_TILE_SIZE)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(bitmap),
        size = Size(FROST_GRAIN_TILE_SIZE.toFloat(), FROST_GRAIN_TILE_SIZE.toFloat()),
    ) {
        for (y in 0 until FROST_GRAIN_TILE_SIZE) {
            for (x in 0 until FROST_GRAIN_TILE_SIZE) {
                var h = (x * 374761393) xor (y * 668265263) xor 0x5BF03635
                h = (h xor (h ushr 13)) * 1274126177
                h = h xor (h ushr 16)
                val bucket = h and 0xFF
                when {
                    bucket < 56 -> {
                        val alpha = 0.022f + (bucket / 56f) * 0.036f
                        drawRect(
                            color = Color.White.copy(alpha = alpha),
                            topLeft = Offset(x.toFloat(), y.toFloat()),
                            size = Size(1f, 1f),
                        )
                    }
                    bucket > 210 -> {
                        val alpha = 0.018f + ((bucket - 210) / 45f) * 0.030f
                        drawRect(
                            color = Color.Black.copy(alpha = alpha),
                            topLeft = Offset(x.toFloat(), y.toFloat()),
                            size = Size(1f, 1f),
                        )
                    }
                }
            }
        }
    }
    synchronized(hallFrostGrainLock) {
        hallFrostGrainBitmap?.let { return it }
        hallFrostGrainBitmap = bitmap
        return bitmap
    }
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
    cachedHallFrostGrainBitmap()
    for (scene in 0..2) cachedHallBattlefieldBitmap(scene)
}

/** 静态战术沙盘兜底插画：当网游未提供封面图或图片加载中时显示，离屏位图缓存、零逐帧重绘开销。 */
@Composable
private fun HallBattlefield(scene: Int, modifier: Modifier) {
    val bitmap = remember(scene) { cachedHallBattlefieldBitmap(scene) }
    Canvas(modifier) {
        drawImage(
            image = bitmap,
            dstSize = IntSize(
                size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
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
