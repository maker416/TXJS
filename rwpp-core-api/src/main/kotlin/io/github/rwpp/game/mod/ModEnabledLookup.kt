/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File
import java.util.Locale

/**
 * 按磁盘文件名在 [enabledByFileName] 中解析期望启用状态。
 * 仅做文件名精确匹配（忽略大小写），避免 `pack.rwmod` 误匹配 `SuperWeaponPack.rwmod`。
 * 传入 map 时：未匹配到的模组默认禁用。
 */
fun resolveModEnabledByFileName(
    pathCandidates: Iterable<String>,
    enabledByFileName: Map<String, Boolean>,
): Boolean {
    val normalizedMap = enabledByFileName.mapKeys { it.key.lowercase(Locale.ROOT) }

    for (rawPath in pathCandidates) {
        if (rawPath.isBlank()) continue
        val fileName = File(rawPath.replace('\\', '/')).name.lowercase(Locale.ROOT)
        if (fileName.isNotEmpty() && normalizedMap.containsKey(fileName)) {
            return normalizedMap.getValue(fileName)
        }
    }
    return false
}

/**
 * 当前一次引擎模组扫描所使用的启用状态。
 *
 * 引擎会在 `i.a.a(boolean, boolean)` 内部先扫描目录，再加载单位定义。平台注入点在扫描
 * 返回后、单位加载前读取这里的状态，从而能处理本次扫描才创建的模组对象。
 */
object ModReloadSelection {
    private val enabledByFileName = ThreadLocal<Map<String, Boolean>?>()

    fun activate(selection: Map<String, Boolean>) {
        enabledByFileName.set(selection.toMap())
    }

    fun deactivate() {
        enabledByFileName.remove()
    }

    /**
     * 未处于受控重载时返回 null；受控重载中未匹配的文件默认返回 false（禁用）。
     */
    fun resolve(pathCandidates: Iterable<String>): Boolean? {
        val selection = enabledByFileName.get() ?: return null
        return resolveModEnabledByFileName(pathCandidates, selection)
    }
}
