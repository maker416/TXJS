/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.io.AtomicFileWrite
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicFileWriteTest {
    @Test
    fun failedStagingPreservesExistingContentAndRemovesTemporaryFile() {
        val directory = Files.createTempDirectory("atomic-config-failure-").toFile()
        try {
            val target = File(directory, "configuration.toml")
            val original = "旧配置：完整记录🙂\n".repeat(128)
            target.writeText(original, Charsets.UTF_8)

            val failure = assertFailsWith<IOException> {
                AtomicFileWrite.write(target) { output ->
                    output.write("部分新内容，不能覆盖旧文件".toByteArray(Charsets.UTF_8))
                    throw IOException("模拟写入中断")
                }
            }

            assertEquals("模拟写入中断", failure.message)
            assertEquals(original, target.readText(Charsets.UTF_8))
            assertEquals(setOf(target.name), directory.listFiles()!!.map { it.name }.toSet(), "失败暂存不得留下.tmp文件")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritersAndReaderOnlyObserveCompleteUtf8Versions() {
        val directory = Files.createTempDirectory("atomic-config-concurrent-").toFile()
        val executor = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val saveLock = Any()
        try {
            val target = File(directory, "configuration.toml")
            val initial = "原始完整配置🙂\n".repeat(4_000)
            val versions = (1..4).map { "完整版本$it：铁锈战争🙂\n".repeat(4_000 + it * 127) }
            val allowed = (versions + initial).toSet()
            val observed = ConcurrentHashMap.newKeySet<String>()
            val reads = AtomicInteger()
            target.writeText(initial, Charsets.UTF_8)

            val reader = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                do {
                    val contents = readWithSharedDelete(target)
                    assertTrue(contents in allowed, "读取到了半写入、截断或混合的UTF-8配置")
                    observed += contents
                    reads.incrementAndGet()
                    readerStarted.countDown()
                    Thread.yield()
                } while (!finished.get())
                val finalContents = readWithSharedDelete(target)
                assertTrue(finalContents in versions)
                observed += finalContents
            }
            val writers = versions.map { contents ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    check(readerStarted.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    // 与真实ConfigIOImpl相同：多个保存请求串行提交，读取仍然并行。
                    repeat(24) {
                        synchronized(saveLock) { writeUntilAvailable(target, contents, deadline) }
                    }
                }
            }
            start.countDown()
            try {
                writers.forEach { it.get(20, TimeUnit.SECONDS) }
            } finally {
                finished.set(true)
            }
            reader.get(5, TimeUnit.SECONDS)

            assertTrue(reads.get() > 0, "并发读取必须实际执行")
            assertTrue(observed.any { it in versions }, "必须读到已替换的新版本")
            assertTrue(readWithSharedDelete(target) in versions)
            assertEquals(setOf(target.name), directory.listFiles()!!.map { it.name }.toSet(), "成功写入不得留下.tmp文件")
        } finally {
            finished.set(true)
            start.countDown()
            readerStarted.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    private fun readWithSharedDelete(target: File): String {
        val output = ByteArrayOutputStream()
        FileChannel.open(target.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(8_192)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count > 0) output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeUntilAvailable(target: File, contents: String, deadline: Long) {
        while (true) {
            try {
                AtomicFileWrite.text(target, contents)
                return
            } catch (failure: AccessDeniedException) {
                // Windows短暂文件锁可以拒绝替换；生产保留旧文件并在下次保存重试。
                // 这里只重试这一种OS锁错误，任何内容断裂、其他IO错误或超时仍失败。
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(2)
            }
        }
    }
}
