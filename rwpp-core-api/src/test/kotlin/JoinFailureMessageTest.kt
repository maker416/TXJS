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
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.ConnectionFailureStage
import io.github.rwpp.net.connectionFailure
import io.github.rwpp.net.localized
import io.github.rwpp.net.sync.direct.DirectModSyncErrorCode
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun throwableTypesAndNestedCausesTakePriorityOverWrapperText() {
        val cases = listOf(
            SocketTimeoutException("arbitrary text") to ConnectionFailureReason.TIMEOUT,
            UnknownHostException("example.invalid") to ConnectionFailureReason.DNS,
            NoRouteToHostException("arbitrary text") to ConnectionFailureReason.UNREACHABLE,
            ConnectException("Connection refused") to ConnectionFailureReason.REFUSED,
            ConnectException("Network is unreachable: connect") to ConnectionFailureReason.UNREACHABLE,
        )
        cases.forEach { (error, reason) ->
            val failure = assertNotNull(connectionFailure(IllegalStateException("wrapper", error)))
            assertEquals(reason, failure.reason)
            assertTrue(failure.details.contains("wrapper"))
            assertTrue(failure.details.contains(error.message!!))
        }
        assertEquals(ConnectionFailureReason.UNKNOWN, connectionFailure(ConnectException("custom timeout message"))?.reason)
        assertEquals(ConnectionFailureReason.INVALID_PORT, connectionFailure(IllegalArgumentException("port out of range:70000"))?.reason)
    }

    @Test
    fun allSevenDirectServerCodesAreMappedIndependentlyOfTheirMessage() {
        DirectModSyncErrorCode.entries.forEach { code ->
            val failure = assertNotNull(connectionFailure(
                IllegalStateException("wrapper", DirectModSyncServerException(code)),
                ConnectionFailureStage.SYNC_MANIFEST,
            ))
            assertEquals(ConnectionFailureReason.valueOf(code.name), failure.reason)
            assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
            assertTrue(failure.details.contains(code.name))
        }
    }

    @Test
    fun cancellationAnywhereInCauseChainNeverProducesAnErrorCard() {
        assertNull(connectionFailure(CancellationException("cancelled")))
        assertNull(connectionFailure(IllegalStateException("wrapper", CancellationException("cancelled"))))
        val cancelledTimeout = SocketTimeoutException("timed out").apply {
            initCause(CancellationException("cancelled"))
        }
        assertNull(connectionFailure(cancelledTimeout, ConnectionFailureStage.SYNC_DOWNLOAD))
    }

    @Test
    fun exactNativeMessagesAndKnownWrappersAreClassified() {
        val cases = mapOf(
            "Kicked: Your username is too long" to ConnectionFailureReason.USERNAME_TOO_LONG,
            "Kicked: Your username is too short" to ConnectionFailureReason.USERNAME_TOO_SHORT,
            "Kicked: Temporarily banned due to recent kick" to ConnectionFailureReason.BANNED,
            "Kicked: Game is out of date, please update to v1.15" to ConnectionFailureReason.CLIENT_OUTDATED,
            "Kicked: Your client is newer then the server. Server is on: v1.14" to ConnectionFailureReason.SERVER_OUTDATED,
            "Kicked: Your core units are different to the server's core units. Game can not be synchronized" to ConnectionFailureReason.CORE_UNITS_MISMATCH,
            "Kicked: Your 'Rusted Warfare' client is different to the server. Game can not be synchronized." to ConnectionFailureReason.CLIENT_INTEGRITY,
            "Kicked: Room is locked. New players cannot join this server." to ConnectionFailureReason.ROOM_LOCKED,
            "Kicked: A game has already been started on this server" to ConnectionFailureReason.GAME_STARTED,
            "Kicked: No free slots on server" to ConnectionFailureReason.ROOM_FULL,
            "Missing 2 mods." to ConnectionFailureReason.MISSING_MODS,
            "Missing mod: 'example'" to ConnectionFailureReason.MISSING_MODS,
            "Missing unit:exampleTank" to ConnectionFailureReason.MISSING_UNITS,
            "Different mod data for: example" to ConnectionFailureReason.DATA_MISMATCH,
            "Connection failed: Wrong password" to ConnectionFailureReason.WRONG_PASSWORD,
            "Missing password" to ConnectionFailureReason.PASSWORD_REQUIRED,
            "Failed to get server connection data - unknown" to ConnectionFailureReason.MASTER_QUERY_FAILED,
            "Direct room manifest changed during download" to ConnectionFailureReason.MANIFEST_CHANGED,
            "Direct unit checksum mismatch (local -12 / host 34)" to ConnectionFailureReason.CHECKSUM_MISMATCH,
        )
        cases.forEach { (message, reason) ->
            val failure = connectionFailure(message, ConnectionFailureStage.SERVER)
            assertEquals(reason, failure.reason, message)
            assertEquals(message, failure.details)
        }
        val timeout = connectionFailure(screenshotMessage)
        assertEquals(ConnectionFailureReason.TIMEOUT, timeout.reason)
        assertEquals(listOf("36.138.76.151", "4000", "7000"), timeout.parameters)
        assertFalse(timeout.parameters.contains("10.88.198.101"))
    }

    @Test
    fun arbitraryServerTextRemainsDetailsAndNeverBecomesLocalizedExplanation() {
        val raw = "Custom server: timeout, wrong password, missing mods, banned"
        val failure = connectionFailure(raw, ConnectionFailureStage.SERVER)
        assertEquals(ConnectionFailureReason.SERVER_REJECTED, failure.reason)
        assertEquals(raw, failure.details)
        assertEquals(ConnectionFailureReason.UNKNOWN, connectionFailure(raw).reason)
        loadBundle("zh")
        val text = failure.localized()
        assertFalse(text.title.contains(raw))
        assertFalse(text.explanation.contains(raw))
        assertFalse(text.suggestion.contains(raw))
    }

    @Test
    fun unclassifiedSyncErrorsRetainTheirStageAndUseSpecificFallbacks() {
        val reasons = mapOf(
            ConnectionFailureStage.SYNC_MANIFEST to ConnectionFailureReason.SYNC_FAILED,
            ConnectionFailureStage.SYNC_DOWNLOAD to ConnectionFailureReason.SYNC_FAILED,
            ConnectionFailureStage.SYNC_APPLY to ConnectionFailureReason.RELOAD_FAILED,
            ConnectionFailureStage.SYNC_RECONNECT to ConnectionFailureReason.RECONNECT_FAILED,
        )
        reasons.forEach { (stage, reason) ->
            val failure = assertNotNull(connectionFailure(IllegalStateException("opaque"), stage))
            assertEquals(stage, failure.stage)
            assertEquals(reason, failure.reason)
        }
    }

    @Test
    fun dynamicNativeValuesAreParametersAndOnlyDisplayedWhenAvailable() {
        loadBundle("zh")
        listOf(
            "Game is out of date, please update to v1.15" to "1.15",
            "Your client is newer then the server. Server is on: v1.14" to "1.14",
            "Missing unit:exampleTank" to "exampleTank",
            "Missing mod: 'exampleMod'" to "exampleMod",
            "Different mod data for: exampleMod" to "exampleMod",
        ).forEach { (message, parameter) ->
            val failure = connectionFailure(message)
            assertEquals(listOf(parameter), failure.parameters)
            assertTrue(failure.localized().explanation.contains(parameter))
        }
        val timeout = connectionFailure(screenshotMessage).localized()
        assertTrue(timeout.explanation.contains("36.138.76.151"))
        assertTrue(timeout.explanation.contains("7000"))
        val withoutParameters = ConnectionFailure(ConnectionFailureReason.TIMEOUT).localized()
        assertFalse(withoutParameters.explanation.contains("{0}"))
        assertFalse(withoutParameters.explanation.contains("目标："))
    }

    @Test
    fun everyReasonAndCommonUiKeyCanBeLocalizedInBothLanguages() {
        listOf("zh", "en").forEach { language ->
            loadBundle(language)
            ConnectionFailureReason.entries.forEach { reason ->
                val text = ConnectionFailure(reason, parameters = listOf("example.org", "5123", "7000")).localized()
                listOf(text.title, text.explanation, text.suggestion).forEach {
                    assertTrue(it.isNotBlank(), "$language: $reason")
                    assertFalse(it.contains("{0}"), "$language: $reason has unformatted arguments")
                }
            }
            listOf("details", "showDetails", "hideDetails", "reasonLabel", "suggestionLabel", "ok").forEach { key ->
                assertTrue(io.github.rwpp.i18n.readI18n("connectionError.$key").isNotBlank())
            }
        }
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
