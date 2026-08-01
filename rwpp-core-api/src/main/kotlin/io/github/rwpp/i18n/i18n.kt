/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.i18n

import io.github.rwpp.appKoin
import net.peanuuutz.tomlkt.TomlTable
import net.peanuuutz.tomlkt.asTomlLiteral
import net.peanuuutz.tomlkt.asTomlTable
import java.text.MessageFormat

 var i18nTable: TomlTable =
     TomlTable()

private val cacheMap = mutableMapOf<String, String>()

fun reloadI18n() {
    cacheMap.clear()
    i18nTable = TomlTable()
}

fun readI18n(path: String, i18nType: I18nType = I18nType.RWPP, vararg arg: String): String {
    if (i18nType == I18nType.RWPP) {
        // Check if i18nTable is empty and try to initialize it
        if (i18nTable.isEmpty()) {
            try {
                val resolver: GameI18nResolver = appKoin.get()
                resolver.init()
            } catch (e: Exception) {
                // If initialization fails, return a fallback value
                return "[$path]"
            }
        }

        // 无参调用跳过 MessageFormat 的 pattern 解析，直接返回缓存模板；
        // 注意这会让无参文本中的单引号按原样显示（修正 MessageFormat 吞引号的既有缺陷，属预期）
        cacheMap[path]?.let { return if (arg.isEmpty()) it else MessageFormat.format(it, *arg) }
        val strArray = path.split(".")
        val iterator = strArray.iterator()
        var table: TomlTable = i18nTable
        while(iterator.hasNext()) {
            val next = iterator.next()
            if(!iterator.hasNext()) {
                return table[next]!!.asTomlLiteral().content.let {
                    cacheMap[path] = it
                    if (arg.isEmpty()) it else MessageFormat.format(it, *arg)
                }
            } else table = table[next]!!.asTomlTable()
        }
    } else if (i18nType == I18nType.RW) {
        val resolver: GameI18nResolver = appKoin.get()
        return resolver.i18n(path, *arg)
    }

    return "null"
}