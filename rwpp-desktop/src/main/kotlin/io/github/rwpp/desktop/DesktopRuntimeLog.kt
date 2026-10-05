/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 引擎、SLF4J、Swing/协程异常写进同一次运行日志，保留最近 10 次运行。 */
object DesktopRuntimeLog {
    private const val RETAINED_RUNS = 10

    fun initialize() {
        if (System.getProperty("org.slf4j.simpleLogger.showDateTime") == null) {
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
        }
        if (System.getProperty("org.slf4j.simpleLogger.dateTimeFormat") == null) {
            System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS")
        }
        val consoleOut = System.out
        val consoleErr = System.err
        try {
            val preferred = File(System.getProperty("user.dir"), "logs")
            val session = try {
                openSession(preferred)
            } catch (_: Exception) {
                val local = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                openSession(File(local, "RWJS/logs"))
            }
            val fileStream = session.outputStream()
            val fileLock = Any()
            System.setOut(PrintStream(tee(consoleOut, fileStream, fileLock), true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(tee(consoleErr, fileStream, fileLock), true, StandardCharsets.UTF_8))
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                System.err.println("[UNCAUGHT] time=${LocalDateTime.now()} thread=${thread.name} id=${thread.threadId()} error=$error")
                error.printStackTrace(System.err)
                System.err.flush()
                previous?.uncaughtException(thread, error)
            }
            Runtime.getRuntime().addShutdownHook(Thread({
                System.err.println("[EXIT] JVM shutdown at ${LocalDateTime.now()}")
                System.out.flush()
                System.err.flush()
            }, "rwjs-log-shutdown"))
            System.out.println("[START] time=${LocalDateTime.now()} pid=${ProcessHandle.current().pid()} log=${session.absolutePath}")
            System.out.println("[ENV] java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
        } catch (e: Exception) {
            consoleErr.println("[LOG] Failed to initialize persistent PC logging")
            e.printStackTrace(consoleErr)
        }
    }

    internal fun openSession(directory: File): File {
        Files.createDirectories(directory.toPath())
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val session = Files.createTempFile(directory.toPath(), "rwjs-$timestamp-${ProcessHandle.current().pid()}-", ".log").toFile()
        directory.listFiles { f -> f.isFile && f.name.startsWith("rwjs-") && f.extension == "log" }
            .orEmpty().filter { it != session }.sortedByDescending { it.lastModified() }
            .drop(RETAINED_RUNS - 1).forEach { it.delete() }
        return session
    }

    internal fun tee(console: OutputStream, file: OutputStream, lock: Any): OutputStream = object : OutputStream() {
        override fun write(value: Int) = synchronized(lock) { file.write(value); console.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(lock) {
            file.write(bytes, offset, length)
            console.write(bytes, offset, length)
        }
        override fun flush() = synchronized(lock) { file.flush(); console.flush() }
    }
}
