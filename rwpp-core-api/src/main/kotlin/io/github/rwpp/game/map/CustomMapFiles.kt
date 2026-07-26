/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.map

import io.github.rwpp.customMapDir
import java.io.File

private val mapPrefixRegex = Regex("""^\[.*?\]""")

/**
 * 自定义地图在磁盘上的条目（仅 `.tmx`，不含内置遭遇战）。
 */
data class CustomMapFile(
    val file: File,
) {
    val mapName: String get() = file.nameWithoutExtension

    fun displayName(): String = mapName.replace(mapPrefixRegex, "")

    fun thumbnailFile(): File =
        File(file.parentFile, file.nameWithoutExtension + "_map.png")
}

/**
 * 扫描 [customMapDir] 下的自定义地图；跳过运行时生成的 `generated_*.tmx`。
 */
fun scanCustomMapFiles(root: File = File(customMapDir)): List<CustomMapFile> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension.equals("tmx", ignoreCase = true) }
        .filter { !it.name.startsWith("generated_", ignoreCase = true) }
        .map { CustomMapFile(it) }
        .sortedBy { it.displayName().lowercase() }
        .toList()
}

/**
 * 在 [customMapDir] 白名单内安全删除 `.tmx` 及其伴生 `_map.png`。
 *
 * @return `.tmx` 是否已成功删除（缩略图缺失不视为失败）
 */
fun deleteCustomMapFilesSafely(
    tmx: File,
    allowedRoots: List<File> = listOf(File(customMapDir)),
): Boolean {
    return runCatching {
        if (!tmx.exists()) return@runCatching false
        if (!tmx.extension.equals("tmx", ignoreCase = true)) return@runCatching false

        val target = tmx.canonicalFile
        val isInAllowedRoot = allowedRoots.any { root ->
            val allowedRoot = root.canonicalFile
            target != allowedRoot && target.isInside(allowedRoot)
        }
        if (!isInAllowedRoot) return@runCatching false

        val deleted = target.delete()
        if (deleted) {
            val thumb = File(target.parentFile, target.nameWithoutExtension + "_map.png")
            if (thumb.exists()) {
                val thumbCanonical = thumb.canonicalFile
                val thumbAllowed = allowedRoots.any { root ->
                    val allowedRoot = root.canonicalFile
                    thumbCanonical != allowedRoot && thumbCanonical.isInside(allowedRoot)
                }
                if (thumbAllowed) thumb.delete()
            }
        }
        deleted
    }.getOrDefault(false)
}

private fun File.isInside(parent: File): Boolean {
    var current: File? = this
    while (current != null) {
        if (current == parent) return true
        current = current.parentFile
    }
    return false
}
