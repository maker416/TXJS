/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.koinInit
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.ChatItem
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendItem
import io.github.rwpp.net.account.FriendRequestDto
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.ui.AccountLoginDialog
import io.github.rwpp.ui.AccountRegisterDialog
import io.github.rwpp.ui.AccountView
import io.github.rwpp.ui.FriendsView
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.WindowManager
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * 把好友/聊天 Compose 画在 360×720 的 Skia 表面上，作为 Android 小屏证据。
 * 预览数据只用于截图，不发 HTTP。
 */
@OptIn(ExperimentalTestApi::class)
class FriendsUiScreenshotTest {

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        val koin = startKoin {
            modules(
                module {
                    single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh") }
                    single { AccountPreferences() }
                    single<ConfigIO> { stub(ConfigIO::class.java) }
                    single<Game> { stub(Game::class.java) }
                    single<Net> { stub(Net::class.java) }
                    single<AppContext> { stub(AppContext::class.java) }
                },
            )
        }.koin
        appKoin = koin
        koinInit = true
        val bundle = File("src/commonMain/composeResources/files/bundle_zh.toml")
        i18nTable = Toml.parseToTomlTable(bundle.readText().replace("\r", "\n"))
        seedPreview()
    }

    @AfterTest
    fun tearDown() {
        FriendsSession.clear()
        AccountSession.resetForTests()
        runCatching { stopKoin() }
        koinInit = false
    }

    @Test
    fun captureFriendsHome() = runDesktopComposeUiTest(width = 360, height = 720) {
        FriendsSession.closeChat()
        setContent {
            ScreenshotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FriendsView(onExit = {}, onGoLogin = {})
                }
            }
        }
        waitForIdle()
        save("friends_home_small.png")
    }

    @Test
    fun captureMainMenu() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            ScreenshotTheme(windowManager = WindowManager.Middle) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(53, 57, 53)),
                ) {
                    UI.UiProvider.MainMenu(
                        multiplayer = {},
                        singlePlayer = {},
                        settings = {},
                        mods = {},
                        extension = {},
                        resourceBrowser = {},
                        openSourceInfo = {},
                        account = {},
                        friends = {},
                    )
                }
            }
        }
        waitForIdle()
        save("main_menu.png")
    }

    @Test
    fun captureAddFriendDialog() = runDesktopComposeUiTest(width = 360, height = 720) {
        FriendsSession.closeChat()
        setContent {
            ScreenshotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FriendsView(onExit = {}, onGoLogin = {}, initiallyShowAdd = true)
                }
            }
        }
        waitForIdle()
        save("add_friend_dialog_small.png")
    }

    @Test
    fun captureFriendChat() = runDesktopComposeUiTest(width = 360, height = 720) {
        setContent {
            ScreenshotTheme {
                FriendsView(onExit = {}, onGoLogin = {})
            }
        }
        waitForIdle()
        save("friend_chat_small.png")
    }

    @Test
    fun captureFriendsSplitHome() = runDesktopComposeUiTest(width = 800, height = 600) {
        FriendsSession.closeChat()
        setContent {
            ScreenshotTheme(windowManager = WindowManager.Middle) {
                FriendsView(onExit = {}, onGoLogin = {})
            }
        }
        waitForIdle()
        save("friends_split_home.png")
    }

    @Test
    fun captureFriendsSplitChat() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            ScreenshotTheme(windowManager = WindowManager.Middle) {
                FriendsView(onExit = {}, onGoLogin = {})
            }
        }
        waitForIdle()
        save("friends_split_chat.png")
    }

    @Test
    fun captureLoggedOutAccount() = runDesktopComposeUiTest(width = 360, height = 720) {
        AccountSession.applyLoggedOutPreview()
        FriendsSession.clear()
        setContent {
            ScreenshotTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(53, 57, 53)),
                ) {
                    AccountView(onExit = {})
                }
            }
        }
        waitForIdle()
        save("account_logged_out_small.png")
    }

    @Test
    fun captureLoginDialog() = runDesktopComposeUiTest(width = 360, height = 720) {
        AccountSession.applyLoggedOutPreview()
        setContent {
            ScreenshotTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(53, 57, 53)),
                ) {
                    AccountLoginDialog(
                        visible = true,
                        username = "xiuyu",
                        onUsernameChange = {},
                        onDismiss = {},
                        onSwitchToRegister = {},
                        onForgot = {},
                    )
                }
            }
        }
        waitForIdle()
        save("account_login_dialog_small.png")
    }

    @Test
    fun captureRegisterDialog() = runDesktopComposeUiTest(width = 360, height = 720) {
        AccountSession.applyLoggedOutPreview()
        setContent {
            ScreenshotTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(53, 57, 53)),
                ) {
                    AccountRegisterDialog(
                        visible = true,
                        username = "xiuyu",
                        onUsernameChange = {},
                        onDismiss = {},
                        onSwitchToLogin = {},
                    )
                }
            }
        }
        waitForIdle()
        save("account_register_dialog_small.png")
    }

    @Test
    fun captureLoggedInAccount() = runDesktopComposeUiTest(width = 360, height = 720) {
        setContent {
            ScreenshotTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(53, 57, 53)),
                ) {
                    AccountView(onExit = {})
                }
            }
        }
        waitForIdle()
        save("account_home_small.png")
    }

    @Test
    fun captureLoggedInAccountLandscapeLowTransparency() = runDesktopComposeUiTest(width = 720, height = 360) {
        // 模拟把背景透明度拉到最低的横屏设备：个人中心必须有可读性下限，不能被游戏画面穿透
        val oldTransparency = UI.backgroundTransparency
        UI.backgroundTransparency = 0.05f
        try {
            setContent {
                ScreenshotTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(53, 57, 53)),
                    ) {
                        AccountView(onExit = {})
                    }
                }
            }
            waitForIdle()
            save("account_home_landscape_low_transparency.png")
        } finally {
            UI.backgroundTransparency = oldTransparency
        }
    }

    private fun seedPreview() {
        val self = AccountUser(
            id = 1,
            username = "xiuyu",
            email = "user@example.com",
            nickname = "修玉",
            createdAt = "2026-09-01T00:00:00+08:00",
        )
        val bob = PublicUser(2, "bob", "Bob")
        val incomingFrom = PublicUser(3, "mapper", "地图作者")
        AccountSession.applyPreview(self)
        FriendsSession.applyPreview(
            previewFriends = listOf(FriendItem(bob, "2026-09-14T23:01:00+08:00")),
            previewIncoming = listOf(
                FriendRequestDto(
                    id = 11,
                    fromUser = incomingFrom,
                    toUser = PublicUser(1, "xiuyu", "修玉"),
                    status = "pending",
                    createdAt = "2026-09-14T23:00:00+08:00",
                    updatedAt = "2026-09-14T23:00:00+08:00",
                ),
            ),
            previewChats = listOf(
                ChatItem(
                    id = 5,
                    peer = bob,
                    lastMessage = ChatMessageDto(20, 5, 2, "今晚开把？", "2026-09-14T23:02:00+08:00"),
                    unread = 1,
                    updatedAt = "2026-09-14T23:02:00+08:00",
                    createdAt = "2026-09-14T23:01:00+08:00",
                ),
            ),
            previewMessages = listOf(
                ChatMessageDto(19, 5, 1, "可以，我带海陆空。", "2026-09-14T23:01:30+08:00"),
                ChatMessageDto(20, 5, 2, "今晚开把？", "2026-09-14T23:02:00+08:00"),
            ),
            peer = bob,
            conversationId = 5,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val rootCount = onAllNodes(isRoot()).fetchSemanticsNodes().size
        val node = if (rootCount > 1) {
            onAllNodes(isRoot())[rootCount - 1]
        } else {
            onRoot()
        }
        val image = node.captureToImage().toAwtImage()
        val wrote = outputDirs().map { dir ->
            check(dir.exists() || dir.mkdirs()) { "cannot create $dir" }
            val file = File(dir, name)
            check(ImageIO.write(image, "png", file)) { "ImageIO.write failed for $file" }
            file
        }
        check(wrote.all { it.isFile && it.length() > 0L }) { "empty screenshot: $wrote" }
    }

    private fun outputDirs(): List<File> {
        val dirs = mutableListOf(File("build/friends-ui-screenshots"))
        val store = File("/cursor/stores/bc-e0fc145f-1942-44cd-b239-0a11e95bd9b4/media/friends-chat-ui")
        if (store.parentFile?.exists() == true) dirs += store
        val artifacts = File("/opt/cursor/artifacts")
        if (artifacts.exists()) dirs += artifacts
        return dirs
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(clazz: Class<T>): T {
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "friends-ui-stub:${clazz.simpleName}"
                "getKoin" -> appKoin
                "getLatestVersionProfile" -> null
                "isAndroid" -> false
                "isDesktop" -> true
                "isGameCouldContinue" -> false
                else -> when (method.returnType) {
                    Void.TYPE, Void::class.java -> null
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.util.List::class.java, MutableList::class.java -> emptyList<Any>()
                    java.util.Map::class.java, MutableMap::class.java -> emptyMap<Any, Any>()
                    String::class.java -> ""
                    else -> null
                }
            }
        } as T
    }
}

@androidx.compose.runtime.Composable
private fun ScreenshotTheme(
    windowManager: WindowManager = WindowManager.Small,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    KoinContext(appKoin) {
        RWPPTheme(default = true) {
            CompositionLocalProvider(LocalWindowManager provides windowManager) {
                content()
            }
        }
    }
}

@Suppress("unused")
private val pngSink: Class<BufferedImage> = BufferedImage::class.java
