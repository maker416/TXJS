/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.i18n

import io.github.rwpp.appKoin
import net.peanuuutz.tomlkt.TomlLiteral
import net.peanuuutz.tomlkt.TomlTable
import net.peanuuutz.tomlkt.asTomlLiteral
import net.peanuuutz.tomlkt.asTomlTable
import java.text.MessageFormat

 var i18nTable: TomlTable =
     TomlTable()

/**
 * 主题美术包的文本覆盖表（`strings_zh.toml` / `strings_en.toml` 解析结果）。
 *
 * [readI18n] 会先在本表中按点分路径**安全导航**，命中即返回；
 * 任一环节缺失都回落内置 [i18nTable] 的严格查找，绝不因覆盖表缺键抛异常。
 */
var i18nOverrideTable: TomlTable? = null
    private set

private val cacheMap = mutableMapOf<String, String>()

/**
 * 设置/清除文本覆盖表，并清空结果缓存使覆盖立即生效。
 */
fun setI18nOverride(table: TomlTable?) {
    i18nOverrideTable = table
    cacheMap.clear()
}

/** 在覆盖表中沿点分路径安全导航，命中字符串则返回，否则 null。 */
private fun TomlTable.resolveLiteralOrNull(path: String): String? {
    val parts = path.split(".")
    var table: TomlTable = this
    parts.forEachIndexed { index, part ->
        val element = table[part] ?: return null
        if (index == parts.lastIndex) {
            return (element as? TomlLiteral)?.content
        }
        table = (element as? TomlTable) ?: return null
    }
    return null
}

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

        cacheMap[path]?.let { return MessageFormat.format(it, *arg) }
        i18nOverrideTable?.resolveLiteralOrNull(path)?.let {
            cacheMap[path] = it
            return MessageFormat.format(it, *arg)
        }
        val strArray = path.split(".")
        val iterator = strArray.iterator()
        var table: TomlTable = i18nTable
        while(iterator.hasNext()) {
            val next = iterator.next()
            if(!iterator.hasNext()) {
                return table[next]!!.asTomlLiteral().content.let {
                    cacheMap[path] = it
                    MessageFormat.format(it, *arg)
                }
            } else table = table[next]!!.asTomlTable()
        }
    } else if (i18nType == I18nType.RW) {
        val resolver: GameI18nResolver = appKoin.get()
        return resolver.i18n(path, *arg)
    }

    return "null"
}