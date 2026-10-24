/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.ui.rememberAnimatedMultiplayerListTopInset
import io.github.rwpp.ui.rememberMultiplayerContentPadding
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import java.io.File
import kotlin.test.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class UiMotionTest {
    private lateinit var settings: Settings

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        appKoin = startKoin { modules(module { single { settings } }) }.koin
    }

    @AfterTest
    fun teardown() { stopKoin() }

    @Test
    fun disabledLoadersStopRequestingFramesAndLiveToggleStopsRunningAnimations() {
        ImageComposeScene(width = 320, height = 180) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Column(verticalArrangement = Arrangement.spacedBy(25.dp)) {
                    UiLinearProgressIndicator()
                    UiCircularProgressIndicator()
                    Box(Modifier.size(120.dp, 70.dp), contentAlignment = Alignment.Center) { LineSpinFadeLoaderIndicator(radius = 16f, penThickness = 5f) }
                }
            }
        }.use { scene ->
            scene.frames(0)
            val still = scene.png(100)
            assertFalse(scene.hasInvalidations(), "Disabled loaders must not schedule another frame")
            assertContentEquals(still, scene.png(900))
            File("build/reports/ui-motion/disabled-loaders.png").apply { parentFile.mkdirs(); writeBytes(still) }

            settings.setUiAnimationsEnabled(true)
            Snapshot.sendApplyNotifications()
            scene.frames(1000)
            assertTrue(scene.hasInvalidations(), "Enabled loading must remain animated")
            val animated = scene.png(1100)
            assertFalse(animated.contentEquals(scene.png(1450)))

            settings.setUiAnimationsEnabled(false)
            Snapshot.sendApplyNotifications()
            scene.frames(1500)
            val stopped = scene.png(1600)
            assertFalse(scene.hasInvalidations(), "Switching off must dispose infinite animation jobs")
            assertContentEquals(stopped, scene.png(2500))
        }
    }

    @Test
    fun materialControlsAndCustomValuesReachTheirTargetsWithoutMotion() {
        var checked by mutableStateOf(false)
        var target by mutableStateOf(0f)
        var observed = -1f
        ImageComposeScene(width = 320, height = 140) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                val value by animateUiFloatAsState(target)
                SideEffect { observed = value }
                Row {
                    UiSwitch(checked, { checked = it })
                    UiCheckbox(checked, { checked = it })
                    RadioButton(checked, { checked = !checked })
                }
            }
        }.use { scene ->
            scene.frames(0)
            val before = scene.png(100)
            checked = true
            target = 1f
            Snapshot.sendApplyNotifications()
            scene.frames(200)
            val after = scene.png(300)
            assertEquals(1f, observed)
            assertFalse(before.contentEquals(after), "The controls must still change state")
            assertContentEquals(after, scene.png(1300))
            assertFalse(scene.hasInvalidations())
            File("build/reports/ui-motion/static-controls.png").apply { parentFile.mkdirs(); writeBytes(after) }
        }
    }

    @Test
    fun disablingMotionSettlesAnAlreadyRunningControlAndValueAnimation() {
        settings.setUiAnimationsEnabled(true)
        var checked by mutableStateOf(false)
        var target by mutableStateOf(0f)
        var observed = -1f
        ImageComposeScene(width = 320, height = 80) {
            UiMotionTheme(darkColorScheme(), Typography()) {
                val value by animateUiFloatAsState(target, androidx.compose.animation.core.tween(1000))
                SideEffect { observed = value }
                Row {
                    UiSwitch(checked, { checked = it })
                    UiCheckbox(checked, { checked = it })
                }
            }
        }.use { scene ->
            scene.frames(0)
            checked = true
            target = 1f
            Snapshot.sendApplyNotifications()
            scene.render(100_000_000L).close()
            scene.render(116_000_000L).close()
            assertTrue(observed < 1f)
            settings.setUiAnimationsEnabled(false)
            Snapshot.sendApplyNotifications()
            scene.frames(132)
            assertEquals(1f, observed)
            val settled = scene.png(220)
            val later = scene.png(1500)
            assertContentEquals(settled, later)
            assertFalse(scene.hasInvalidations())
        }
    }

    @Test
    fun topBarAnimationDoesNotRecomposeItsParentEveryFrame() {
        settings.setUiAnimationsEnabled(true)
        var visible by mutableStateOf(true)
        var compositions = 0
        ImageComposeScene(width = 320, height = 240) {
            val inset = rememberAnimatedMultiplayerListTopInset(80.dp, visible)
            val padding = rememberMultiplayerContentPadding(inset, 12.dp)
            SideEffect { compositions++ }
            Box(Modifier.fillMaxSize().padding(padding)) { Text("List content") }
        }.use { scene ->
            scene.frames(0)
            visible = false
            Snapshot.sendApplyNotifications()
            scene.frames(100)
            val start = compositions
            for (frame in 10..50) scene.render(frame * 16_000_000L).close()
            assertEquals(start, compositions, "Animated inset reads belong in measurement, not parent composition")
        }
    }

    @Test
    fun invitationTimeoutSurvivesAnimationToggleWithoutExpiringEarlyOrRestarting() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var timeouts = 0
        var progress = -1f
        setContent {
            val value by rememberTimedUiProgress("invite", 10_000) { timeouts++ }
            SideEffect { progress = value }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(4_000)
        runOnIdle { assertEquals(0, timeouts); assertEquals(1f, progress); settings.setUiAnimationsEnabled(true) }
        mainClock.advanceTimeBy(2_000)
        runOnIdle { settings.setUiAnimationsEnabled(false) }
        mainClock.advanceTimeBy(3_000)
        runOnIdle { assertEquals(0, timeouts); assertEquals(1f, progress) }
        mainClock.advanceTimeBy(1_100)
        runOnIdle { assertEquals(1, timeouts) }
    }

    @Test
    fun switchKeepsKeyboardFocusWhenTogglingTheAnimationPreference() = runDesktopComposeUiTest {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                val requester = remember { FocusRequester() }
                UiSwitch(uiAnimationsEnabled(), { settings.setUiAnimationsEnabled(it) },
                    Modifier.testTag("motion-switch").focusRequester(requester))
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
        onNodeWithTag("motion-switch").assertIsFocused().assertIsOff()
        onNodeWithTag("motion-switch").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("motion-switch").assertIsFocused().assertIsOn()
        onNodeWithTag("motion-switch").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("motion-switch").assertIsFocused().assertIsOff()
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
}
