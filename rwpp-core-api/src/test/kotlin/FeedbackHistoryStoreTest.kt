/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.config.FeedbackPreferences
import io.github.rwpp.net.feedback.FeedbackHistoryStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml
import java.io.IOException
import java.util.UUID
import kotlin.test.*

class FeedbackHistoryStoreTest {
    private val first = "12345678-1234-4321-8321-123456789abc"
    private val second = "22345678-1234-4321-8321-123456789abc"

    @Test fun persistsOnlyReceiptsAndKeepsEndpointAndAccountGroupsSeparate() {
        val prefs = FeedbackPreferences()
        var saved = ""
        val store = FeedbackHistoryStore(prefs) { saved = Json.encodeToString(it) }
        store.record("https://feedback.example/", "", first)
        store.record("https://feedback.example", "account-a", second)
        store.record("https://other.example", "", second)
        assertEquals(listOf(first), store.list("https://feedback.example", "").map { it.id })
        assertEquals(listOf(second), store.list("https://feedback.example/", "account-a").map { it.id })
        assertTrue(store.list("https://feedback.example", "account-b").isEmpty())
        assertFalse(saved.contains("token"))
        assertFalse(saved.contains("description"))
        assertFalse(saved.contains("contact"))
        val restored = Json.decodeFromString<FeedbackPreferences>(saved)
        assertEquals(store.list("https://feedback.example", ""), FeedbackHistoryStore(restored) {}.list("https://feedback.example", ""))
    }

    @Test fun retryIsIdempotentAndStorageFailureRestoresPreviousEntries() {
        val prefs = FeedbackPreferences()
        var writes = 0
        var fail = false
        val store = FeedbackHistoryStore(prefs) { writes++; if (fail) throw IOException("test disk failure") }
        store.record("https://feedback.example", "", first)
        store.record("https://feedback.example/", "", first)
        assertEquals(1, writes)
        fail = true
        assertFailsWith<IOException> { store.record("https://feedback.example", "", second) }
        assertEquals(listOf(first), store.list("https://feedback.example", "").map { it.id })
        fail = false
        store.record("https://feedback.example", "", second)
        assertEquals(listOf(second, first), store.list("https://feedback.example", "").map { it.id })
    }

    @Test fun historyRoundTripsThroughRuntimeTomlAndOldPreferencesDefaultToEmptyHistory() {
        val prefs = FeedbackPreferences("https://feedback.example")
        FeedbackHistoryStore(prefs) {}.record(prefs.apiUrl, "", first)
        val encoded = Toml.encodeToString(FeedbackPreferences.serializer(), prefs)
        val restored = Toml.decodeFromString(FeedbackPreferences.serializer(), encoded)
        assertEquals(prefs, restored)
        assertTrue(Toml.decodeFromString(FeedbackPreferences.serializer(), "apiUrl = \"https://feedback.example\"").history.isEmpty())
    }

    @Test fun boundsRetainedReceiptsAndRejectsInvalidIdsBeforePersisting() {
        val prefs = FeedbackPreferences()
        val store = FeedbackHistoryStore(prefs) {}
        val ids = List(1005) { UUID.randomUUID().toString() }
        ids.forEach { store.record("https://feedback.example", "", it) }
        assertEquals(ids.takeLast(1000).reversed(), store.list("https://feedback.example", "").map { it.id })
        assertFailsWith<IllegalArgumentException> { store.record("https://feedback.example", "", "../private") }
        assertFailsWith<IllegalArgumentException> { store.record("https://user:password@feedback.example", "", first) }
        assertEquals(1000, prefs.history.size)
    }
}
