/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import io.github.rwpp.io.HashUtils
import io.github.rwpp.net.RoomDescription
import java.io.File
import java.nio.charset.StandardCharsets

/** 同步记录 key 前缀：Q/R 短码房（大小写归一为大写），如 `code:Q77182`。 */
const val CODE_PREFIX = "code:"

/** 同步记录 key 前缀：房间发布到列表后的 server_id 别名，如 `sid:<uuid>`。 */
const val SID_PREFIX = "sid:"

private val ROOM_CODE_REGEX = Regex("^[QR]\\d+$", RegexOption.IGNORE_CASE)

/**
 * 由列表房间信息推导候选同步 key（按优先级排序，去重）：
 *
 * - [RoomDescription.uuid] 非空时产出 `sid:<uuid>`（发布后绑定的 server_id 别名）为首项；
 * - [RoomDescription.addressProvider] 去掉端口部分后形似 `[QR]\d+` 短码时追加 `code:XXX`（归一大写）。
 *
 * 直连 IP 房不产出 `code:` key；无任何匹配时返回空列表（调用方按原版行为加入）。
 */
fun forRoomDescription(desc: RoomDescription): List<String> {
    val keys = mutableListOf<String>()
    if (desc.uuid.isNotBlank()) {
        keys += "$SID_PREFIX${desc.uuid}"
    }
    val host = desc.addressProvider().substringBefore(':')
    if (ROOM_CODE_REGEX.matches(host)) {
        keys += "$CODE_PREFIX${host.uppercase()}"
    }
    return keys.distinct()
}

/**
 * 由原始加入地址推导候选同步 key：trim 并截掉可能的 `:端口` 后缀后，
 * 仅当形如 `[QR]\d+` 短码时产出 `["code:XXX"]`（归一大写）；`ip:port` 等直连地址返回空列表。
 */
fun forAddress(address: String): List<String> {
    val host = address.trim().substringBefore(':')
    return if (ROOM_CODE_REGEX.matches(host)) {
        listOf("$CODE_PREFIX${host.uppercase()}")
    } else {
        emptyList()
    }
}

/**
 * 计算模组集合指纹：`模组名→文件签名` 的规范化串的 SHA-256。
 *
 * - 与传入顺序无关（内部按模组名排序）；
 * - 文件模组签名为 `绝对路径:长度:mtime`；
 * - 目录模组递归收集所有文件的 `相对路径:长度:mtime`（目录自身 mtime 不可靠，
 *   目录内文件被编辑不会反映到目录 mtime 上）。
 *
 * 仅做 stat 级读取，开销远小于重新读取/压缩/哈希全部字节。
 *
 * （实现自 `io.github.rwpp.net.HostManifestCache.fingerprint` 原样迁移。）
 */
fun fingerprint(modFiles: List<Pair<String, File>>): String {
    val canonical = buildString {
        modFiles.sortedBy { it.first }.forEach { (name, file) ->
            append(name).append('=')
            appendFileSignature(file, this)
            append(';')
        }
    }
    return HashUtils.sha256(canonical.toByteArray(StandardCharsets.UTF_8))
}

private fun appendFileSignature(file: File, out: StringBuilder) {
    if (file.isDirectory) {
        out.append("dir[")
        val base = file.absoluteFile.toPath()
        file.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { f ->
                val rel = runCatching { base.relativize(f.absoluteFile.toPath()).toString() }
                    .getOrDefault(f.name)
                out.append(rel).append(':').append(f.length()).append(':').append(f.lastModified()).append(',')
            }
        out.append(']')
    } else {
        out.append("file(").append(file.absolutePath)
            .append(':').append(file.length())
            .append(':').append(file.lastModified()).append(')')
    }
}
