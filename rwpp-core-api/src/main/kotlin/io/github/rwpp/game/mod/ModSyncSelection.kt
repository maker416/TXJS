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
                descriptor == mod.payloadDescriptor()
            } catch (error: Exception) {
                onHashFailure(mod, error)
                false
            }
        }
    (matched?.let { File(it.path) } ?: cache.activate(descriptor).payloadFile).canonicalFile
}.toSet()

/** 返回本地或缓存已满足的清单身份，供 Direct 只下载缺失项。 */
fun findMatchingModSyncKeys(
    mods: List<Mod>,
    descriptors: List<NetworkModDescriptor>,
    cache: NetworkModCache,
    onHashFailure: (Mod, Exception) -> Unit = { _, _ -> },
): Set<String> {
    val targets = descriptors.associateBy { it.name }
    val matches = descriptors.filter { cache.find(it) != null }.mapTo(mutableSetOf()) { it.cacheKey() }
    mods.forEach { mod ->
        val expected = targets[mod.name] ?: return@forEach
        val actual = if (mod.isNetworkMod) cache.descriptorForManagedPath(mod.path) else try {
            mod.payloadDescriptor()
        } catch (error: Exception) {
            onHashFailure(mod, error)
            null
        }
        if (actual == expected) matches += expected.cacheKey()
    }
    return matches
}

/** 启用集合必须与清单完全一致；禁用副本或缓存中的正确版本不能替启用副本通过校验。 */
fun isEnabledModSetExact(
    mods: List<Mod>,
    descriptors: List<NetworkModDescriptor>,
    cache: NetworkModCache,
    onHashFailure: (Mod, Exception) -> Unit = { _, _ -> },
): Boolean {
    val enabled = mods.filter { it.isEnabled }
    val expectedByName = descriptors.associateBy { it.name }
    if (enabled.size != descriptors.size || enabled.map { it.name }.toSet() != expectedByName.keys) return false
    return enabled.all { mod ->
        val expected = expectedByName[mod.name] ?: return@all false
        val actual = if (mod.isNetworkMod) cache.descriptorForManagedPath(mod.path) else try {
            mod.payloadDescriptor()
        } catch (error: Exception) {
            onHashFailure(mod, error)
            null
        }
        actual == expected
    }
}
