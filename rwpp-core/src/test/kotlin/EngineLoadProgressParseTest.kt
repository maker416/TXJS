import io.github.rwpp.widget.composeEngineLoadingMessage
import io.github.rwpp.widget.parseEngineLoadProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EngineLoadProgressParseTest {

    @Test
    fun parsesStructuredUnitsMessage() {
        val parsed = parseEngineLoadProgress("Loading units - 123 (combatEngineer)")
        assertNotNull(parsed)
        assertEquals("units", parsed.stage)
        assertEquals(123, parsed.count)
        assertEquals("combatEngineer", parsed.detail)
    }

    @Test
    fun parsesStructuredModsMessage() {
        val parsed = parseEngineLoadProgress("Loading mods - 4")
        assertNotNull(parsed)
        assertEquals("mods", parsed.stage)
        assertEquals(4, parsed.count)
        assertNull(parsed.detail)
    }

    @Test
    fun rejectsUnrelatedText() {
        assertNull(parseEngineLoadProgress("init complete"))
        assertNull(parseEngineLoadProgress(""))
    }

    @Test
    fun composesDialogTitleAndCount() {
        val line = composeEngineLoadingMessage("Loading units", "88 (heavyTank)")
        val parsed = parseEngineLoadProgress(line)
        assertNotNull(parsed)
        assertEquals(88, parsed.count)
        assertEquals("heavyTank", parsed.detail)
    }
}
