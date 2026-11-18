/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.gameFramework.e.c
import com.corrodinggames.rts.gameFramework.utility.j
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.resourceOutputDir
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

@InjectClass(c::class)
object AssetInject {
    private val overlay by lazy { DesktopAssetOverlay(resourceOutputDir) }
    // InjectApi preserves f(String) under this name in the loaded game class.
    private val originalPath by lazy {
        c::class.java.getDeclaredMethod("__original__f", String::class.java).apply { isAccessible = true }
    }

    @Inject("f", injectMode = InjectMode.InsertBefore)
    fun c.redirectAsset(str: String): Any {
        if (!overlay.handles(str)) return Unit
        val resolved = originalPath.invoke(this, str) as String?
        return InterruptResult(overlay.redirectResolvedPath(resolved))
    }

    @Inject("i", injectMode = InjectMode.InsertBefore)
    fun redirectImageAsset(str: String): Any {
        if (!overlay.handles(str)) return Unit
        return InterruptResult(overlay.openAsset(str))
    }
}

internal class DesktopAssetOverlay(private val outputDir: String) {
    private val directory = File(outputDir)

    // Cache the path only: switching a theme can rebuild or remove the directory at runtime.
    fun handles(path: String): Boolean = !path.contains("builtin_mods") &&
        !(path.contains("maps") && !path.contains("bitmaps")) && !path.contains("translations") &&
        directory.isDirectory

    private fun relativeAsset(path: String): String? =
        if (path.startsWith("assets/") || path.startsWith("assets\\"))
            path.substring("assets/".length).replace('\\', '/') else null

    fun redirectResolvedPath(path: String?): String? =
        path?.let(::relativeAsset)?.let { outputDir + it } ?: path

    fun openAsset(path: String): j? {
        val relative = relativeAsset(path) ?: path
        val target = outputDir + relative
        return try {
            j(FileInputStream(target), target, relative)
        } catch (_: FileNotFoundException) {
            null
        }
    }
}
