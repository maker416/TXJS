/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.rwpp.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.scene.ComposeScene
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.modDir
import io.github.rwpp.platform.modFileDropTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.AfterClass
import org.slf4j.LoggerFactory
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 通过真实 Compose 拖放分发和 ModsView 导入流程验证页面权限、生命周期及最终提示。 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ModFileDropInteractionTest {
    companion object {
        // modDir 是进程级 lazy，同一个测试类的所有场景共用隔离目录。
        private val fixtureRoot = Files.createTempDirectory("rwpp-mod-drop-").toFile()

        @JvmStatic
        @AfterClass
        fun cleanupFixtures() {
            fixtureRoot.deleteRecursively()
        }
    }

    private var imageScene: ImageComposeScene? = null
    private var frameTime = 0L

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        onEdt {
            runCatching { stopKoin() }
            logger = LoggerFactory.getLogger("ModFileDropInteractionTest")
            appKoin = startKoin { modules(module {
                single { Settings(enableAnimations = false) }
                single<ModManager> { stub(ModManager::class.java) { name, _ ->
                    if (name == "getAllMods") emptyList<Any>() else null
                } }
                single<AppContext> { stub(AppContext::class.java) { name, args ->
                    when (name) {
                        "isDesktop" -> true
                        "isAndroid" -> false
                        "externalStoragePath" -> File(fixtureRoot, args!![0] as String).absolutePath
                        else -> null
                    }
                } }
            }) }.koin
            reloadI18n()
            setI18nOverride(null)
            i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
            UI.warning = null
        }
    }

    @AfterTest
    fun cleanup() {
        onEdt {
            imageScene?.close()
            imageScene = null
            UI.warning = null
            stopKoin()
            reloadI18n()
            setI18nOverride(null)
        }
        Dispatchers.resetMain()
    }

    @Test
    fun inactiveModsPageRejectsFileDrop() = onEdt {
        setContent {
            LauncherOverlayHost(true, { false }, EnterTransition.None, fadeOut(tween(1000))) {
                ModsView({}, embedded = true)
            }
        }
        assertFalse(beginDrag(listOf(File("inactive.rwmod"))))
    }

    @Test
    fun exitingModsPageRejectsFileDropBeforeItsAnimationCompletes() = onEdt {
        var visible by mutableStateOf(true)
        setContent {
            LauncherOverlayHost(visible, { visible }, EnterTransition.None, fadeOut(tween(1000))) {
                ModsView({}, embedded = true)
            }
        }
        visible = false
        render()
        render()
        assertFalse(beginDrag(listOf(File("exiting.rwmod"))))
    }

    @Test
    fun disablingDuringDragClearsOverlayAndRejectsStaleDrop() = onEdt {
        var enabled by mutableStateOf(true)
        var overlayVisible = false
        var drops = 0
        setContent {
            Box(Modifier.fillMaxSize().modFileDropTarget(
                enabled = enabled,
                onDragStarted = { overlayVisible = true },
                onDragEnded = { overlayVisible = false },
                onDragPointerInRoot = {},
                onFilesDropped = { drops++ },
            ))
        }
        assertTrue(beginDrag(listOf(File("first.rwmod"))))
        assertTrue(overlayVisible)
        enabled = false
        render()
        assertFalse(overlayVisible)
        // 同一原生拖放会话中重新启用，也不能放行失效的旧事件。
        enabled = true
        render()
        assertFalse(drop())
        assertEquals(0, drops)
        assertTrue(beginDrag(listOf(File("next.rwmod"))))
        assertTrue(drop())
        assertEquals(1, drops)
        assertFalse(overlayVisible)
    }

    @Test
    fun disposingDuringDragClearsOverlay() = onEdt {
        var visible by mutableStateOf(true)
        var overlayVisible = false
        setContent {
            if (visible) Box(Modifier.fillMaxSize().modFileDropTarget(
                enabled = true,
                onDragStarted = { overlayVisible = true },
                onDragEnded = { overlayVisible = false },
                onDragPointerInRoot = {},
                onFilesDropped = {},
            ))
        }
        assertTrue(beginDrag(listOf(File("dispose.rwmod"))))
        assertTrue(overlayVisible)
        visible = false
        render()
        assertFalse(overlayVisible)
        assertFalse(drop())
    }

    @Test
    fun multipleFilesKeepIgnoredCountInSuccessNotice() {
        val files = listOf(createMod(), createMod(), createMod())
        importFiles(files)
        val notice = awaitNotice(readI18n("mod.importSuccess", I18nType.RWPP, files.first().name))
        assertTrue(notice.contains(ignoredNotice(2)), notice)
        assertTrue(File(modDir, files.first().name).isFile)
        files.drop(1).forEach { assertFalse(File(modDir, it.name).exists()) }
    }

    @Test
    fun multipleFilesKeepIgnoredCountWhenFirstModAlreadyExists() {
        val files = listOf(createMod(), createMod())
        files.first().copyTo(File(modDir, files.first().name).apply { parentFile.mkdirs() })
        importFiles(files)
        val notice = awaitNotice(readI18n("mod.importAlreadyExists", I18nType.RWPP, files.first().name))
        assertTrue(notice.contains(ignoredNotice(1)), notice)
        assertFalse(File(modDir, files.last().name).exists())
    }

    @Test
    fun multipleFilesKeepIgnoredCountWhenImportFails() {
        val missing = File(fixtureRoot, "missing-${System.nanoTime()}.rwmod")
        val other = createMod()
        importFiles(listOf(missing, other))
        val notice = awaitNotice(missing.name)
        assertTrue(notice.contains(ignoredNotice(1)), notice)
        assertFalse(File(modDir, other.name).exists())
    }

    @Test
    fun singleFileKeepsOriginalSuccessNotice() {
        val file = createMod()
        importFiles(listOf(file))
        val expected = readI18n("mod.importSuccess", I18nType.RWPP, file.name)
        assertEquals(expected, awaitNotice(expected))
    }

    private fun importFiles(files: List<File>) = onEdt {
        setContent { ModsView({}, embedded = true) }
        assertTrue(beginDrag(files))
        assertTrue(drop())
    }

    private fun createMod(): File = File(fixtureRoot, "source-${System.nanoTime()}.rwmod").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt"))
            zip.write("[mod]\ntitle=Drop regression test\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun ignoredNotice(count: Int) = readI18n("mod.dropExtraRwmodIgnored", I18nType.RWPP, count.toString())

    private fun awaitNotice(expected: String): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        var notice = ""
        while (System.nanoTime() < deadline) {
            notice = onEdt { render(); UI.warning?.reason.orEmpty() }
            if (expected in notice) return notice
            Thread.sleep(10)
        }
        error("Expected notice containing '$expected', got '$notice'")
    }

    private fun setContent(content: @Composable () -> Unit) {
        imageScene = ImageComposeScene(1100, 850, coroutineContext = Dispatchers.Main) {
            KoinContext(appKoin) { MaterialTheme(content = content) }
        }
        render()
        render()
    }

    private fun render() {
        frameTime += 16_000_000L
        imageScene!!.render(frameTime).close()
    }

    private val scene: ComposeScene
        get() = ImageComposeScene::class.java.getDeclaredField("scene").run {
            isAccessible = true
            get(imageScene) as ComposeScene
        }

    private lateinit var dragEvent: DragAndDropEvent

    private fun beginDrag(files: List<File>): Boolean {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor) = files
        }
        val native = object : DropTargetDropEvent(
            DropTarget().dropTargetContext, Point(500, 500), DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY,
        ) {
            override fun getTransferable(): Transferable = transferable
        }
        dragEvent = DragAndDropEvent(DragAndDropTransferAction.Copy, native, Offset(500f, 500f))
        return scene.rootDragAndDropNode.run {
            acceptDragAndDropTransfer(dragEvent).also { accepted ->
                if (accepted) {
                    onStarted(dragEvent)
                    onEntered(dragEvent)
                    onMoved(dragEvent)
                }
            }
        }
    }

    private fun drop(): Boolean = scene.rootDragAndDropNode.run {
        try { onDrop(dragEvent) } finally { onEnded(dragEvent) }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    private fun <T> stub(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args) })
}
