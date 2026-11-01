/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import io.github.rwpp.app.UpdateDownloadSession
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The distribution version is independent of both the client and the upstream engine. */
data class BrowserKernelVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<BrowserKernelVersion> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }
    override fun compareTo(other: BrowserKernelVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): BrowserKernelVersion? {
            if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) return null
            val parts = value.split('.').map { it.toIntOrNull() ?: return null }
            return BrowserKernelVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class BrowserKernelReleaseAsset(val name: String, val url: String)
data class BrowserKernelCandidate(val version: BrowserKernelVersion, val asset: BrowserKernelReleaseAsset)
data class BrowserKernelRelease(val tag: String, val assets: List<BrowserKernelReleaseAsset>) {
    fun candidates(requirement: BrowserKernelRequirement): List<BrowserKernelCandidate> {
        val m = requirement.manifest
        val prefix = "RWJS-Browser-${m.engine}-${m.platform}-${m.arch}-v"
        val candidates = assets.mapNotNull { asset ->
            if (!asset.name.startsWith(prefix) || !asset.name.endsWith(".json")) return@mapNotNull null
            val version = BrowserKernelVersion.parse(asset.name.removePrefix(prefix).removeSuffix(".json")) ?: return@mapNotNull null
            BrowserKernelCandidate(version, asset)
        }
        require(candidates.map { it.version }.distinct().size == candidates.size) { "Duplicate browser kernel version" }
        require(candidates.size <= 32) { "Too many browser kernel versions" }
        return candidates.sortedByDescending { it.version }
    }
    fun source(): BrowserKernelSource = BrowserKernelSource { _, name, _ ->
        val matching = assets.filter { it.name == name }
        if (matching.size != 1) throw IOException("Kernel asset is missing or duplicated: $name")
        matching.single().url.also { require(it.startsWith("https://")) { "Insecure kernel asset" } }
    }
}

data class BrowserKernelUpdate(val requirement: BrowserKernelRequirement?, val newerClientRequired: Boolean = false)

/** Resolve a compatible component from a frozen latest-Release snapshot before starting any download. */
class BrowserKernelUpdateResolver(private val client: OkHttpClient) {
    fun resolve(baseline: BrowserKernelRequirement, release: BrowserKernelRelease, session: UpdateDownloadSession,
        source: BrowserKernelSource = release.source()): BrowserKernelUpdate {
        var incompatible = false
        for (candidate in release.candidates(baseline)) {
            session.checkActive()
            if (candidate.version < requireNotNull(BrowserKernelVersion.parse(baseline.manifest.componentVersion))) continue
            val text = session.execute(client.newCall(Request.Builder().url(source.asset(release.tag, candidate.asset.name, session)).build())) { response ->
                if (!response.isSuccessful) throw IOException("Kernel manifest returned HTTP ${response.code}")
                readKernelMetadata(response.body?.byteStream() ?: throw IOException("Empty kernel manifest"))
            }
            val requirement = BrowserKernelRequirement.fromManifest(text, candidate.asset.name)
            require(requirement.manifest.componentVersion == candidate.version.toString()) { "Kernel filename/version mismatch" }
            val expected = baseline.manifest
            val actual = requirement.manifest
            if (actual.engine != expected.engine || actual.platform != expected.platform || actual.arch != expected.arch) throw IOException("Kernel platform mismatch")
            if (actual.version != expected.version || actual.format != expected.format) { incompatible = true; continue }
            // Resolve every volume now, so an incomplete release cannot replace a working installation.
            actual.parts.forEach { source.asset(release.tag, it.name, session) }
            return BrowserKernelUpdate(requirement, incompatible)
        }
        return BrowserKernelUpdate(null, incompatible)
    }
}

internal fun readKernelMetadata(input: java.io.InputStream): String = input.use {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = it.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 2 * 1024 * 1024) { "Kernel metadata too large" }
        output.write(buffer, 0, count)
    }
    output.toString("UTF-8")
}
