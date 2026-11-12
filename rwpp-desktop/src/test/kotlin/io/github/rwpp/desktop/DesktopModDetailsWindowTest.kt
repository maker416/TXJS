/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.ptr.IntByReference
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.ui.ModsAndMapsView
import io.github.rwpp.ui.ModsMapsTab
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.compose.KoinContext
import org.slf4j.LoggerFactory
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.accessibility.Accessible
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 使用生产透明 Direct3D 菜单、WINDOW 弹层和真实模组页，检查实际后端与 SkiaLayer 位图。
 * RWJS_TEST_VISIBLE_CAPTURE=true 附加屏幕像素检查，避免卡片只存在于绘制缓冲区。
 * 默认使用定向 AWT 输入与 Skia 位图；RWJS_TEST_ROBOT_INPUT=true 显式启用前台 Robot。
 * 可用 -Prwjs.test.uiScale=2.0 验证 HiDPI。
 */
@OptIn(ExperimentalSkikoApi::class)
class DesktopModDetailsWindowTest {
    @Test fun detailsRemainVisibleWithAnimations() = verify(animations = true)
    @Test fun detailsRemainVisibleWithoutAnimations() = verify(animations = false)

    private fun verify(animations: Boolean) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
        val previousLayers = System.getProperty("compose.layers.type")
        val previousApi = System.getProperty("skiko.renderApi")
        val fixtureRoot = Files.createTempDirectory("rwpp-native-mod-details-").toFile()
        val mod = fixtureMod(fixtureRoot)
        val report = File("build/reports/desktop-mod-details/animations-$animations-scale-${System.getProperty("sun.java2d.uiScale", "default")}")
            .apply { mkdirs() }
        var frame: JFrame? = null
        var overlay: DesktopLauncherOverlay? = null
        var menu: JDialog? = null
        try {
            System.setProperty("compose.layers.type", "WINDOW")
            System.setProperty("skiko.renderApi", "OPENGL")
            assertTrue(DesktopWindowLayers.install())
            runCatching { stopKoin() }
            logger = LoggerFactory.getLogger("DesktopModDetailsWindowTest")
            appKoin = startKoin {
                modules(module {
                    single { Settings(enableAnimations = animations) }
                    single<ModManager> {
                        stub(ModManager::class.java) { name, _ ->
                            when (name) {
                                "getAllMods" -> listOf(mod)
                                "getKoin" -> appKoin
                                else -> error("Unexpected ModManager access: $name")
                            }
                        }
                    }
                    single<AppContext> {
                        stub(AppContext::class.java) { name, args ->
                            when (name) {
                                "isDesktop" -> true
                                "isAndroid" -> false
                                "externalStoragePath" -> File(fixtureRoot, args!![0] as String).absolutePath
                                "getKoin" -> appKoin
                                else -> error("Unexpected AppContext access: $name")
                            }
                        }
                    }
                    single<ExternalHandler> {
                        stub(ExternalHandler::class.java) { name, _ ->
                            when (name) {
                                "getKoin" -> appKoin
                                else -> error("Unexpected ExternalHandler access: $name")
                            }
                        }
                    }
                })
            }.koin
            reloadI18n()
            setI18nOverride(null)
            i18nTable = Toml.parseToTomlTable(File("../rwpp-core/src/commonMain/composeResources/files/bundle_zh.toml").readText())
            UI.pendingModsMapsTab = ModsMapsTab.Mods
            onEdt {
                val canvas = object : Canvas() {
                    override fun paint(graphics: Graphics) {
                        graphics.color = java.awt.Color(32, 96, 144)
                        graphics.fillRect(0, 0, width, height)
                    }
                    override fun update(graphics: Graphics) = paint(graphics)
                }
                val host = JPanel(NativeCanvasCardLayout(canvas) { 1.0 }).apply {
                    add(canvas, DisplayMode.Game.cardName)
                }
                val content: @Composable () -> Unit = {
                    KoinContext(appKoin) {
                        MaterialTheme(colorScheme = defaultRWPPColorScheme.copy(surfaceContainerHigh = DetailColor)) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f / 255f))) {
                                ModsAndMapsView(onExit = {})
                            }
                        }
                    }
                }
                val fallback = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
                    isOpaque = false
                    setContent(content)
                }
                val owner = JFrame("RWJS mod details native regression").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    add(host)
                    val screen = graphicsConfiguration.bounds
                    val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
                    setSize(minOf(1280, screen.width - insets.left - insets.right),
                        minOf(720, screen.height - insets.top - insets.bottom))
                    setLocationRelativeTo(null)
                    isVisible = true
                    toFront()
                }
                frame = owner
                overlay = DesktopLauncherOverlay(fallback, host, owner, nativeContent = content).also { it.showMenu() }
                overlay!!.focusMenu()
            }
            await("native menu and first mod") {
                val current = overlay!!
                if (!current.isNativeRendering || !current.contentComponent.isShowing) false else {
                    menu = SwingUtilities.getWindowAncestor(current.contentComponent) as JDialog
                    findBounds(menu!!, mod.name) != null
                }
            }
            val robot = Robot().apply { autoDelay = 30 }
            if (!localInput) activate(menu!!)
            File(report, "verification-mode.txt").writeText(if (localInput)
                "Directed AWT input, SkiaLayer backing bitmap and Direct3D backend assertion; foreground Robot input/screen pixels were not verified."
                else "Foreground Robot input and screen pixels.")
            repeat(2) { iteration ->
                screenshot(robot, menu!!, File(report, "$iteration-before.png"))
                click(robot, menu!!, assertNotNull(onEdtValue { findBounds(menu!!, mod.name) }))
                await("owned details window") { menu!!.ownedWindows.any { it.isShowing } }
                val dialog = onEdtValue { menu!!.ownedWindows.single { it.isShowing } }
                onEdt {
                    File(report, "$iteration-renderers.txt").writeText(
                        "menu=${findSkiaLayers(menu!!).map { it.renderApi }}\ndialog=${findSkiaLayers(dialog).map { it.renderApi }}\n"
                    )
                }
                await("details title and close button") {
                    findBounds(dialog, mod.name) != null && findBounds(dialog, readI18n("common.close")) != null
                }
                // 等动画稳定后同时验证可交互布局和实际绘制；透明空窗也能满足以上节点检查。
                Thread.sleep(350)
                val image = screenshot(robot, dialog, File(report, "$iteration-open.png"))
                val expectedViewport = onEdtValue { Rectangle(overlay!!.contentComponent.locationOnScreen, overlay!!.contentComponent.size) }
                onEdt {
                    assertEquals(menu, dialog.owner)
                    assertEquals(expectedViewport, dialog.bounds)
                    assertFalse(dialog.isAlwaysOnTop)
                    assertTrue(expectedViewport.contains(assertNotNull(findBounds(dialog, mod.name))), "Mod title must remain in the visible viewport")
                    assertTrue(expectedViewport.contains(assertNotNull(findBounds(dialog, readI18n("common.close")))), "Close button must remain in the visible viewport")
                    assertNotNull(findBounds(dialog, readI18n("mod.delete")))
                }
                if (System.getenv("RWJS_TEST_VISIBLE_CAPTURE").toBoolean()) {
                    val visibleImage = captureVisibleTree(robot, frame!!, dialog, File(report, "$iteration-visible.png"))
                    File(report, "verification-mode.txt").appendText(
                        "\nDiagnostic screen capture temporarily raises the owner window tree without global mouse/keyboard input."
                    )
                    assertTrue(countColor(visibleImage, DetailColor.toArgb()) > 1_000,
                        "The mod details card must reach the screen, not only the Skia backing bitmap")
                }
                onEdt {
                    assertEquals(listOf(GraphicsApi.DIRECT3D), findSkiaLayers(dialog).map { it.renderApi },
                        "A transparent native dialog must follow the menu's Direct3D renderer to present its card")
                }
                assertTrue(countColor(image, DetailColor.toArgb()) > 1_000,
                    "The native mod details card was not painted; only a dimmed launcher may be visible (see ${report.absolutePath})")
                click(robot, dialog, assertNotNull(onEdtValue { findBounds(dialog, readI18n("common.close")) }))
                await("details dismissed and mod list restored") {
                    menu!!.ownedWindows.none { it.isShowing } && findBounds(menu!!, mod.name) != null
                }
            }
        } finally {
            if (menu?.isShowing == true && (localInput || isForeground(menu!!))) runCatching {
                screenshot(Robot(), menu!!, File(report, "final.png"))
            }
            onEdt {
                overlay?.dispose()
                frame?.let(::disposeTree)
            }
            stopKoin()
            UI.pendingModsMapsTab = null
            setI18nOverride(null)
            reloadI18n()
            fixtureRoot.deleteRecursively()
            restoreProperty("compose.layers.type", previousLayers)
            restoreProperty("skiko.renderApi", previousApi)
        }
    }

    private fun fixtureMod(root: File): Mod {
        val file = File(root, "native-details.rwmod")
        val title = "桌面详情显示回归模组"
        val descriptionText = "打开详情后应显示模组名称、说明、路径和删除按钮。".repeat(12)
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("mod-info.txt"))
            it.write("[mod]\ntitle=$title\ndescription=$descriptionText\n".toByteArray())
            it.closeEntry()
        }
        return object : Mod {
            override val id = 4261
            override val name = title
            override val description = descriptionText
            override val minVersion = "1.15"
            override val errorMessage: String? = null
            override var isEnabled = true
            override val path = file.absolutePath
            override fun getRamUsed() = "16 MB"
            override fun getSize() = file.length()
            override fun getBytes() = file.readBytes()
        }
    }

    private fun findBounds(root: Accessible, text: String): Rectangle? {
        val context = root.accessibleContext ?: return null
        val component = context.accessibleComponent
        if (listOfNotNull(context.accessibleName, context.accessibleDescription).any { it.contains(text) } && component?.isShowing == true) {
            val bounds = runCatching { Rectangle(component.locationOnScreen, component.size) }.getOrNull()
            if (bounds != null && bounds.width > 0 && bounds.height > 0) return bounds
        }
        for (index in 0 until context.accessibleChildrenCount) {
            val child = context.getAccessibleChild(index) ?: continue
            findBounds(child, text)?.let { return it }
        }
        return null
    }

    private fun click(robot: Robot, window: Window, bounds: Rectangle) {
        if (localInput) {
            onEdt {
                val canvas = findSkiaLayers(window).single().canvas
                val origin = canvas.locationOnScreen
                val x = bounds.x + bounds.width / 2 - origin.x
                val y = bounds.y + bounds.height / 2 - origin.y
                val timestamp = System.currentTimeMillis()
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, timestamp, 0, x, y, 0, false))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, timestamp, 0, x, y, 0, false))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, timestamp,
                    InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, timestamp + 1,
                    0, x, y, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, timestamp + 2,
                    0, x, y, 1, false, MouseEvent.BUTTON1))
            }
            return
        }
        activate(window)
        onEdt {
            assertTrue(
                ForegroundWindows.INSTANCE.GetForegroundWindow() == Native.getWindowPointer(window))
        }
        robot.mouseMove(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()
    }

    private fun activate(window: Window) {
        val until = System.nanoTime() + 3_000_000_000L
        do {
            onEdt {
                val native = ForegroundWindows.INSTANCE
                val foreground = native.GetForegroundWindow()
                val foregroundThread = native.GetWindowThreadProcessId(foreground, null)
                val ourThread = NativeThreads.INSTANCE.GetCurrentThreadId()
                val attached = foregroundThread != 0 && foregroundThread != ourThread &&
                    native.AttachThreadInput(ourThread, foregroundThread, true)
                try {
                    val handle = Native.getWindowPointer(window)
                    native.ShowWindow(handle, 9)
                    window.toFront()
                    window.requestFocus()
                    native.SetForegroundWindow(handle)
                } finally {
                    if (attached) native.AttachThreadInput(ourThread, foregroundThread, false)
                }
            }
            if (onEdtValue { ForegroundWindows.INSTANCE.GetForegroundWindow() == Native.getWindowPointer(window) }) return
            Thread.sleep(40)
        } while (System.nanoTime() < until)
        fail("Windows did not allow the test's own window to become foreground; native Robot/pixel verification was not run")
    }

    private fun screenshot(robot: Robot, window: Window, file: File): BufferedImage {
        val image = if (localInput) onEdtValue {
            val layer = findSkiaLayers(window).single()
            layer.renderImmediately()
            assertNotNull(layer.screenshot()).use { bitmap ->
                BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB).also { buffered ->
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        buffered.setRGB(x, y, bitmap.getColor(x, y))
                    }
                }
            }
        } else {
            robot.waitForIdle()
            assertTrue(isForeground(window), "Screen pixels require the test's own window to be foreground")
            robot.createScreenCapture(onEdtValue { Rectangle(window.locationOnScreen, window.size) })
        }
        ImageIO.write(image, "png", file)
        return image
    }

    /** 只作实际呈现诊断；短暂提升整个自有树，不单独把弹层置顶来改变其层级关系。 */
    private fun captureVisibleTree(robot: Robot, owner: JFrame, dialog: Window, file: File): BufferedImage {
        val previousTopmost = onEdtValue { owner.isAlwaysOnTop }
        return try {
            onEdt { owner.isAlwaysOnTop = true }
            Thread.sleep(150)
            robot.waitForIdle()
            val bounds = onEdtValue { Rectangle(dialog.locationOnScreen, dialog.size) }
            robot.createScreenCapture(bounds).also { ImageIO.write(it, "png", file) }
        } finally {
            onEdt { owner.isAlwaysOnTop = previousTopmost }
        }
    }

    private fun countColor(image: BufferedImage, expected: Int): Int {
        val red = expected shr 16 and 255
        val green = expected shr 8 and 255
        val blue = expected and 255
        var count = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val actual = image.getRGB(x, y)
            if (abs((actual shr 16 and 255) - red) <= 3 && abs((actual shr 8 and 255) - green) <= 3 &&
                abs((actual and 255) - blue) <= 3) count++
        }
        return count
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            if (onEdtValue(condition)) return
            Thread.sleep(20)
        }
        fail("Mod details native window did not reach: $stage")
    }

    private fun disposeTree(window: Window) {
        window.ownedWindows.forEach(::disposeTree)
        window.dispose()
    }

    private fun findSkiaLayers(component: Component): List<SkiaLayer> = buildList {
        if (component is SkiaLayer) add(component)
        if (component is Container) component.components.forEach { addAll(findSkiaLayers(it)) }
    }

    private fun onEdt(action: () -> Unit) = onEdtValue(action)

    private fun <T> onEdtValue(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        var result: T? = null
        try {
            SwingUtilities.invokeAndWait { result = action() }
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    private fun <T> stub(type: Class<T>, result: (String, Array<out Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NativeModDetailsFixture(${type.simpleName})"
                else -> result(method.name, args)
            }
        } as T
    }

    private interface ForegroundWindows : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun SetForegroundWindow(window: Pointer?): Boolean
        fun ShowWindow(window: Pointer?, command: Int): Boolean
        fun GetWindowThreadProcessId(window: Pointer?, process: IntByReference?): Int
        fun AttachThreadInput(fromThread: Int, toThread: Int, attach: Boolean): Boolean
        companion object {
            val INSTANCE: ForegroundWindows by lazy { Native.load("user32", ForegroundWindows::class.java) }
        }
    }

    private interface NativeThreads : StdCallLibrary {
        fun GetCurrentThreadId(): Int
        companion object {
            val INSTANCE: NativeThreads by lazy { Native.load("kernel32", NativeThreads::class.java) }
        }
    }

    private fun isForeground(window: Window): Boolean = onEdtValue {
        ForegroundWindows.INSTANCE.GetForegroundWindow() == Native.getWindowPointer(window)
    }

    companion object {
        private val DetailColor = Color(0xFF314F6B)
        private val localInput: Boolean get() = !System.getProperty("rwjs.test.robotInput", "false").toBoolean() &&
            !System.getenv("RWJS_TEST_ROBOT_INPUT").toBoolean()
    }
}
