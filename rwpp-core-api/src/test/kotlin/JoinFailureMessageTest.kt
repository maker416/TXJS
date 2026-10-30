/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.joinFailureMessage
import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinFailureMessageTest {
    private val screenshotMessage = "Failed to connect to host - failed to connect to /36.138.76.151 (port 4000) from /10.88.198.101 (port 40910) after 7000ms"

    @AfterTest
    fun tearDown() {
        setI18nOverride(null)
        reloadI18n()
    }

    private fun loadBundle(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(
            File("../rwpp-core/src/commonMain/composeResources/files/bundle_$language.toml").readText(),
        )
    }

    @Test
    fun screenshotTimeoutIsChineseWithDestinationAndDuration() {
        loadBundle("zh")
        val result = joinFailureMessage(screenshotMessage)
        assertEquals(
            "连接服务器超时\n目标：36.138.76.151，端口：4000\n等待 7000 毫秒后仍未连通。请检查网络连接，或稍后重试；若其他房间可以加入，可能是该服务器暂时不可达。",
            result,
        )
        assertFalse(result.contains("10.88.198.101"))
    }

    @Test
    fun desktopWrapperAndIpv6AreRecognized() {
        loadBundle("zh")
        assertEquals(joinFailureMessage(screenshotMessage), joinFailureMessage("Connection failed: $screenshotMessage."))
        val result = joinFailureMessage("Failed to connect to host - failed to connect to /2001:db8::1 (port 5123) after 5000ms")
        assertTrue(result.contains("2001:db8::1"))
        assertTrue(result.contains("5123"))
        assertTrue(result.contains("5000"))
    }

    @Test
    fun englishBundleAlsoFormatsTimeout() {
        loadBundle("en")
        val result = joinFailureMessage(screenshotMessage)
        assertTrue(result.startsWith("Connection to server timed out"))
        assertTrue(result.contains("36.138.76.151"))
        assertTrue(result.contains("7000 ms"))
    }

    @Test
    fun otherErrorsAreNotMisclassifiedAsTimeouts() {
        listOf(
            "Failed to connect to host - Connection refused",
            "Connection failed: Incorrect password.",
            "房间已关闭",
            "Failed to connect to host - failed to connect to /host (port 4000) after 7000ms: ECONNREFUSED",
        ).forEach { assertEquals(it, joinFailureMessage(it)) }
    }
}
