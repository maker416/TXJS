/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.config.DEFAULT_ACCOUNT_API_URL
import io.github.rwpp.config.LEGACY_ACCOUNT_APP_KEY
import io.github.rwpp.config.resolveAccountApiUrl
import io.github.rwpp.config.resolveAccountAppKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountConfigTest {

    @Test
    fun blankUrlFallsBackToDefaultAndStripsSlash() {
        assertEquals(DEFAULT_ACCOUNT_API_URL, resolveAccountApiUrl(""))
        assertEquals("http://example.com:1", resolveAccountApiUrl("http://example.com:1/"))
    }

    @Test
    fun blankAppKeyFallsBackToShippedDefault() {
        val key = resolveAccountAppKey("")
        assertTrue(key.startsWith("ak_"))
        assertTrue(key.length > 8)
    }

    @Test
    fun legacyAppKeyMigratesToShippedDefault() {
        val migrated = resolveAccountAppKey(LEGACY_ACCOUNT_APP_KEY)
        assertTrue(migrated.startsWith("ak_"))
        assertTrue(migrated != LEGACY_ACCOUNT_APP_KEY)
    }

    @Test
    fun customAppKeyIsKept() {
        assertEquals("ak_custom123", resolveAccountAppKey("ak_custom123"))
    }
}
