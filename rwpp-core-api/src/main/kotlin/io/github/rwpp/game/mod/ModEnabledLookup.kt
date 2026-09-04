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

/** 跨进程保存"重启式特殊加载"期望启用状态的 ConfigIO group/key。 */
const val PENDING_MOD_STATES_GROUP = "rwpp_mods"
const val PENDING_MOD_STATES_KEY = "pendingEnabledStates"

/**
 * 把启用状态 map 编码为 `名字1=1;名字2=0` 单行字符串（文件名统一小写）。
 *
 * 文件名含 `;`/`=` 的条目会被丢弃（实际模组文件名几乎不会出现）；
 * 空 map 编码为 ""（写入 ConfigIO 后即等效清除，读取时空白串返回 null）。
 */
fun encodeEnabledStates(states: Map<String, Boolean>): String =
    states.entries
        .filter { (name, _) -> !name.contains(';') && !name.contains('=') }
        .joinToString(";") { (name, enabled) -> "${name.lowercase(Locale.ROOT)}=${if (enabled) "1" else "0"}" }

/** [encodeEnabledStates] 的逆操作；任何条目畸形即丢弃该条目，整体失败返回空 map。 */
fun decodeEnabledStates(raw: String?): Map<String, Boolean> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';')
        .mapNotNull { entry ->
            val sep = entry.lastIndexOf('=')
            if (sep <= 0 || sep == entry.length - 1) return@mapNotNull null
            val enabled = when (entry.substring(sep + 1)) {
                "1" -> true
                "0" -> false
                else -> return@mapNotNull null
            }
            entry.substring(0, sep) to enabled
        }
        .toMap()
}
