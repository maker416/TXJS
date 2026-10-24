/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.game.mod

import io.github.rwpp.game.GameStartSafety
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** A failed unit table must never be reported as a successful reload. No retained engine/cause graph. */
class ModLoadFailure(val memoryExhausted: Boolean, message: String) : IllegalStateException(message)

/** Escapes the engine's RuntimeException catch without allocating a stack while memory is scarce. */
private object StopModLoading : Error("Resource load stopped", null, false, false)

data class ModLoadJournalEntry(val attempt: String, val session: String, val stage: String, val mod: String, val failure: String)

/** Small, atomic, engine-independent evidence. An unfinished load is not necessarily an OOM. */
class ModLoadJournal(private val file: File) {
    @Synchronized fun read(): ModLoadJournalEntry? {
        if (!file.isFile || file.length() > 32 * 1024) return null
        return runCatching {
            val p = Properties().apply { file.inputStream().use(::load) }
            ModLoadJournalEntry(p.getProperty("attempt"), p.getProperty("session"),
                p.getProperty("stage", ""), p.getProperty("mod", ""), p.getProperty("failure", ""))
        }.getOrNull()
    }
    @Synchronized fun write(entry: ModLoadJournalEntry) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val p = Properties().apply {
            setProperty("attempt", entry.attempt); setProperty("session", entry.session)
            setProperty("stage", entry.stage.take(80)); setProperty("mod", entry.mod.take(240))
            setProperty("failure", entry.failure.take(80))
        }
        tmp.outputStream().use { p.store(it, null); it.fd.sync() }
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    @Synchronized fun complete(attempt: String) {
        if (read()?.attempt == attempt && file.exists() && !file.delete()) throw java.io.IOException("Cannot clear load journal")
    }
}

/** Actual Java heap headroom, never compressed archive size or physical RAM. */
fun modLoadReserveBytes(maxHeap: Long): Long = (maxHeap / 10).coerceIn(32L shl 20, 128L shl 20)

/** Recovery lists containers only. It deliberately does not inflate archives or initialize the engine. */
fun recoveryModFiles(roots: List<File>): List<File> = roots.flatMap { root ->
    root.listFiles().orEmpty().filter {
        !it.name.startsWith('.') && (it.isDirectory || it.extension.equals("rwmod", true))
    }.toList()
}.distinctBy { it.canonicalPath }.sortedBy { it.name.lowercase() }

object ModLoadSafety {
    private val session = UUID.randomUUID().toString()
    @Volatile private var journal: ModLoadJournal? = null
    @Volatile private var active: ModLoadJournalEntry? = null
    @Volatile private var recoverySelection: Map<String, Boolean>? = null
    @Volatile private var peakUsed = 0L
    val loading: Boolean get() = active != null
    val suppressSelectionSave: Boolean get() = recoverySelection != null || GameStartSafety.requiresRestart
    val temporaryRecovery: Boolean get() = recoverySelection != null

    fun configure(file: File) { if (journal == null) journal = ModLoadJournal(file) }
    fun previousFailure(): ModLoadJournalEntry? = journal?.read()?.takeIf { it.session != session || it.failure.isNotBlank() }
    fun useRecoverySelection(selection: Map<String, Boolean>) { recoverySelection = selection.toMap() }
    fun recoveryEnabled(paths: Iterable<String>): Boolean? = recoverySelection?.let { resolveModEnabledByFileName(paths, it) }
    /** Only an explicit Apply action may replace the user's saved selection. */
    fun commitRecoverySelection() {
        recoverySelection = null
        // Explicit Apply is followed by a new journaled reload. Do not clear earlier evidence here.
    }

    fun checkpoint(mod: String? = null) {
        if (GameStartSafety.requiresRestart) throw StopModLoading
        val current = active ?: return
        if (mod != null && current.mod != mod) {
            val updated = current.copy(mod = mod.take(240))
            active = updated
            journal?.write(updated)
        }
        val runtime = Runtime.getRuntime()
        peakUsed = maxOf(peakUsed, runtime.totalMemory() - runtime.freeMemory())
        val reserve = modLoadReserveBytes(runtime.maxMemory())
        fun remaining() = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (remaining() >= reserve) return
        // Every low-water crossing can be temporary parser garbage. A once-per-load GC would
        // falsely reject small live registries during long or repeated parses.
        System.gc()
        if (remaining() < reserve) abortMemory()
    }

    fun onEngineError(error: Throwable) {
        var cause: Throwable? = error
        repeat(16) {
            if (cause is OutOfMemoryError || cause === StopModLoading) abortMemory()
            val next = cause?.cause
            if (next === cause) return
            cause = next ?: return
        }
    }

    fun abortMemory(): Nothing {
        GameStartSafety.markMemoryExhausted()
        throw StopModLoading
    }

    /** Transfer/setup failures outside parsing must also leave evidence for the next process. */
    fun recordMemoryFailure(stage: String) {
        GameStartSafety.markMemoryExhausted()
        runCatching {
            val entry = active ?: ModLoadJournalEntry(UUID.randomUUID().toString(), session, stage, "", "")
            journal?.write(entry.copy(failure = "memory"))
        }
    }

    fun <T> run(stage: String, block: () -> T): T {
        val entry = ModLoadJournalEntry(UUID.randomUUID().toString(), session, stage, "", "")
        synchronized(this) {
            GameStartSafety.requireCanReload()
            check(active == null) { "Another resource load is still running" }
            active = entry
        }
        peakUsed = 0
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        reportHeap(stage, "begin", before)
        try {
            journal?.write(entry)
            checkpoint()
            val value = block()
            checkpoint()
            if (temporaryRecovery) {
                // The saved original selection is still unchanged and may still crash on next launch.
                journal?.write((active ?: entry).copy(failure = "temporary-recovery"))
            } else journal?.complete(entry.attempt)
            reportHeap(stage, "success", before)
            return value
        } catch (error: Throwable) {
            if (error is ReloadAbortToVanilla) {
                journal?.complete(entry.attempt)
                throw error
            }
            val memory = error is OutOfMemoryError || error === StopModLoading ||
                error is ModLoadFailure && error.memoryExhausted || GameStartSafety.memoryExhausted
            if (memory) GameStartSafety.markMemoryExhausted() else GameStartSafety.markLoadFailed()
            reportHeap(stage, if (memory) "memory-stop" else "failed", before)
            // This evidence is best effort; the prewritten unfinished marker already protects next startup.
            runCatching { journal?.write((active ?: entry).copy(failure = if (memory) "memory" else "load")) }
            if (!memory && error !is Exception) throw error
            throw ModLoadFailure(memory, if (memory) "内存余量不足，已停止资源加载。请重启并减少启用的模组。"
                else "资源加载失败，请重启后调整模组。${error.message.orEmpty().take(160)}")
        } finally { active = null }
    }

    private fun reportHeap(stage: String, outcome: String, before: Long) {
        // Fixed-size numeric evidence; no exception graph or per-unit lists on the failure path.
        runCatching {
            val runtime = Runtime.getRuntime()
            io.github.rwpp.logger.info("[MODMEM] stage={} outcome={} before={} peak={} used={} max={}",
                stage, outcome, before, peakUsed, runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory())
        }
    }
}
