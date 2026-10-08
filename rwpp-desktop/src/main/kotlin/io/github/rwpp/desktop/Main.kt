/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.rwpp.App
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.CoreImplModule
import io.github.rwpp.config.ConfigModule
import io.github.rwpp.config.Settings
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.event.events.QuitGameEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.sendChatMessageOrCommand
import io.github.rwpp.generatedLibDir
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.runtime.Builder
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.packageName
import io.github.rwpp.scripts.Render
import io.github.rwpp.ui.InjectConsole
import io.github.rwpp.ui.UI.chatMessages
import io.github.rwpp.ui.Widget
import io.github.rwpp.ui.defaultBuildLogger
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.MenuLoadingView
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.RWTextFieldColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import io.github.rwpp.projectVersion


typealias ColorCompose = androidx.compose.ui.graphics.Color

var native: Boolean = false
var isSendingTeamChat by mutableStateOf(false)
lateinit var mainJFrame: JFrame
lateinit var gameCanvas: Canvas
lateinit var displaySize: Dimension
lateinit var sendMessageDialog: Dialog
lateinit var displaySwitcher: DisplaySwitcher
lateinit var gameSessionManager: GameSessionManager
lateinit var focusRequester: FocusRequester
var inGameWidget: Widget? by mutableStateOf(null)
lateinit var inGameWidgetDialog: Dialog
var requireReloadingLib = false

/** 主窗口是否已创建（供 FullscreenController 等跨文件判断 lateinit 状态） */
val isMainWindowInitialized: Boolean get() = ::mainJFrame.isInitialized

//val cacheModSize = AtomicInteger(0)

private fun resolveLauncherExePath(): String {
    val dir = System.getProperty("user.dir")
    val rwjs = "$dir/RWJS.exe"
    val rwpp = "$dir/RWPP.exe"
    return when {
        File(rwjs).exists() -> rwjs
        File(rwpp).exists() -> rwpp
        else -> rwjs
    }
}

fun main(array: Array<String>) {
    // 必须在 SLF4J 创建 ConsoleAppender 之前接管输出，否则启动器日志仍写旧 stderr。
    DesktopRuntimeLog.initialize()
    // 独立窗口浮层才能覆盖 Chromium 原生子窗口，资源页悬浮球不会被网页遮挡。
    System.setProperty("compose.layers.type", "WINDOW")
    if (array.contains("-localgl") && File("opengl32.dll").exists()) { // for only debug
        System.loadLibrary("opengl32")
    }

    logger = LoggerFactory.getLogger(packageName)
    native = array.contains("-native")

    logger.info("[START] RWJS {} native={}", projectVersion, native)

    koinInit = true
    appKoin = startKoin {
        logger(org.koin.core.logger.PrintLogger(org.koin.core.logger.Level.ERROR))
            modules(ConfigModule().module, CoreImplModule().module, DesktopModule().module)
    }.koin

    appKoin.get<ConfigIO>().readAllConfig()
    Builder.outputDir = generatedLibDir
    Builder.logger = defaultBuildLogger
    requireReloadingLib = Builder.prepareReloadingLib()

    if (!requireReloadingLib) {
        val app = appKoin.get<AppContext>()
        app.init()
        val networkModCache = appKoin.get<NetworkModCache>()
        networkModCache.prepareStartup()
        app.onExit { networkModCache.cleanupWorkingCopiesOnExit() }
    }

    Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    File("mods/maps/")
        .walk()
        .filter { it.name.startsWith("generated_") }
        .forEach {
            it.delete()
        }

    val settings = appKoin.get<Settings>()
    if (settings.renderingBackend != "Default") {
        System.setProperty("skiko.renderApi", settings.renderingBackend.uppercase())
    }

    displaySize =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .displayMode
            .run { Dimension(width, height) }

    swingApplication()
}

