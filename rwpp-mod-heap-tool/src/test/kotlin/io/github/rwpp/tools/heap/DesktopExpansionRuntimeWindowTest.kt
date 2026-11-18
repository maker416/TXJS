/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import com.corrodinggames.rts.game.units.custom.ag as UnitLoader
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.i.a as EngineModManager
import com.corrodinggames.rts.gameFramework.i.b as EngineMod
import com.corrodinggames.rts.gameFramework.e.a as EngineFiles
import com.corrodinggames.rts.gameFramework.l as GameEngine
import io.github.rwpp.game.mod.ContentModSource
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.PathType
import io.github.rwpp.inject.runtime.InjectApi
import io.github.rwpp.logger
import org.junit.Assume.assumeTrue
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opt-in native integration test: real engine, OpenGL textures and full unit parsing. The isolated
 * game JAR receives the same production scan hooks through InjectApi; this does not manually call
 * those hooks or replace native scanning/parsing. No client installation or user configuration is used.
 * This covers source isolation/restoration, not an actual server join or launcher art rendering.
 */
class DesktopExpansionRuntimeWindowTest {
    @Test fun nativeReloadUsesOnlyTheIndependentExpansionAndRestoresOrdinaryUnits() {
        val path = System.getProperty("rwpp.heap.integrationGameRoot")
        assumeTrue("指定 rwpp.heap.integrationGameRoot 才运行真实 OpenGL 核心测试", !path.isNullOrBlank())
        val gameRoot = File(requireNotNull(path)).canonicalFile
        assertTrue(EngineHeapAnalyzer.isGameRoot(gameRoot), "需要完整桌面游戏资源和 native：$gameRoot")
        val work = createTempDirectory("rwjs-expansion-runtime-").toFile()
        val reportRoot = File("build/reports/expansion-runtime").apply { mkdirs() }
        try {
            val sandbox = File(work, "sandbox").apply { mkdirs() }
            for (name in listOf("assets", "res", "font")) {
                File(gameRoot, name).copyRecursively(File(sandbox, name))
            }
            File(sandbox, "mods/units").mkdirs()
            val ordinary = writeMod(gameRoot, File(work, "ordinary.rwmod"), "rwjsExpansionOrdinary")
            val expansion = writeMod(gameRoot,
                File(sandbox, "expansions/runtime-game/${"f".repeat(64)}/mods/exclusive.rwmod"),
                "rwjsExpansionExclusive")
            val patched = File(work, "game-lib-runtime-test.jar")
            patchProductionScanHooks(patched)
            val classpath = listOf(patched.canonicalPath) + requireNotNull(
                System.getProperty("rwpp.heap.testWorkerClasspath"),
            ).split(File.pathSeparator).map { File(it).absoluteFile }
                .filterNot { it.name == "game-lib.jar" }.map { it.path }
            val output = File(sandbox, "result.txt")
            val log = File(sandbox, "engine.log")
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx2048m", "-Xss16m", "-Dfile.encoding=UTF-8",
                "-Djava.library.path=${gameRoot.path}", "-Dorg.lwjgl.librarypath=${gameRoot.path}",
                "-cp", classpath.joinToString(File.pathSeparator), DesktopExpansionRuntimeWorker::class.java.name,
                gameRoot.path, ordinary.path, expansion.path, output.path,
            ).directory(sandbox).redirectErrorStream(true).redirectOutput(log).start()
            try {
                if (!process.waitFor(8, TimeUnit.MINUTES)) fail("真实扩展加载超时：\n${log.readText().takeLast(8000)}")
                if (process.exitValue() != 0 || !output.isFile) {
                    fail("真实扩展加载失败（退出码 ${process.exitValue()}）：\n${log.readText().takeLast(12000)}")
                }
                val report = output.readText()
                assertTrue(report.contains("PASS normal -> expansion -> rescan -> normal"), report)
                File(reportRoot, "result.txt").writeText(report)
                println(report)
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                if (log.isFile) log.copyTo(File(reportRoot, "engine.log"), overwrite = true)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun writeMod(root: File, archive: File, name: String): File {
        archive.parentFile.mkdirs()
        val source = File(root, "assets/units/scout")
        val ini = File(source, "scout.ini").readText()
            .replaceFirst(Regex("(?m)^name\\s*[:=][^\\r\\n]*"), "name: $name")
        check(ini.contains("name: $name")) { "原版 scout 夹具结构变化" }
        ZipOutputStream(archive.outputStream()).use { zip ->
            val entries = mapOf(
                "mod-info.txt" to "[mod]\ntitle: $name\ndescription: Native expansion isolation fixture\n".toByteArray(),
                "unit.ini" to ini.toByteArray(),
                "base.png" to File(source, "base.png").readBytes(),
                "base_dead.png" to File(source, "base_dead.png").readBytes(),
            )
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return archive.canonicalFile
    }

    /** Patch only this test's copy; retain the real composition/order used by Builder. */
    private fun patchProductionScanHooks(destination: File) {
        val original = File("../lib/game-lib.jar").canonicalFile
        val oldIncludes = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes += GameLibraries.`game-lib`
            GameLibraries.`game-lib`.load(original)
            val target = "com.corrodinggames.rts.gameFramework.i.a"
            val hooks = "io.github.rwpp.desktop.impl.inject.ModManagerInject."
            InjectApi.injectMethod(target, false, "e", "()V", InjectMode.InsertBefore,
                hooks + "preserveSelectionDuringRecovery", false, PathType.Path)
            InjectApi.injectMethod(target, true, "k", "()Ljava/util/ArrayList;", InjectMode.InsertBefore,
                hooks + "invalidateChangedSourcesBeforeScan", true, PathType.Path)
            InjectApi.injectMethod(target, true, "k", "()Ljava/util/ArrayList;", InjectMode.InsertAfter,
                hooks + "applyReloadSelectionAfterScan", true, PathType.Path)
            // Javassist is an implementation dependency of core-api; keep it out of this test's API.
            val tree: Any = GameLibraries.`game-lib`.classTree
            val pool = tree.javaClass.getMethod("getDefPool").invoke(tree)
            val ct = pool.javaClass.getMethod("get", String::class.java).invoke(pool, target)
            val bytes = Class.forName("javassist.CtClass").getMethod("toBytecode").invoke(ct) as ByteArray
            ZipFile(original).use { source ->
                ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                    source.entries().asSequence().forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            if (entry.name == target.replace('.', '/') + ".class") zip.write(bytes)
                            else source.getInputStream(entry).use { it.copyTo(zip) }
                        }
                        zip.closeEntry()
                    }
                }
            }
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(oldIncludes)
        }
    }
}

