/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun remoteOlderThanCurrent_shouldNotPrompt() {
        assertFalse(shouldPromptUpdate("v1.7.2", "v1.9.0"))
    }

    @Test
    fun prereleaseTag_doesNotThrow() {
        assertEquals(0, compareVersions("v1.9.0-beta1", "v1.9.0"))
        assertEquals(1, compareVersions("v2.0.0-beta1", "v1.9.0"))
    }

    @Test
    fun parseVersionPart_handlesNonNumericSuffix() {
        assertEquals(0, parseVersionPart("0-beta1"))
        assertEquals(9, parseVersionPart("9rc"))
        assertEquals(0, parseVersionPart("beta1"))
        assertEquals(0, parseVersionPart(""))
    }

    @Test
    fun malformedTags_neverThrow() {
        listOf(
            "v1.9.0-beta1" to "1.9.0",
            "not-a-version" to "1.9.0",
            "" to "v1.0.0",
        ).forEach { (remote, local) ->
            compareVersions(remote, local)
        }
    }

    @Test
    fun remoteNewerThanCurrent_shouldPrompt() {
        assertTrue(shouldPromptUpdate("v2.0.0", "v1.9.0"))
    }

    private fun shouldPromptUpdate(remoteTag: String, projectVersion: String): Boolean =
        compareVersions(remoteTag, projectVersion) > 0
}
