/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File

/** 按内容为每个清单项选择唯一文件；同名旧版本必须禁用，缓存仅在本地没有匹配副本时激活。 */
fun selectModSyncFiles(
    mods: List<Mod>,
    descriptors: List<NetworkModDescriptor>,
    cache: NetworkModCache,
    onHashFailure: (Mod, Exception) -> Unit = { _, _ -> },
): Set<File> = descriptors.map { descriptor ->
    val matched = mods.asSequence()
        .filter { it.name == descriptor.name }
        .sortedWith(compareBy<Mod> { it.isNetworkMod }.thenByDescending { it.isEnabled }.thenBy { it.path })
        .firstOrNull { mod ->
            try {
                descriptor.matches(mod.getBytes())
            } catch (error: Exception) {
                onHashFailure(mod, error)
                false
            }
        }
    (matched?.let { File(it.path) } ?: cache.activate(descriptor).payloadFile).canonicalFile
}.toSet()
