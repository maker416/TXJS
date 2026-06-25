/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.io

import io.github.rwpp.modDir
import java.io.File
import java.io.IOException

/**
 * 原子写工具：写到同目录临时文件，再 [File.renameTo] 目标路径。
 *
 * 用于 mod 传输落盘：避免 [File.writeBytes] 直写最终路径在写入中途崩溃/断连时
 * 损坏已有 mod。失败时临时文件被清理，目标路径要么是完整新文件、要么保持旧文件。
 *
 * 路径安全策略与 [io.github.rwpp.game.mod.deleteModFileSafely] 一致：
 * 通过 canonicalFile + 祖先链遍历校验目标在允许的根目录之下，拒绝越界写。
 */
object AtomicFileUtils {

    /**
     * 将 [bytes] 原子地写入接收者 [File]。
     *
     * 流程：校验路径在 [allowedRoots] 之下 → 写到同目录 `.rwmod.tmp` 临时文件 → renameTo 目标。
     *
     * @param allowedRoots 允许的根目录白名单，默认仅 [modDir]。
     * @return 写入完成的目标文件。
     * @throws IOException 路径越界、临时文件创建/写入失败或 rename 失败时抛出。
     *         失败时临时文件会被删除，目标文件不受影响。
     */
    fun File.writeBytesAtomic(
        bytes: ByteArray,
        allowedRoots: List<File> = listOf(File(modDir)),
    ): File {
        parentFile?.mkdirs()

        val canonicalTarget = this.canonicalFile
        val inAllowedRoot = allowedRoots.any { root ->
            val allowed = root.canonicalFile
            canonicalTarget != allowed && canonicalTarget.isInside(allowed)
        }
        if (!inAllowedRoot) {
            throw IOException("Refuse to write outside allowed mod roots: $canonicalTarget")
        }

        val tmp = File.createTempFile(this.name, ".tmp", canonicalTarget.parentFile)
        try {
            tmp.writeBytes(bytes)
            // 目标已存在时先删，否则 renameTo 在部分文件系统上会失败
            if (canonicalTarget.exists() && !canonicalTarget.delete()) {
                throw IOException("Failed to delete existing target: $canonicalTarget")
            }
            if (!tmp.renameTo(canonicalTarget)) {
                throw IOException("Failed to rename temp file to target: $canonicalTarget")
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return canonicalTarget
    }

    private fun File.isInside(parent: File): Boolean {
        var current: File? = this
        while (current != null) {
            if (current == parent) return true
            current = current.parentFile
        }
        return false
    }
}