fun swingApplication() = SwingUtilities.invokeLater {
    val panel = ComposePanel()
    panel.isVisible = true
    panel.size = displaySize.size
    panel.isOpaque = false
    panel.isFocusable = true
    panel.setContent {
        var isLoading by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf("loading...") }
        var startupError by remember { mutableStateOf<Throwable?>(null) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                if (requireReloadingLib) {
                    runCatching {
                        Builder.init(GameLibraries.`game-lib`, File(System.getProperty("user.dir"), "game-lib.jar"))

                        Builder.logger?.info("Apply config successfully. Now you can restart game to take effect. (已成功应用配置，请重启游戏以生效。)")
                        if (native) {
                            val processBuilder = ProcessBuilder(resolveLauncherExePath())
                            processBuilder.start()
                            exitProcess(0)
                        }
                    }.onFailure {
                        Builder.logger?.error(it.stackTraceToString())
                    }
                } else {
                    try {
                        val game = appKoin.get<Game>()
                        game.load { message = it }
                        withContext(Dispatchers.Main.immediate) {
                            GameLoadedEvent().broadcastIn()
                            isLoading = false
                            logger.info("[START] game initialization completed")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.error("[START] game initialization failed", error)
                        withContext(Dispatchers.Main.immediate) { startupError = error }
                    }
                }
            }
        }

        if (requireReloadingLib) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                ColorCompose.Black,
                                androidx.compose.ui.graphics.Color(52, 52, 52),
                                androidx.compose.ui.graphics.Color(2, 48, 32),
                                androidx.compose.ui.graphics.Color(52, 52, 52),
                                ColorCompose.Black
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                RWPPTheme(true) {
                    InjectConsole()
                }
            }
        } else {
            val settings = koinInject<Settings>()
            val isPremium = true
            var backgroundImagePath by remember { mutableStateOf(settings.backgroundImagePath ?: "") }
            var backgroundImageEnabled by remember { mutableStateOf(settings.backgroundImageEnabled) }
            // 主题美术包的背景图优先于用户在设置中手选的背景
            val themeBackground = ArtThemeController.activeTheme?.backgroundFile?.absolutePath
            val effectiveBackgroundPath = if (backgroundImageEnabled) themeBackground ?: backgroundImagePath else ""
            val painter = remember(effectiveBackgroundPath) {
                if (effectiveBackgroundPath.isNotBlank() && isPremium) {
                    runCatching { ImageIO.read(File(effectiveBackgroundPath)).toPainter() }.getOrNull()
                } else {
                    null
                }
            }

            Box(
                modifier = Modifier.fillMaxSize().composed {
                    if (effectiveBackgroundPath.isBlank() || !isPremium || painter == null) {
                        background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    ColorCompose.Black,
                                    androidx.compose.ui.graphics.Color(52, 52, 52),
                                    androidx.compose.ui.graphics.Color(2, 48, 32),
                                    androidx.compose.ui.graphics.Color(52, 52, 52),
                                    ColorCompose.Black
                                )
                            )
                        )
                    } else {
                        this
                    }
                },
                contentAlignment = Alignment.Center
            ) {

                if (effectiveBackgroundPath.isNotBlank() && isPremium && painter != null) {
                    Image(
                        painter = painter,
                        null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                val error = startupError
                if (error != null) {
                    RWPPTheme(true) {
                        Column(
                            modifier = Modifier.widthIn(max = 600.dp).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(readI18n("mod.startupFailed"), color = ColorCompose.White, style = MaterialTheme.typography.headlineSmall)
                            Text(readI18n("mod.startupFailedDetail", arg = arrayOf(error.toString())), color = ColorCompose.White)
                            RWTextButton(readI18n("menu.exit")) { appKoin.get<AppContext>().exit() }
                        }
                    }
                } else if (isLoading) {
                    MenuLoadingView(message)
                } else {
                    App(isPremium = isPremium) { path ->
                        backgroundImagePath = path
                        backgroundImageEnabled = settings.backgroundImageEnabled
                    }
                }

            }
        }
    }

    val window = JFrame()
    mainJFrame = window
    if (requireReloadingLib) {
        window.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    } else {
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)
    }
    window.background = java.awt.Color.BLACK
    // 全屏/窗口模式不再由启动时的 isUndecorated 决定（该属性在窗口可见后无法修改，只能重启进程），
    // 统一以带装饰窗口启动；需要全屏时由 FullscreenController 在窗口可见前应用无边框样式，
    // 运行时切换同样走该控制器，窗口句柄与游戏 OpenGL 上下文均不销毁。
    window.minimumSize = Dimension(800, 600)
    window.isResizable = true
    window.extendedState = JFrame.MAXIMIZED_BOTH
    val startFullscreen = !requireReloadingLib && appKoin.get<Settings>().isFullscreen
    FullscreenController.init(startFullscreen)
    if (startFullscreen && !FullscreenController.isWindowsPlatform) {
        // 非 Windows 平台保留原有无边框启动方式（其运行时切换走 AWT 全屏独占回退）
        window.isUndecorated = true
    }
    window.title = "RWJS"
    window.iconImage = ImageIO.read(ClassLoader.getSystemResource("composeResources/io.github.rwpp.rwpp_core.generated.resources/drawable/logo.png"))
    if (!requireReloadingLib) {
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                logger.info("[WINDOW] main window close requested")
                val result = JOptionPane.showConfirmDialog(
                    window,
                    "Are you sure to exit RWJS? (确定要退出RWJS吗？)",
                    "提示",
                    JOptionPane.YES_NO_OPTION
                )
                if (result == JOptionPane.YES_OPTION) {
                    appKoin.get<AppContext>().exit()
                }
            }
            override fun windowClosed(e: WindowEvent?) {
                logger.info("[WINDOW] main window disposed")
            }
        })
    }

    val canvas = Canvas()
    gameCanvas = canvas
    canvas.size = displaySize.size
    canvas.isVisible = false
    canvas.background = java.awt.Color.BLACK
    canvas.isFocusable = true

    val displayLayout = NativeCanvasCardLayout(canvas, ::getDPIScale)
    val displayHost = JPanel(displayLayout).apply {
        isOpaque = true
        background = java.awt.Color.BLACK
        add(panel, DisplayMode.Menu.cardName)
        add(canvas, DisplayMode.Game.cardName)
    }

    window.layout = BorderLayout()
    window.add(displayHost, BorderLayout.CENTER)

    displaySwitcher = DisplaySwitcher(
        menuPanel = panel,
        gameCanvas = gameCanvas,
        displayHost = displayHost,
        displayLayout = displayLayout,
        window = window
    )

    // Windows 下需要全屏启动时，先创建原生句柄再在窗口可见前应用无边框样式，避免闪现标题栏
    if (startFullscreen && FullscreenController.isWindowsPlatform) {
        window.pack()
        FullscreenController.enterFullscreenAtStartup(window)
    }
    window.isVisible = true
    panel.requestFocus()

    focusRequester = FocusRequester()
    val panel2 = ComposePanel()
    panel2.isOpaque = false
    panel2.isFocusable = true
    panel2.size = Dimension(550, 540)
    panel2.setContent {
        val game = koinInject<Game>()
        RWPPTheme {
            BorderCard(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyDown) {
                            closeSendMessageDialog()
                            true
                        } else false
                    },

                backgroundColor = Color(53, 57, 53),
                shape = RectangleShape
            ) {
                var chatMessage by remember { mutableStateOf("") }
                var allChatMessages by remember(chatMessages) {
                    mutableStateOf(TextFieldValue(chatMessages))
                }

                Box {
                    fun onExit() {
                        closeSendMessageDialog()
                    }

                    fun onSendMessage() {
                        if (chatMessage.isBlank()) return
                        if (isSendingTeamChat) {
                            game.gameRoom.sendChatMessage("-t $chatMessage")
                        } else {
                            game.gameRoom.sendChatMessageOrCommand(chatMessage)
                        }

                        chatMessage = ""
                        onExit()
                    }

                    GlobalEventChannel.filter(QuitGameEvent::class).onDispose {
                        subscribeAlways { onExit() }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(48.dp))
                        TextField(
                            value = allChatMessages,
                            onValueChange = { allChatMessages = it },
                            readOnly = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            colors = RWTextFieldColors,
                            maxLines = 100
                        )
                        RWSingleOutlinedTextField(
                            label = if (isSendingTeamChat) readI18n("ingame.sendTeamMessage") else readI18n("ingame.sendMessage"),
                            value = chatMessage,
                            focusRequester = focusRequester,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                                .onPreviewKeyEvent {
                                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyDown) {
                                        onSendMessage()
                                        true
                                    } else false
                                },
                            trailingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    null,
                                    modifier = Modifier.clickable {
                                        onSendMessage()
                                    }
                                )
                            },
                            onValueChange =
                            {
                                chatMessage = it
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            RWTextButton(
                                readI18n("ingame.sendMessage"),
                                modifier = Modifier.padding(5.dp)
                            ) {
                                game.gameRoom.sendChatMessageOrCommand(chatMessage)
                                chatMessage = ""
                                onExit()
                            }

                            RWTextButton(
                                readI18n("ingame.sendTeamMessage"),
                                modifier = Modifier.padding(5.dp)
                            ) {
                                game.gameRoom.sendChatMessage("-t $chatMessage")
                                chatMessage = ""
                                onExit()
                            }
                        }
                    }

                    ExitButton { onExit() }
                }
            }
        }
    }

    sendMessageDialog = Dialog(window)
    sendMessageDialog.isUndecorated = true
    sendMessageDialog.isFocusable = true
    sendMessageDialog.isVisible = false
    sendMessageDialog.isAlwaysOnTop = true
    sendMessageDialog.size = Dimension(550, 540)
    sendMessageDialog.add(panel2)

    // F11 即时切换全屏/窗口（与设置页中的「沉浸式全屏」开关等价，并立即持久化配置）
    var fullscreenKeyDown = false
    window.addWindowFocusListener(object : WindowAdapter() {
        override fun windowLostFocus(e: WindowEvent?) {
            fullscreenKeyDown = false
        }
    })
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
        if (!requireReloadingLib && e.keyCode == KeyEvent.VK_F11 && e.id == KeyEvent.KEY_RELEASED) {
            fullscreenKeyDown = false
            true
        } else if (!requireReloadingLib && e.keyCode == KeyEvent.VK_F11 && e.id == KeyEvent.KEY_PRESSED) {
            if (fullscreenKeyDown) return@addKeyEventDispatcher true
            fullscreenKeyDown = true
            val settings = appKoin.get<Settings>()
            settings.isFullscreen = !settings.isFullscreen
            FullscreenController.setFullscreen(settings.isFullscreen)
            Thread {
                runCatching { appKoin.get<ConfigIO>().saveAllConfig() }
            }.apply { isDaemon = true; name = "rwpp-config-save" }.start()
            true
        } else false
    }

    window.addComponentListener(object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            syncGameCanvasSizeToNative()
            resetSendDialogLocation()
        }

        override fun componentMoved(e: ComponentEvent) {
            // 跨显示器移动后 DPI 缩放可能变化，重新同步（尺寸未变时为空操作）
            syncGameCanvasSizeToNative()
            resetSendDialogLocation()
        }
    })
    window.addPropertyChangeListener("graphicsConfiguration") {
        SwingUtilities.invokeLater { syncGameCanvasSizeToNative() }
    }

    onInitInGameWidgetDialog()
}

