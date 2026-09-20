/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.account.AccountFieldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountFieldRulesTest {

    @Test
    fun usernameMustBe3To32AlnumOrUnderscore() {
        assertTrue(AccountFieldRules.isValidUsername("abc"))
        assertTrue(AccountFieldRules.isValidUsername("A1_z"))
        assertTrue(AccountFieldRules.isValidUsername("a".repeat(32)))
        assertFalse(AccountFieldRules.isValidUsername("ab"))
        assertFalse(AccountFieldRules.isValidUsername("a".repeat(33)))
        assertFalse(AccountFieldRules.isValidUsername("alice@x"))
        assertFalse(AccountFieldRules.isValidUsername("修玉"))
        assertTrue(AccountFieldRules.isValidUsername(" alice "))
        assertEquals("alice", AccountFieldRules.normalizeUsername(" alice "))
    }

    @Test
    fun passwordCountsUnicodeCodePoints() {
        assertFalse(AccountFieldRules.isValidPassword("short"))
        assertTrue(AccountFieldRules.isValidPassword("passw0rd"))
        assertTrue(AccountFieldRules.isValidPassword("密码要够长啊12"))
        assertFalse(AccountFieldRules.isValidPassword("x".repeat(73)))
        assertTrue(AccountFieldRules.isValidPassword("x".repeat(72)))
    }

    @Test
    fun emailIsTrimmedLowercasedAndChecked() {
        assertTrue(AccountFieldRules.isValidEmail("Alice@Example.com"))
        assertEquals("alice@example.com", AccountFieldRules.normalizeEmail(" Alice@Example.com "))
        assertFalse(AccountFieldRules.isValidEmail("not-an-email"))
        assertFalse(AccountFieldRules.isValidEmail("a@b"))
    }

    @Test
    fun codeMustBeSixDigits() {
        assertTrue(AccountFieldRules.isValidCode("123456"))
        assertFalse(AccountFieldRules.isValidCode("12345"))
        assertFalse(AccountFieldRules.isValidCode("12345a"))
    }

    @Test
    fun nicknameAndChatBodyFollowDocumentLimits() {
        assertTrue(AccountFieldRules.isValidRegisterNickname(""))
        assertTrue(AccountFieldRules.isValidRegisterNickname("A".repeat(64)))
        assertFalse(AccountFieldRules.isValidRegisterNickname("A".repeat(65)))
        assertFalse(AccountFieldRules.isValidChangeNickname(""))
        assertTrue(AccountFieldRules.isValidChangeNickname(" Alice "))
        assertTrue(AccountFieldRules.isValidChatBody("hello"))
        assertFalse(AccountFieldRules.isValidChatBody("   "))
        assertFalse(AccountFieldRules.isValidChatBody("x".repeat(2001)))
    }
}
