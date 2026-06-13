/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.modDir
import java.io.File

enum class ModSourceType {
    RwMod,
    Folder,
    Ini,
    Unknown
}

fun detectModSourceType(file: File): ModSourceType {
    return when {
        file.extension.equals("rwmod", ignoreCase = true) -> ModSourceType.RwMod
        file.isDirectory -> ModSourceType.Folder
        file.extension.equals("ini", ignoreCase = true) -> ModSourceType.Ini
        else -> ModSourceType.Unknown
    }
}

fun deleteModFileSafely(
    file: File,
    allowedRoots: List<File> = listOf(File(modDir), File("mods/units"), File("units"))
): Boolean {
    return runCatching {
        if (!file.exists()) return@runCatching false

        val target = file.canonicalFile
        val isInAllowedRoot = allowedRoots.any { root ->
            val allowedRoot = root.canonicalFile
            target != allowedRoot && target.isInside(allowedRoot)
        }

        if (!isInAllowedRoot) return@runCatching false

        if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
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

interface Mod {
    val id: Int
    val name: String
    val description: String
    val minVersion: String
    val errorMessage: String?
    var isEnabled: Boolean
    val path: String
    val sourceType: ModSourceType
        get() = detectModSourceType(File(path))
    //var isNetworkMod: Boolean

    fun tryDelete(): Boolean {
        return deleteModFileSafely(File(path))
    }

    fun getRamUsed(): String

    fun getSize(): Long

    fun getBytes(): ByteArray
}
