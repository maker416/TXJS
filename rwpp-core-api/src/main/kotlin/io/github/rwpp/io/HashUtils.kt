/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.io

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 哈希工具，用于 mod 传输的完整性校验。
 */
object HashUtils {
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 对内存中的 [bytes] 计算 SHA-256，返回小写十六进制字符串。
     * 调用方应复用同一份 ByteArray（如 mod 传输时对 [getBytes] 的返回值既分块又算哈希），
     * 避免对同一份数据二次全量进内存。
     */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toHex(digest.digest(bytes))
    }

    /**
     * 流式读取 [file] 计算 SHA-256，避免大文件二次全量进内存。
     * 用 8KB 缓冲区分块 update。
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input: InputStream ->
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
