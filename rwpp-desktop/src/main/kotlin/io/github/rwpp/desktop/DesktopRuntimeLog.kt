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
import io.github.rwpp.diagnostics.RuntimeLogs
import java.time.LocalDateTime

/** 引擎、SLF4J、Swing/协程异常按天追加，保留包含当天在内的七天。 */
object DesktopRuntimeLog {

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
            val fileStream = try {
                RuntimeLogs.initialize(preferred)
            } catch (_: Exception) {
                val local = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                RuntimeLogs.initialize(File(local, "RWJS/logs"))
            }
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
            System.out.println("[START] time=${LocalDateTime.now()} pid=${ProcessHandle.current().pid()} logs=${fileStream.directory.absolutePath}")
            System.out.println("[ENV] java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
        } catch (e: Exception) {
            consoleErr.println("[LOG] Failed to initialize persistent PC logging")
            e.printStackTrace(consoleErr)
        }
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
