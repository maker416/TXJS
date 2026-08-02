/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.HashUtils
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 房主侧「已准备模组清单」缓存。
 *
 * 准备清单需要对每个所需模组执行「读取/目录压缩 + SHA-256」，开销很大；而同一局房内所有加入者
 * 请求的模组集合完全相同。本缓存以 [fingerprint]（所需模组集合 + 各模组文件签名）为键，
 * 指纹一致即直接复用已准备好的字节，避免每个加入者（以及同一客户端的重复请求）重复准备。
 *
 * 仅保留单份条目：新准备完成时整体替换旧条目。条目在关房/传输清理时由调用方 [clear]。
 *
 * 注意：[HostModTransferSource.release] 当前默认无操作（字节由 GC 回收），因此条目替换
 * 不影响仍在使用旧字节的发送会话；若未来 release 引入真实资源释放，需要改为引用计数。
 */
class HostManifestCache {

    class Entry(
        val fingerprint: String,
        val sources: List<HostModTransferSource>,
    ) {
        val descriptors: List<NetworkModDescriptor> = sources.map { it.descriptor }
    }

    private var entry: Entry? = null

    /** 指纹一致时返回缓存条目，否则返回 null。 */
    @Synchronized
    fun get(fingerprint: String): Entry? = entry?.takeIf { it.fingerprint == fingerprint }

    /** 存入新条目并替换旧条目（旧条目字节由 GC 回收，见类注释）。 */
    @Synchronized
    fun put(newEntry: Entry) {
        entry = newEntry
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    companion object {
        /**
         * 计算模组集合指纹：`模组名→文件签名` 的规范化串的 SHA-256。
         *
         * - 与传入顺序无关（内部按模组名排序）；
         * - 文件模组签名为 `绝对路径:长度:mtime`；
         * - 目录模组递归收集所有文件的 `相对路径:长度:mtime`（目录自身 mtime 不可靠，
         *   目录内文件被编辑不会反映到目录 mtime 上）。
         *
         * 仅做 stat 级读取，开销远小于重新读取/压缩/哈希全部字节。
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
    }
}
