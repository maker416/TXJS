/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File

/** File mutations only publish a revision; engine caches are invalidated later on their owning thread. */
object ModSourceChanges {
    private val revisions = mutableMapOf<String, Long>()

    fun sourceChanged(source: File) {
        val canonical = source.canonicalFile
        synchronized(revisions) {
            revisions[canonical.path] = (revisions[canonical.path] ?: 0L) + 1L
        }
        ModPlaytimeHashCache.sourceChanged(canonical)
    }

    internal fun revision(canonicalPath: String): Long =
        synchronized(revisions) { revisions[canonicalPath] ?: 0L }
}

/** Tracks the sources seen by one engine scanner, including replacements that preserve file metadata. */
class ModSourceTracker {
    private data class SourceState(val exists: Boolean, val length: Long, val modified: Long, val revision: Long)
    private val baseline = mutableMapOf<String, SourceState>()

    @Synchronized
    fun changed(sources: Collection<File>): Set<String> = buildSet {
        sources.forEach { source ->
            val canonical = source.canonicalFile
            val current = state(canonical)
            val previous = baseline.put(canonical.path, current)
            // A newly observed existing source might already have an engine ZIP handle from startup.
            if (previous != current && (previous != null || current.exists)) add(canonical.path)
        }
    }

    /** Register newly scanned sources without concealing a mutation of an already tracked source. */
    @Synchronized
    fun remember(sources: Collection<File>) {
        sources.forEach { source ->
            val canonical = source.canonicalFile
            baseline.putIfAbsent(canonical.path, state(canonical))
        }
    }

    private fun state(source: File): SourceState {
        // Read the revision first so a concurrent explicit mutation remains visible to the next scan.
        val revision = ModSourceChanges.revision(source.path)
        return SourceState(source.exists(), source.length(), source.lastModified(), revision)
    }
}

private val archiveSuffix = Regex("\\.rwmod(?=$|[/\\\\])", RegexOption.IGNORE_CASE)

/** Return the archive container, dropping any virtual path inside it. */
fun archivePath(path: String): String? = archiveSuffix.find(path)?.let { path.substring(0, it.range.last + 1) }

fun archiveFile(path: String): File? = archivePath(path)?.let { File(it.replace('\\', '/')) }

/** Match a resource path or an engine cache key while keeping source names on path boundaries. */
fun pathBelongsToSource(path: String, source: String): Boolean {
    val normalizedPath = path.replace('\\', '/')
    val normalizedSource = source.replace('\\', '/').trimEnd('/')
    if (normalizedSource.isEmpty()) return false
    var start = normalizedPath.indexOf(normalizedSource)
    while (start >= 0) {
        val end = start + normalizedSource.length
        val before = start == 0 || normalizedPath[start - 1].isCacheBoundary()
        val after = end == normalizedPath.length || normalizedPath[end] == '/' || normalizedPath[end].isCacheBoundary()
        if (before && after) return true
        start = normalizedPath.indexOf(normalizedSource, start + 1)
    }
    return false
}

private fun Char.isCacheBoundary(): Boolean = isWhitespace() || this in ":|,;([{<>=!\"')]}"