/** Every engine/GL call stays on the loader's owner thread in a disposable JVM. */
internal object DesktopExpansionRuntimeWorker {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 4)
            logger = LoggerFactory.getLogger("DesktopExpansionRuntimeWorker")
            val source = File(args[1]).canonicalFile
            val expansion = File(args[2]).canonicalFile
            val ordinary = File("mods/units/ordinary.rwmod").canonicalFile
            ContentProfiles.configureRoot(File("expansions").canonicalFile)
            val report = StringBuilder()
            EngineModLoader(File(args[0])).use { loader ->
                loader.loadBaseline()
                source.copyTo(ordinary)
                val normal = ContentProfile.Normal(mapOf(ordinary.path to true))
                ContentProfiles.stage(normal)
                reloadAndCheck("normal", "rwjsExpansionOrdinary", ordinary, "rwjsExpansionExclusive", report)
                ContentProfiles.stage(ContentProfile.Expansion("runtime-game", "f".repeat(64),
                    listOf(ContentModSource("exclusive", expansion))))
                reloadAndCheck("expansion", "rwjsExpansionExclusive", expansion, "rwjsExpansionOrdinary", report)
                // Exercise the persistent mode on a second native scan, not just a one-time toggle.
                reloadAndCheck("rescan", "rwjsExpansionExclusive", expansion, "rwjsExpansionOrdinary", report)
                ContentProfiles.stage(normal)
                reloadAndCheck("restored", "rwjsExpansionOrdinary", ordinary, "rwjsExpansionExclusive", report)
                ContentProfiles.finishNormal()
                check(File("mods/units").listFiles()!!.map { it.name } == listOf("ordinary.rwmod")) {
                    "扩展文件进入了普通模组目录"
                }
                check(expansion.isFile) { "恢复普通模式不应删除扩展包缓存" }
                report.appendLine("PASS normal -> expansion -> rescan -> normal")
                report.appendLine("Native source: ${expansion.path}")
                report.appendLine("Real OpenGL/OpenAL; full native unit parsing and texture allocation; isolated process.")
            }
            File(args[3]).writeText(report.toString())
            exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun reloadAndCheck(stage: String, expected: String, file: File, excluded: String, report: StringBuilder) {
        val manager = GameEngine.B().bZ
        val nativeListField = EngineModManager::class.java.getDeclaredField("e").apply { isAccessible = true }
        val returned = manager.k()
        check(returned === nativeListField.get(manager)) {
            "$stage: 扫描返回了登记外部来源前的过期列表"
        }
        if (ContentProfiles.expansionActive) {
            check(returned.filterIsInstance<EngineMod>().any { File(it.p).canonicalFile == file.canonicalFile }) {
                "$stage: 扫描返回列表遗漏独立扩展来源"
            }
        }
        manager.a(false, false)
        io.github.rwpp.desktop.impl.DesktopModResourceCache.sweep()
        val units = synchronized(UnitDefinition.c) { UnitDefinition.c.filterIsInstance<UnitDefinition>() }
        val selected = units.singleOrNull { it.M == expected }
            ?: error("$stage: 原版未解析预期单位 $expected；已加载：${units.filter { it.J != null }.map { it.M }}")
        check(units.none { it.M == excluded }) { "$stage: 其他内容来源仍然加载了 $excluded" }
        @Suppress("UNCHECKED_CAST") val mods = (nativeListField.get(manager) as List<EngineMod>).toList()
        if (stage == "restored") {
            check(mods.none { it.e?.startsWith("rwjs-expansion:") == true }) {
                "恢复普通模式后仍持有历史扩展登记项"
            }
        }
        val enabled = mods.filter { !it.f }
        check(enabled.size == 1 && selected.J === enabled.single()) {
            "$stage: 实际原版启用集合不是唯一来源：${enabled.map { it.p }}"
        }
        val mod = enabled.single()
        // Native ordinary scans use /SD/mods/... aliases; resolve them with the engine's own IO.
        // Expansion registration must still retain the exact independent absolute archive source.
        val physical = File(EngineFiles.e(mod.p)).canonicalFile
        check(physical == file.canonicalFile) { "$stage: 原版加载来源不匹配：${mod.p} -> $physical" }
        if (ContentProfiles.expansionActive) {
            check(File(mod.p).isAbsolute && File(mod.p).canonicalFile == file.canonicalFile) {
                "$stage: 扩展来源必须保留独立绝对路径：${mod.p}"
            }
        }
        check(!mod.C && mod.R == null && mod.V.isEmpty()) { "$stage: 原版模组报错：${mod.R}, ${mod.V}" }
        check(mod.G > 0) { "$stage: 未分配真实单位贴图" }
        val liveImages = (UnitLoader.g as Map<*, *>).values
            .filterIsInstance<com.corrodinggames.rts.java.s>().count { it.C()?.isDestroyed == false }
        check(liveImages > 0) { "$stage: 无存活 OpenGL 贴图" }
        report.appendLine("$stage: unit=$expected; source=${mod.p}; physical=$physical; enabled=${enabled.size}; textureBytes=${mod.G}; liveImages=$liveImages")
    }
}
