/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import com.corrodinggames.rts.appFramework.i as LevelSelect
import com.corrodinggames.rts.game.a.a as AiPlayer
import com.corrodinggames.rts.game.e as HumanPlayer
import com.corrodinggames.rts.game.n as Player
import com.corrodinggames.rts.gameFramework.l as GameEngine
import com.corrodinggames.rts.gameFramework.w as GameObject
import io.github.rwpp.game.map.scanSavedGameFiles
import java.io.DataOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * 手动启用的真实桌面引擎读写回归；复用已存在的离屏 GL 核心加载夹具。
 * 原版目录只作为 assets/res/font 的复制源及 native 库来源，不读取、复制或修改用户存档。
 * 所有对局、配置和新存档均位于独立临时 cwd，退出工作进程后清理。
 */
class SavedGameRoundTripIntegrationTest {
    @Test
    fun singlePlayerSaveRestoresTimePlayersCreditsAndUnitsAndRejectsBadFiles() {
        val path = System.getProperty("rwpp.heap.integrationGameRoot")
        assumeTrue("需要显式指定完整原版游戏目录与可用 OpenGL/音频设备", !path.isNullOrBlank())
        val gameRoot = File(requireNotNull(path)).canonicalFile
        assertTrue(EngineHeapAnalyzer.isGameRoot(gameRoot))
        val sandbox = createTempDirectory("rwjs-save-roundtrip-").toFile()
        try {
            listOf("assets", "res", "font").forEach { name ->
                assertTrue(File(gameRoot, name).copyRecursively(File(sandbox, name)))
            }
            assertTrue(File(sandbox, "mods/units").mkdirs())
            val output = File(sandbox, "roundtrip.properties")
            val log = File(sandbox, "engine.log")
            val classpath = requireNotNull(System.getProperty("rwpp.heap.testWorkerClasspath")) {
                "请通过 rwpp-mod-heap-tool:test 运行以提供隔离子进程的 classpath"
            }.split(File.pathSeparator).joinToString(File.pathSeparator) { File(it).absolutePath }
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val process = ProcessBuilder(
                java, "-Xmx2048m", "-Xss16m", "-Dfile.encoding=UTF-8",
                "-Djava.library.path=${gameRoot.path}", "-Dorg.lwjgl.librarypath=${gameRoot.path}",
                "-cp", classpath, SavedGameRoundTripWorker::class.java.name, gameRoot.path, output.absolutePath,
            ).directory(sandbox).redirectErrorStream(true).redirectOutput(log).start()
            try {
                if (!process.waitFor(3, TimeUnit.MINUTES)) {
                    fail("存档实测子进程超时：\n${log.readText().takeLast(5000)}")
                }
                assertEquals(0, process.exitValue(), log.readText().takeLast(7000))
                assertTrue(output.isFile, "存档实测未返回结果")
                val results = Properties().apply { output.reader().use { load(it) } }
                assertEquals("true", results.getProperty("roundtrip"))
                assertEquals("true", results.getProperty("corruptRejected"))
                assertEquals("true", results.getProperty("newerRejected"))
                assertEquals("false", results.getProperty("networkLoad"))
                val report = File("build/reports/saved-game-roundtrip.properties").apply { parentFile.mkdirs() }
                report.writeText(output.readText())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            sandbox.deleteRecursively()
        }
    }
}

/** 单独 JVM；同一线程持有真实 GL 上下文并调用与客户端一致的单人 c(filename,false)。 */
internal object SavedGameRoundTripWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 2)
            val results = Properties()
            EngineModLoader(File(args[0])).use { loader ->
                loader.loadBaseline()
                val engine = GameEngine.B()
                val map = File("assets/maps/skirmish").listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "tmx" && it.name.startsWith("[p2]") }
                    .minByOrNull { it.length() }
                    ?: error("原版资源缺少双人遭遇战地图")
                // 与原版单人遭遇战相同，创建本地玩家和一个 AI，然后真正解析并加载地图。
                LevelSelect.a("maps/skirmish/${map.name}", true, 1, 0, true, false)
                check(engine.bL != null && engine.bL.W) { "夹具地图加载失败" }
                val local = checkNotNull(engine.bs)
                val ai = checkNotNull(Player.k(1))
                check(ai is AiPlayer) { "夹具必须包含一个真实 AI 玩家" }
                engine.by = 123_456
                local.v = "RWJS save test"
                local.o = 76_543.0
                ai.o = 45_678.0
                val expectedLocalSlot = local.k
                val expectedObjects = GameObject.er.size
                check(expectedObjects > 0) { "夹具必须包含真实游戏单位" }
                val name = "rwjs_roundtrip.v1.2"
                engine.ca.b(name, false)
                val files = scanSavedGameFiles(File("saves"))
                val save = files.single()
                check(save.name == "$name.rwsave" && save.fileSizeBytes > 0) { "存档未完整写出" }
                val mapEntry = save.toGameMap(0)
                check(mapEntry.mapName + mapEntry.getMapSuffix() == save.name) { "列表改变了真实存档文件名" }

                // 改变内存状态并清空玩家表，确保读档恢复实际保存的玩家/AI，而非沿用当前房间。
                engine.by = 42
                local.o = 1.0
                ai.o = 2.0
                Player.F()
                engine.bs = HumanPlayer(3)
                engine.bX.b("loading new save")
                engine.bv = false
                check(engine.ca.c(mapEntry.mapName + mapEntry.getMapSuffix(), false)) { "真实单人读档返回失败" }
                check(engine.bL != null && engine.bL.W)
                check(engine.by == 123_456) { "游戏时间未恢复" }
                check(engine.bs.k == expectedLocalSlot) { "本地玩家席位未恢复" }
                check(engine.bs.v == "RWJS save test" && engine.bs.o == 76_543.0) { "本地玩家数据未恢复" }
                val restoredAi = checkNotNull(Player.k(1))
                check(restoredAi is AiPlayer && restoredAi.o == 45_678.0) { "AI 类型或资金未恢复" }
                check(GameObject.er.size == expectedObjects) { "存档单位对象数量未恢复" }
                results.setProperty("roundtrip", "true")
                results.setProperty("networkLoad", "false")
                results.setProperty("time", engine.by.toString())
                results.setProperty("localSlot", engine.bs.k.toString())
                results.setProperty("objects", GameObject.er.size.toString())
                results.setProperty("saveBytes", save.fileSizeBytes.toString())

                // 引擎在旧有效地图仍存在时也会对坏头/新版本返回 false；调用方必须判断返回值。
                DataOutputStream(File("saves/corrupt.rwsave").outputStream()).use { it.writeUTF("not a save") }
                check(!engine.ca.c("corrupt.rwsave", false)) { "损坏存档被引擎当作成功" }
                results.setProperty("corruptRejected", "true")
                DataOutputStream(File("saves/newer.rwsave").outputStream()).use {
                    it.writeUTF("rustedWarfareSave")
                    it.writeInt(176)
                    it.writeInt(999)
                }
                check(!engine.ca.c("newer.rwsave", false)) { "未来版本存档被引擎当作成功" }
                results.setProperty("newerRejected", "true")
            }
            File(args[1]).writer().use { results.store(it, "Isolated original-engine save/load round trip") }
            exitProcess(0)
        } catch (failure: Throwable) {
            failure.printStackTrace(System.err)
            exitProcess(1)
        }
    }
}
