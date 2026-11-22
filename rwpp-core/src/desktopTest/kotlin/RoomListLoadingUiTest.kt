/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.widget.UiMotionTheme
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import io.github.rwpp.widget.setUiAnimationsEnabled
import io.github.rwpp.widget.themes
import io.github.rwpp.widget.uiAnimationsEnabled
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class RoomListLoadingUiTest {
    private lateinit var settings: Settings
    private var desktop = true

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        desktop = true
        appKoin = startKoin {
            modules(module {
                single { settings }
                single<AppContext> {
                    Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, _ ->
                        when (method.name) {
                            "isDesktop" -> desktop
                            "isAndroid" -> !desktop
                            "getKoin" -> appKoin
                            else -> error("Unexpected AppContext access: ${method.name}")
                        }
                    } as AppContext
                }
            })
        }.koin
        loadLanguage("zh")
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun disabledMotionSchedulesZeroFramesAndLiveToggleStartsAndStopsCleanly() {
        var parentCompositions = 0
        ImageComposeScene(width = 1180, height = 640) {
            KoinContext(appKoin) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        SideEffect { parentCompositions++ }
                        RoomListLoadingPreviewHost(
                            isRefreshing = true,
                            isDesktop = true,
                            backdrop = defaultRWPPColorScheme.background,
                        )
                    }
                }
            }
        }.use { scene ->
            scene.frames(0)
            val still = scene.png(100)
            assertFalse(scene.hasInvalidations(), "Disabled room-list loading must not request frames")
            assertContentEquals(still, scene.png(1400))
            saveBytes("static_desktop.png", still)

            settings.setUiAnimationsEnabled(true)
            Snapshot.sendApplyNotifications()
            scene.frames(1500)
            assertTrue(scene.hasInvalidations(), "Enabled room-list loading must animate continuously")
            val frame1 = scene.png(1650)
            val compositionAfterStart = parentCompositions
            for (step in 1..25) {
                scene.render((1650 + step * 16) * 1_000_000L).close()
            }
            assertEquals(
                compositionAfterStart,
                parentCompositions,
                "Master loading clock must only be read in draw/graphicsLayer phases, not parent composition",
            )
            val frame2 = scene.png(2250)
            assertFalse(frame1.contentEquals(frame2), "Loading stage pixels should smoothly advance over time")
            saveBytes("animated_desktop_t1.png", frame1)
            saveBytes("animated_desktop_t2.png", frame2)

            settings.setUiAnimationsEnabled(false)
            Snapshot.sendApplyNotifications()
            scene.frames(2600)
            val stopped = scene.png(2700)
            assertFalse(scene.hasInvalidations(), "Disabling motion live must dispose infinite loading clocks")
            assertContentEquals(stopped, scene.png(3900))
        }
    }

    @Test
    fun topBarRefreshLineAndButtonAnimateOnlyWhenRefreshingAndEnabled() {
        var refreshing by mutableStateOf(false)
        settings.setUiAnimationsEnabled(true)
        ImageComposeScene(width = 960, height = 92) {
            KoinContext(appKoin) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        MultiplayerTopBar(
                            userName = "Commander",
                            accountNameLocked = false,
                            onUserNameChange = {},
                            joinServerAddress = "",
                            onJoinServerAddressChange = {},
                            onJoinServer = {},
                            onFilter = {},
                            onRefresh = {},
                            isRefreshing = refreshing,
                            joinHistory = emptyList(),
                            onSelectJoinHistory = {},
                            onClose = {},
                        )
                    }
                }
            }
        }.use { scene ->
            // 先让桌面顶栏刷新按钮初始焦点指示动画收束，再验证空闲态零无效化。
            for (ms in 0L..400L step 20L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            assertFalse(scene.hasInvalidations(), "Idle top bar must not schedule animation frames")

            refreshing = true
            Snapshot.sendApplyNotifications()
            scene.frames(500)
            assertTrue(scene.hasInvalidations(), "Refreshing top bar should animate refresh indicator and hairline")
            val t1 = scene.png(650)
            val t2 = scene.png(1100)
            assertFalse(t1.contentEquals(t2))

            settings.setUiAnimationsEnabled(false)
            Snapshot.sendApplyNotifications()
            scene.frames(1250)
            assertFalse(scene.hasInvalidations(), "Disabled motion must freeze top bar refresh indicator cleanly")
            assertContentEquals(scene.png(1350), scene.png(2200))

            settings.setUiAnimationsEnabled(true)
            refreshing = false
            Snapshot.sendApplyNotifications()
            // 经过淡出收束时长后不再持续请求帧
            for (ms in 2300L..2900L step 20L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            assertFalse(scene.hasInvalidations(), "Stopped refresh must settle with zero remaining invalidations")
        }
    }

    @Test
    fun rendersResponsiveMultiThemePreviewsAndLightLargeFontCleanly() {
        renderThemeSnapshot(
            width = 720,
            height = 620,
            colorScheme = themes.getValue("Aqua Blue"),
            windowManager = WindowManager.Middle,
            isDesktop = true,
            language = "zh",
            fontScale = 1f,
            fileName = "aqua_tablet_720.png",
        )
        renderThemeSnapshot(
            width = 360,
            height = 640,
            colorScheme = themes.getValue("Pink Sakura"),
            windowManager = WindowManager.Small,
            isDesktop = false,
            language = "zh",
            fontScale = 1f,
            fileName = "sakura_mobile_360.png",
        )
        renderThemeSnapshot(
            width = 720,
            height = 620,
            colorScheme = lightColorScheme(),
            windowManager = WindowManager.Middle,
            isDesktop = true,
            language = "en",
            fontScale = 1.25f,
            fileName = "light_english_large_font_720.png",
        )
    }

    @Test
    fun arrivalStateAnimatesFirstRowsOnceAndLoadingTransitionsIntoRooms() =
        runDesktopComposeUiTest(width = 760, height = 560) {
            val arrivalState = RoomListArrivalState()
            assertFalse(arrivalState.consumeShouldAnimate("room-row:a", 0))
            arrivalState.markArrival()
            assertTrue(arrivalState.consumeShouldAnimate("room-row:a", 0))
            assertFalse(arrivalState.consumeShouldAnimate("room-row:a", 0), "Scrolled-back rows must not replay arrival")
            assertFalse(arrivalState.consumeShouldAnimate("room-row:g", 6), "Deep rows skip staggered arrival")

            var loading by mutableStateOf(true)
            var rooms by mutableStateOf<List<RoomDescription>>(emptyList())
            var clickedRoom: String? = null
            setContent {
                KoinContext(appKoin) {
                    MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                        BoxWithConstraints(Modifier.fillMaxSize().background(defaultRWPPColorScheme.background)) {
                            val columns = roomCardColumnCount(maxWidth, LocalDensity.current.fontScale)
                            val animate = uiAnimationsEnabled()
                            val phase = rememberRoomListLoadingPhase(active = loading)
                            LazyColumn(Modifier.fillMaxSize()) {
                                if (loading) {
                                    roomListLoadingStage(
                                        columns = columns,
                                        phase = phase,
                                        compactSkeletonRows = false,
                                        isDesktop = true,
                                        animate = animate,
                                    )
                                }
                                roomCardRows(rooms, columns, animate, arrivalState) {
                                    clickedRoom = it.uuid
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("room-list-loading-hero").assertIsDisplayed()
            assertTrue(onAllNodesWithTag("room-list-skeleton-card").fetchSemanticsNodes().size >= 4)

            runOnIdle {
                arrivalState.markArrival()
                rooms = listOf(
                    RoomDescription(
                        uuid = "r1", creator = "萌新友好对战房", mapName = "峡谷.tmx",
                        playerCurrentCount = 2, playerMaxCount = 8, label = "休闲|模组同步",
                    ),
                    RoomDescription(
                        uuid = "r2", creator = "原版经典4v4", mapName = "群岛.tmx",
                        playerCurrentCount = 5, playerMaxCount = 8, label = "原版",
                    ),
                )
                loading = false
            }
            waitForIdle()
            onNodeWithTag("room-list-loading-hero").assertDoesNotExist()
            onNodeWithText("萌新友好对战房", useUnmergedTree = true).assertIsDisplayed().performClick()
            assertEquals("r1", clickedRoom)
            saveCapture("loaded_transition_760.png")
        }

    private fun renderThemeSnapshot(
        width: Int,
        height: Int,
        colorScheme: ColorScheme,
        windowManager: WindowManager,
        isDesktop: Boolean,
        language: String,
        fontScale: Float,
        fileName: String,
    ) {
        desktop = isDesktop
        loadLanguage(language)
        settings.setUiAnimationsEnabled(true)
        ImageComposeScene(width = width, height = height) {
            KoinContext(appKoin) {
                CompositionLocalProvider(
                    LocalWindowManager provides windowManager,
                    LocalDensity provides Density(1f, fontScale = fontScale),
                ) {
                    UiMotionTheme(colorScheme, Typography()) {
                        RoomListLoadingPreviewHost(
                            isRefreshing = true,
                            isDesktop = isDesktop,
                            backdrop = if (colorScheme.surface == lightColorScheme().surface) {
                                Color(0xFFEFF2EE)
                            } else {
                                colorScheme.background
                            },
                        )
                    }
                }
            }
        }.use { scene ->
            scene.frames(0)
            saveBytes(fileName, scene.png(620))
        }
    }

    @Composable
    private fun RoomListLoadingPreviewHost(
        isRefreshing: Boolean,
        isDesktop: Boolean,
        backdrop: Color,
    ) {
        val density = LocalDensity.current
        var measuredTopBarHeight by remember { mutableStateOf(MultiplayerTopBarHeight) }
        Box(Modifier.fillMaxSize().background(backdrop)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = roomCardColumnCount(maxWidth, LocalDensity.current.fontScale)
                val animate = uiAnimationsEnabled()
                val phase = rememberRoomListLoadingPhase(active = isRefreshing)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = measuredTopBarHeight, bottom = 16.dp),
                ) {
                    roomListLoadingStage(
                        columns = columns,
                        phase = phase,
                        compactSkeletonRows = false,
                        isDesktop = isDesktop,
                        animate = animate,
                    )
                }
            }
            MultiplayerTopBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { coords ->
                        val h = with(density) { coords.size.height.toDp() }
                        if (h > 0.dp && h != measuredTopBarHeight) measuredTopBarHeight = h
                    },
                userName = "Commander",
                accountNameLocked = false,
                onUserNameChange = {},
                joinServerAddress = "",
                onJoinServerAddressChange = {},
                onJoinServer = {},
                onFilter = {},
                onRefresh = {},
                isRefreshing = isRefreshing,
                joinHistory = emptyList(),
                onSelectJoinHistory = {},
                onClose = {},
            )
        }
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    private fun ImageComposeScene.frames(startMs: Long) {
        repeat(5) {
            Snapshot.sendApplyNotifications()
            render((startMs + it * 16) * 1_000_000L).close()
        }
    }

    private fun ImageComposeScene.png(timeMs: Long): ByteArray {
        render(timeMs * 1_000_000L).close()
        Snapshot.sendApplyNotifications()
        return render(timeMs * 1_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
    }

    private fun saveBytes(name: String, bytes: ByteArray) {
        val target = File("build/reports/room-list-loading/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        target.writeBytes(bytes)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveCapture(name: String) {
        val target = File("build/reports/room-list-loading/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
