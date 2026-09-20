/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

/**
 * 与统一账号 API「字段规则」对齐的前端校验。
 * 只实现文档写明的约束，不额外发明规则。
 */
object AccountFieldRules {
    private val USERNAME = Regex("^[A-Za-z0-9_]{3,32}$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val CODE = Regex("^\\d{6}$")

    fun normalizeUsername(raw: String): String = raw.trim()

    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    fun isValidUsername(raw: String): Boolean = USERNAME.matches(normalizeUsername(raw))

    fun isValidPassword(raw: String): Boolean {
        val n = raw.codePointCount(0, raw.length)
        return n in 8..72
    }

    fun isValidRegisterNickname(raw: String): Boolean {
        val t = raw.trim()
        return t.length <= 64
    }

    fun isValidChangeNickname(raw: String): Boolean {
        val t = raw.trim()
        return t.length in 1..64
    }

    fun isValidEmail(raw: String): Boolean {
        val email = normalizeEmail(raw)
        return email.length in 6..128 && EMAIL.matches(email)
    }

    fun isValidCode(raw: String): Boolean = CODE.matches(raw.trim())

    fun isValidChatBody(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        return t.codePointCount(0, t.length) in 1..2000
    }
}