fun onInitInGameWidgetDialog() = SwingUtilities.invokeLater {
    val panel = ComposePanel()
    panel.isOpaque = false
    panel.isFocusable = true
    panel.setContent {
        RWPPTheme {
            BorderCard(
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyDown) {
                            inGameWidgetDialog.isVisible = false
                            true
                        } else {
                            false
                        }
                    }.onSizeChanged { size ->
                        SwingUtilities.invokeLater {
                            val scale = inGameWidgetDialog.graphicsConfiguration?.defaultTransform?.scaleX ?: getDPIScale()
                            val preferredSize = Dimension(
                                (size.width / scale).toInt().coerceAtLeast(1),
                                (size.height / scale).toInt().coerceAtLeast(1)
                            )
                            if (inGameWidgetDialog.preferredSize != preferredSize) {
                                inGameWidgetDialog.preferredSize = preferredSize
                                inGameWidgetDialog.pack()
                                resetInGameWidgetDialogLocation()
                            }
                        }
                    },
                backgroundColor = Color(53, 57, 53),
                shape = RectangleShape
            ) {
                key(inGameWidget) { inGameWidget?.Render() }
            }
        }
    }

    inGameWidgetDialog = Dialog(mainJFrame).apply {
        isUndecorated = true
        isFocusable = true
        isVisible = false
        isAlwaysOnTop = true
        add(panel)
    }

    mainJFrame.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentMoved(e: java.awt.event.ComponentEvent) {
            resetInGameWidgetDialogLocation()
        }

        override fun componentResized(e: java.awt.event.ComponentEvent) {
            resetInGameWidgetDialogLocation()
        }
    })
}

