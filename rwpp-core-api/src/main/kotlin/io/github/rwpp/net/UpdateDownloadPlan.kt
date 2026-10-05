/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/** ZIP 分卷按序拼接后解压；单 APK/EXE 保持兼容。 */
data class UpdateDownloadPlan(
    val partUrls: List<String>,
    val sha256Url: String? = null,
    val isArchive: Boolean = partUrls.size > 1,
    val partSizes: List<Long> = emptyList(),
)

private val splitPart = Regex("""^(.+)\.zip\.(\d+)$""", RegexOption.IGNORE_CASE)

private fun LatestVersionProfile.splitPlan(android: Boolean): UpdateDownloadPlan? {
    val groups = assets.mapNotNull { asset ->
        val match = splitPart.matchEntire(asset.name) ?: return@mapNotNull null
        val index = match.groupValues[2].toIntOrNull() ?: -1
        Triple(match.groupValues[1], index, asset)
    }.groupBy { it.first.lowercase() }
    return groups.values.filter { group ->
        val prefix = group.first().first
        if (android) prefix.contains("android", true) || prefix.contains("apk", true)
        else !prefix.contains("android", true) && !prefix.contains("apk", true)
    }.sortedWith(compareByDescending<List<Triple<String, Int, ReleaseAsset>>> {
        it.first().first.contains(if (android) "android" else "setup", true)
    }.thenByDescending { it.size }).firstNotNullOfOrNull { group ->
        val sorted = group.sortedBy { it.second }
        val checksum = assets.firstOrNull { it.name.equals("${group.first().first}.zip.sha256", true) && it.downloadUrl.isNotBlank() }
        val minimum = if (android) 1 else 2
        if (sorted.size < minimum || sorted.map { it.second } != (1..sorted.size).toList() ||
            (android && checksum == null) || sorted.any { it.third.downloadUrl.isBlank() }) null
        else UpdateDownloadPlan(sorted.map { it.third.downloadUrl }, checksum?.downloadUrl,
            isArchive = true, partSizes = sorted.map { it.third.size })
    }
}

private fun LatestVersionProfile.rawPlan(extension: String): UpdateDownloadPlan? {
    val asset = assets.firstOrNull { it.name.endsWith(extension, true) && it.downloadUrl.isNotBlank() } ?: return null
    val checksum = assets.firstOrNull { it.name.equals("${asset.name}.sha256", true) && it.downloadUrl.isNotBlank() }
    return UpdateDownloadPlan(listOf(asset.downloadUrl), checksum?.downloadUrl, false, listOf(asset.size))
}

fun LatestVersionProfile.resolveDesktopUpdatePlan(): UpdateDownloadPlan? = splitPlan(false) ?: rawPlan(".exe")
fun LatestVersionProfile.resolveAndroidUpdatePlan(): UpdateDownloadPlan? = splitPlan(true) ?: rawPlan(".apk")
