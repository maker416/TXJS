/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.diagnostics.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.*

class RuntimeLogsTest {
    private val root = Files.createTempDirectory("rwjs-daily-logs-test-").toFile()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    @Test fun restartingAppAppendsAndMidnightRotatesWithoutRestart() {
        var day = LocalDate.of(2026, 10, 25)
        DailyLogOutputStream(root) { day }.use { it.write("第一次启动\n".toByteArray()) }
        DailyLogOutputStream(root) { day }.use {
            it.write("second run\n".toByteArray())
            day = day.plusDays(1)
            it.write("next day\n".toByteArray())
        }
        assertEquals("第一次启动\nsecond run\n", File(root,"rwjs-2026-10-25.log").readText())
        assertEquals("next day\n", File(root,"rwjs-2026-10-26.log").readText())
    }

    @Test fun retainsTodayPlusSixPreviousDaysAndLeavesUnrelatedFilesAlone() {
        val day = LocalDate.of(2026, 10, 25)
        repeat(10) { File(root, "rwjs-${day.minusDays(it.toLong())}.log").writeText("$it") }
        File(root, "keep.txt").writeText("unrelated")
        DailyLogOutputStream(root) { day }.use { stream ->
            val snapshot = stream.recentFiles()
            assertEquals(7, snapshot.size)
            assertTrue(File(root,"keep.txt").exists())
            RuntimeLogs.archive(snapshot).use { archive ->
                ZipFile(archive.file).use { zip ->
                    assertEquals(7, zip.size())
                    assertEquals("0",zip.getInputStream(zip.getEntry("rwjs-$day.log")).reader().readText())
                }
            }
        }
    }

    @Test fun snapshotDoesNotIncludeBytesWrittenAfterCaptureAndDeletesTemporaryArchive() {
        DailyLogOutputStream(root).use { stream ->
            stream.write("before".toByteArray())
            val snapshot = stream.recentFiles()
            stream.write("after".toByteArray())
            val archive = RuntimeLogs.archive(snapshot)
            ZipFile(archive.file).use { zip ->
                assertEquals("before",zip.getInputStream(zip.entries().nextElement()).reader().readText())
            }
            archive.close()
            assertFalse(archive.file.exists())
        }
    }

    @Test fun oversizedSnapshotFailsBeforeAllocatingOrCompressing() {
        val file=File(root,"rwjs-${LocalDate.now()}.log")
        RandomAccessFile(file,"rw").use { it.setLength(RuntimeLogs.MAX_RAW_BYTES+1) }
        assertFailsWith<IllegalStateException> { RuntimeLogs.archive(listOf(LogFileSnapshot(file,file.length()))) }
        assertEquals(1,root.listFiles()!!.size)
    }

    @Test fun upgradesRecentPerRunLogsIntoDailyFileWithoutLosingContents() {
        val old = File(root,"rwjs-20261025-120000-1234-test.log").apply { writeText("old stack trace\n") }
        DailyLogOutputStream(root).use { stream ->
            assertFalse(old.exists())
            val daily = stream.recentFiles().single().file.readText()
            assertTrue(daily.contains("old stack trace"))
        }
    }
}
