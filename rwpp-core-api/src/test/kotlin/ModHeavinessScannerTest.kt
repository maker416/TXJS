/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModHeavinessScanner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModHeavinessScannerTest {

    /** 重型样本：深层 select( 嵌套 + memory. 引用 + 大量 decal 节（对标安绒宁静的写法）。 */
    private val heavyIni = buildString {
        appendLine("[core]")
        appendLine("name:heavy")
        repeat(400) { appendLine("@memory 变量$it:float[]") }
        appendLine("copyFrom:a.template,b.template")
        append("[hiddenAction_x]\nsetUnitMemory:预备[index]=")
        // 800 层 select( 嵌套
        repeat(800) { append("select(memory.随机[$it]==1,\"甲\",") }
        append("\"乙\"")
        repeat(800) { append(")") }
        appendLine()
        repeat(200) { i ->
            appendLine("[decal_贴图$i]")
            appendLine("image:test.png")
            appendLine("isVisible:if memory.字[$i]>0")
        }
    }

    /** 轻型样本：纯键值对 + 大量普通节（对标铁锈酒馆的写法）。 */
    private val lightIni = buildString {
        repeat(300) { i ->
            appendLine("[action_动作$i]")
            appendLine("text:普通动作$i")
            appendLine("price:50")
            appendLine("buildSpeed:10s")
        }
    }

    private fun writeRwmod(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun heavyModScoresAboveThreshold() {
        val dir = createTempDirectory().toFile()
        val mod = File(dir, "heavy.rwmod")
        writeRwmod(mod, mapOf("pack/units/heavy.ini" to heavyIni))

        val result = ModHeavinessScanner.scan(mod)

        assertEquals(800, result.selects)
        // 800 处 memory.随机 + 200 处 memory.字（@memory 声明无点号不计）
        assertEquals(1000, result.memoryRefs)
        assertEquals(200, result.decals)
        assertTrue(ModHeavinessScanner.isHeavy(result), "score=${result.score}")
    }

    @Test
    fun lightModScoresBelowThreshold() {
        val dir = createTempDirectory().toFile()
        val mod = File(dir, "light.rwmod")
        writeRwmod(mod, mapOf("pack/units/light.ini" to lightIni))

        val result = ModHeavinessScanner.scan(mod)

        assertEquals(0, result.selects)
        assertEquals(0, result.decals)
        assertFalse(ModHeavinessScanner.isHeavy(result), "score=${result.score}")
    }

    @Test
    fun heavyOutscoresLightByWideMargin() {
        val dir = createTempDirectory().toFile()
        val heavy = File(dir, "heavy.rwmod")
        val light = File(dir, "light.rwmod")
        writeRwmod(heavy, mapOf("heavy.ini" to heavyIni))
        writeRwmod(light, mapOf("light.ini" to lightIni))

        val heavyScore = ModHeavinessScanner.scan(heavy).score
        val lightScore = ModHeavinessScanner.scan(light).score
        assertTrue(heavyScore > lightScore * 10, "heavy=$heavyScore light=$lightScore")
    }

    @Test
    fun folderModIsScannedRecursively() {
        val dir = createTempDirectory().toFile()
        val sub = File(dir, "pack/units").apply { mkdirs() }
        File(sub, "a.ini").writeText(heavyIni, Charsets.UTF_8)
        File(sub, "b.ini").writeText(lightIni, Charsets.UTF_8)
        File(sub, "c.png").writeBytes(byteArrayOf(1, 2, 3))

        val result = ModHeavinessScanner.scan(dir)

        assertEquals(800, result.selects)
        assertEquals(200, result.decals)
        assertTrue(ModHeavinessScanner.isHeavy(result))
    }

    @Test
    fun singleIniFileIsScanned() {
        val dir = createTempDirectory().toFile()
        val ini = File(dir, "unit.ini")
        ini.writeText(heavyIni, Charsets.UTF_8)

        val result = ModHeavinessScanner.scan(ini)
        assertEquals(800, result.selects)
    }

    @Test
    fun missingFileScoresZero() {
        val result = ModHeavinessScanner.scan(File("nonexistent-mod-xyz.rwmod"))
        assertEquals(0.0, result.score)
    }

    @Test
    fun repeatedScanUsesCache() {
        val dir = createTempDirectory().toFile()
        val mod = File(dir, "cached.rwmod")
        writeRwmod(mod, mapOf("cached.ini" to lightIni))

        val first = ModHeavinessScanner.scan(mod)
        val second = ModHeavinessScanner.scan(mod)
        assertEquals(first, second)

        // 内容变化（大小与 mtime 均变）后缓存失效
        Thread.sleep(5)
        writeRwmod(mod, mapOf("cached.ini" to heavyIni))
        mod.setLastModified(System.currentTimeMillis() + 1000)
        val third = ModHeavinessScanner.scan(mod)
        assertEquals(800, third.selects)
    }
}
