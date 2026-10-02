/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import android.content.ServerContext
import android.os.Looper
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.l as GameEngine
import com.corrodinggames.rts.gameFramework.n as EngineCallbacks
import com.corrodinggames.rts.gameFramework.a.e as SoundEngine
import com.corrodinggames.rts.gameFramework.j.n as NetworkEngine
import com.corrodinggames.rts.java.audio.lwjgl.OpenALAudio
import org.lwjgl.openal.AL10
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.DisplayMode
import org.lwjgl.opengl.Pbuffer
import org.lwjgl.opengl.PixelFormat
import org.newdawn.slick.opengl.renderer.Renderer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class LoadedUnit(val name: String, val fileName: String, val root: Any)

/** G/H 是原版引擎资源记账，不是 JVM 堆或驱动显存的实测值。 */
internal data class EngineLoadResult(
    val units: List<LoadedUnit>,
    val textureAccountedBytes: Long,
    val soundAccountedBytes: Long,
    val warnings: List<String>,
)

/**
 * 仅供独立测量 JVM 使用。进程工作目录必须是含 assets/res/font 的临时目录，
 * mods/units 必须为空；不使用启动器、用户配置或生成后的注入游戏库。
 * 所有 GL 和引擎入口固定在调用 loadBaseline 的同一线程运行。
 */
internal class EngineModLoader(private val gameRoot: File) : AutoCloseable {
    private lateinit var engine: GameEngine
    private var owner: Thread? = null
    private var pbuffer: Pbuffer? = null
    private var audio: OpenALAudio? = null
    private var fallbackDisplay = false
    private var measured = false
    private val warnings = mutableListOf<String>()

    fun loadBaseline() {
        check(owner == null) { "此加载器只允许初始化一次；每个模组需要独立测量进程。" }
        check(GameEngine.B() == null) { "测量 JVM 中已存在游戏引擎。" }
        owner = Thread.currentThread()
        val working = File(".").canonicalFile
        require(working != gameRoot.canonicalFile) { "测量进程必须在隔离临时目录运行。" }
        for (resource in listOf("assets", "res", "font")) {
            require(File(working, resource).isDirectory) { "测量目录缺少原版资源：$resource" }
        }
        val modsDirectory = File(working, "mods/units")
        require(modsDirectory.isDirectory && modsDirectory.listFiles()?.isEmpty() == true) {
            "测量目录的 mods/units 必须存在且为空，避免其他模组污染基线。"
        }
        // LWJGL 读取该属性时动态选择原版 native；父进程也须传 java.library.path。
        System.setProperty("org.lwjgl.librarypath", gameRoot.canonicalPath)
        createGraphicsContext()
        startLooper()

        GameEngine.aU = true
        GameEngine.bb = true
        GameEngine.aX = true
        GameEngine.aW = true
        GameEngine.bg = com.corrodinggames.rts.java.e::class.java
        // 原版 -nobackground：只关闭随机菜单场景，仍完整加载核心资源与单位。
        GameEngine.ay = true
        GameEngine.aB = false
        GameEngine.aD = false
        val openAL = OpenALAudio(20, 9, 512)
        audio = openAL
        // 这份原版 JAR 的 hasDevice() 字节码实际返回 noDevice，不能按名字使用。
        val noDevice = OpenALAudio::class.java.getDeclaredField("noDevice").run {
            isAccessible = true
            getBoolean(openAL)
        }
        check(!noDevice) { "OpenAL 音频设备不可用；无法完整测量真实音效加载。" }
        // 加载与分配仍正常进行，但分析进程不播放声音。
        AL10.alListenerf(AL10.AL_GAIN, 0f)
        SoundEngine.c = com.corrodinggames.rts.java.o(openAL)
        com.corrodinggames.rts.gameFramework.am.a = com.corrodinggames.rts.java.l(openAL)
        NetworkEngine.d = com.corrodinggames.rts.java.k()
        com.corrodinggames.rts.gameFramework.ac.b = com.corrodinggames.rts.java.v()

        engine = GameEngine.a(ServerContext(), object : EngineCallbacks() {
            override fun a(message: String, code: Int) { warnings += message }
            override fun a(title: String, message: String) { warnings += "$title: $message" }
            override fun a(message: String, modal: Boolean) { /* 原版加载进度写入进程日志。 */ }
            override fun a(error: Throwable?) { if (error != null) throw error }
        })
        engine.bQ.sendReports = false
        check(engine.bi) { "真实游戏核心没有完成初始化。" }
        check(!engine.ee && !engine.eh && !engine.ei) { "真实核心进入安全模式，测量已停止。" }
        engine.bQ.loadDisabledModData = false
        engine.bQ.musicVolume = 0f
        // 与待测阶段走相同完整重载路径，预热核心解析/缓存，减少首次调用污染。
        engine.bZ.k().forEach { (it as com.corrodinggames.rts.gameFramework.i.b).f = true }
        engine.bZ.a(false, false)
    }

