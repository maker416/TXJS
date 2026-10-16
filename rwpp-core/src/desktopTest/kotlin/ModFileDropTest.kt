/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.platform.analyzeRwmodFileDrop
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModFileDropTest {
    @Test
    fun emptyListReturnsNoCandidate() {
        val selection = analyzeRwmodFileDrop(emptyList())
        assertNull(selection.importCandidate)
        assertEquals(0, selection.rwmodCount)
    }

    @Test
    fun picksFirstRwmodCaseInsensitive() {
        val files = listOf(
            File("b.txt"),
            File("first.RWMOD"),
            File("second.rwmod"),
        )
        val selection = analyzeRwmodFileDrop(files)
        assertEquals("first.RWMOD", selection.importCandidate?.name)
        assertEquals(2, selection.rwmodCount)
    }

    @Test
    fun nonRwmodFallsBackToFirstFile() {
        val files = listOf(File("readme.txt"))
        val selection = analyzeRwmodFileDrop(files)
        assertEquals("readme.txt", selection.importCandidate?.name)
        assertEquals(0, selection.rwmodCount)
    }
}
