/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.save

import io.github.rwpp.saveDir
import java.io.File

/**
 * 磁盘上的对局存档（`.rwsave`）。
 */
data class GameSave(
    val file: File,
) {
    /** 存档名（不含 `.rwsave` 后缀）。 */
    val saveName: String get() = file.nameWithoutExtension

    /** 带后缀的存档文件名（原版读档接口需要带后缀、不带 `saves/` 前缀）。 */
    val fileName: String get() = file.name

    fun displayName(): String = saveName.replace('_', ' ')
}

/**
 * 扫描 [saveDir] 下的 `.rwsave` 存档；`.tmp` 写入中间文件因后缀不同被自然过滤。
 * 按修改时间倒序（最新的存档排在最前）。
 */
fun scanGameSaves(root: File = File(saveDir)): List<GameSave> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension.equals("rwsave", ignoreCase = true) }
        .map { GameSave(it) }
        .sortedByDescending { it.file.lastModified() }
        .toList()
}

/**
 * 在 [saveDir] 白名单内安全删除 `.rwsave` 及其伴生 `.map` 文件（与原版删除行为一致）。
 *
 * @return 存档文件是否已成功删除（伴生文件缺失不视为失败）
 */
fun deleteGameSaveSafely(
    save: File,
    allowedRoots: List<File> = listOf(File(saveDir)),
): Boolean {
    return runCatching {
        if (!save.exists()) return@runCatching false
        if (!save.extension.equals("rwsave", ignoreCase = true)) return@runCatching false

        val target = save.canonicalFile
        val isInAllowedRoot = allowedRoots.any { root ->
            val allowedRoot = root.canonicalFile
            target != allowedRoot && target.isInside(allowedRoot)
        }
        if (!isInAllowedRoot) return@runCatching false

        val deleted = target.delete()
        if (deleted) {
            // 原版删除存档时会连带删除 `<存档文件名>.map` 伴生文件
            val companion = File(target.parentFile, target.name + ".map")
            if (companion.exists()) {
                val companionCanonical = companion.canonicalFile
                val companionAllowed = allowedRoots.any { root ->
                    val allowedRoot = root.canonicalFile
                    companionCanonical != allowedRoot && companionCanonical.isInside(allowedRoot)
                }
                if (companionAllowed) companion.delete()
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