    fun loadMod(modFile: File): EngineLoadResult {
        checkOwner()
        check(!measured) { "每个模组必须在新的独立进程中测量。" }
        measured = true
        require(modFile.exists()) { "模组不存在：${modFile.absolutePath}" }
        require(modFile.isDirectory || modFile.extension.lowercase() in listOf("rwmod", "zip")) {
            "真实核心测量需要完整模组目录、.rwmod 或 .zip；单个 INI 不包含完整资源与依赖。"
        }
        val input = normalizeArchive(modFile.canonicalFile)
        // 引擎公开登记接口使用绝对路径，避免复制大型模组。m 保留手动登记项，
        // bZ.k() 之后仍能用原版递归扫描器读取它；不替换 INI/图像/音效解析器。
        val info = engine.bZ.a(
            modFile.name, modFile.name, input.absolutePath, "rwjs-heap-measured", true, false, false, 0,
        )
        info.m = true
        info.f = false
        val registrationErrors = listOfNotNull(info.R) + info.V.map { it.toString() }
        check(registrationErrors.isEmpty()) { "真实核心登记模组失败：${registrationErrors.distinct().joinToString("；")}" }

        // 与桌面 ModManager 相同：ag.h() 扫描/解析、跨单位解析、注册表与动作终结。
        engine.bZ.a(false, false)
        val errors = listOfNotNull(info.R) + info.V.map { it.toString() }
        check(errors.isEmpty()) { "真实核心加载失败，已丢弃不完整测量：${errors.distinct().joinToString("；")}" }
        check(!info.f && !info.C) { "真实核心未启用待测模组，不能报告测量成功。" }
        val definitions = UnitDefinition.c.filterIsInstance<UnitDefinition>().filter { it.J === info }
        check(definitions.isNotEmpty()) { "真实核心没有从此模组加载任何单位。" }
        return EngineLoadResult(
            units = definitions.map { LoadedUnit(it.M ?: it.i(), it.b(), it) },
            textureAccountedBytes = info.G,
            soundAccountedBytes = info.H,
            warnings = (warnings + info.U.map { it.toString() }).distinct(),
        )
    }

    private fun normalizeArchive(source: File): File {
        if (source.isDirectory || source.extension == "rwmod") return source
        // 原版仅按 .rwmod 识别 ZIP 容器，优先硬链接，跨盘时以流复制规范扩展名。
        val target = File("heap-measured-input.rwmod").absoluteFile
        require(!target.exists()) { "测量临时目录已存在输入归档。" }
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (_: Exception) {
            Files.copy(source.toPath(), target.toPath())
        }
        return target
    }

    private fun createGraphicsContext() {
        try {
            val buffer = Pbuffer(16, 16, PixelFormat(), null, null)
            pbuffer = buffer
            buffer.makeCurrent()
        } catch (failure: Exception) {
            pbuffer?.destroy()
            pbuffer = null
            // 仍使用真实桌面 OpenGL，仅将极小窗口移到屏幕外；不降级为假图像。
            Display.setDisplayMode(DisplayMode(16, 16))
            Display.setLocation(-32_000, -32_000)
            Display.setTitle("RWJS heap measurement")
            try {
                Display.create(PixelFormat())
                fallbackDisplay = true
            } catch (displayFailure: Exception) {
                displayFailure.addSuppressed(failure)
                throw IllegalStateException("无法创建真实 OpenGL 上下文；请检查原版 native 与显卡驱动。", displayFailure)
            }
            warnings += "显卡不支持离屏 Pbuffer，已使用屏幕外 OpenGL 上下文。"
        }
        Renderer.get().initDisplay(16, 16)
    }

    private fun startLooper() {
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Thread({
            try {
                GameEngine.aq()
                Looper.a()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                ready.countDown()
            }
            if (failure.get() == null) Looper.c()
        }, "rwjs-heap-engine-looper").apply { isDaemon = true; start() }
        check(ready.await(10, TimeUnit.SECONDS)) { "原版 Android 兼容消息循环初始化超时。" }
        failure.get()?.let { throw IllegalStateException("原版消息循环初始化失败。", it) }
    }

    private fun checkOwner() {
        check(owner === Thread.currentThread() && ::engine.isInitialized) {
            "请在完成 loadBaseline 的同一线程调用真实核心加载。"
        }
    }

    override fun close() {
        // 仅在测量/对象遍历结束后释放；子 JVM 仍需退出，终止原版后台线程。
        check(owner == null || owner === Thread.currentThread())
        audio?.dispose()
        audio = null
        pbuffer?.destroy()
        pbuffer = null
        if (fallbackDisplay) Display.destroy()
    }
}