fun resetInGameWidgetDialogLocation() {
    if (!::inGameWidgetDialog.isInitialized) return
    inGameWidgetDialog.setLocation(
        mainJFrame.x + mainJFrame.width / 2 - inGameWidgetDialog.width / 2,
        mainJFrame.y + mainJFrame.height / 2 - inGameWidgetDialog.height / 2
    )
}

fun showSendMessageDialog() {
    check(SwingUtilities.isEventDispatchThread())
    resetSendDialogLocation()
    sendMessageDialog.isVisible = true
    sendMessageDialog.requestFocus()
    SwingUtilities.invokeLater {
        if (sendMessageDialog.isVisible) focusRequester.requestFocus()
    }
}

private fun closeSendMessageDialog() {
    if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater { closeSendMessageDialog() }
        return
    }
    sendMessageDialog.isVisible = false
    isSendingTeamChat = false
}

private fun resetSendDialogLocation() {
    val window = mainJFrame
    sendMessageDialog.setLocation(window.x + window.width / 2 - sendMessageDialog.width / 2, window.y + window.height / 2 - sendMessageDialog.height / 2)
}

fun getDPIScale(): Double {
    // 优先取主窗口实际所在显示器的配置（多显示器缩放比例可能不同），
    // 窗口未初始化时回退到默认显示设备
    val config = if (::mainJFrame.isInitialized) mainJFrame.graphicsConfiguration else null
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

    // 获取系统缩放倍数（Windows 的 % 缩放比例），通常 X/Y 缩放一致
    return config.defaultTransform.scaleX
}

/**
 * 将游戏 Canvas 的组件尺寸同步为物理像素尺寸（容器逻辑尺寸 × DPI 缩放）。
 *
 * LWJGL2 在 Display.setParent 模式下直接以 canvas 组件尺寸（AWT 逻辑像素）创建/缩放
 * 原生渲染子窗口。HiDPI（系统缩放 >100%）下逻辑像素小于物理像素，会导致全屏画面缩在
 * 屏幕左上角、其余区域黑屏，因此这里把 canvas 尺寸放大到物理像素进行抵消。
 *
 * NativeCanvasCardLayout 在布局时直接设置正确尺寸，此处仅在跨屏移动或全屏切换后
 * 使用当前显示器的缩放重做布局，避免先缩小、再由 resize 回调放大的反馈循环。
 */
fun syncGameCanvasSizeToNative() {
    if (!::gameCanvas.isInitialized || !::mainJFrame.isInitialized) return

    val parent = gameCanvas.parent ?: return
    (parent.layout as? NativeCanvasCardLayout)?.layoutContainer(parent)
}
