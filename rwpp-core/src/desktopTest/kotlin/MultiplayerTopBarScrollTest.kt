/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MultiplayerTopBarScrollTest {
    @Test
    fun wheelOnEmptyOrShortListKeepsToolbarVisible() = runDesktopComposeUiTest(width = 400, height = 320) {
        var count by mutableStateOf(0)
        lateinit var list: LazyListState
        setContent { TestList(count) { state, _ -> list = state } }
        waitForIdle()
        repeat(2) {
            onNodeWithTag("list").performMouseInput { moveTo(center); repeat(3) { scroll(40f) } }
            waitForIdle()
            onNodeWithTag("toolbar").assertIsDisplayed()
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertFalse(list.canScrollForward)
            runOnIdle { count = 3 }
            waitForIdle()
        }
    }

    @Test
    fun scrollingDownHidesToolbarAndReverseOrTopRestoresIt() = runDesktopComposeUiTest(width = 400, height = 320) {
        lateinit var list: LazyListState
        setContent { TestList(60) { state, _ -> list = state } }
        onNodeWithTag("list").performScrollToIndex(10)
        waitForIdle()
        onNodeWithTag("toolbar").assertDoesNotExist()
        onNodeWithTag("list").performMouseInput { moveTo(center); scroll(-40f) }
        waitForIdle()
        onNodeWithTag("toolbar").assertIsDisplayed()
        onNodeWithTag("list").performScrollToIndex(0)
        waitForIdle()
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        onNodeWithTag("toolbar").assertIsDisplayed()
    }

    @Test
    fun filteringToShortListRestoresHiddenToolbar() = runDesktopComposeUiTest(width = 400, height = 320) {
        var count by mutableStateOf(60)
        setContent { TestList(count) }
        onNodeWithTag("list").performScrollToIndex(10)
        waitForIdle()
        onNodeWithTag("toolbar").assertDoesNotExist()
        runOnIdle { count = 2 }
        waitForIdle()
        onNodeWithTag("toolbar").assertIsDisplayed()
        onNodeWithTag("list").performMouseInput { moveTo(center); scroll(80f) }
        waitForIdle()
        onNodeWithTag("toolbar").assertIsDisplayed()
    }

    @Test
    fun wheelPastBottomDoesNotHideToolbarWithoutMovingList() = runDesktopComposeUiTest(width = 400, height = 320) {
        lateinit var list: LazyListState
        lateinit var visible: MutableState<Boolean>
        setContent { TestList(60) { state, toolbar -> list = state; visible = toolbar } }
        onNodeWithTag("list").performScrollToIndex(59)
        waitForIdle()
        assertFalse(list.canScrollForward)
        val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        runOnIdle { visible.value = true }
        onNodeWithTag("list").performMouseInput { moveTo(center); repeat(3) { scroll(40f) } }
        waitForIdle()
        assertEquals(position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        onNodeWithTag("toolbar").assertIsDisplayed()
    }

    @Composable
    private fun TestList(
        count: Int,
        capture: (LazyListState, MutableState<Boolean>) -> Unit = { _, _ -> },
    ) {
        val list = rememberLazyListState()
        val visible = rememberMultiplayerTopBarVisible(list)
        SideEffect { capture(list, visible) }
        MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .multiplayerTopBarWheelVisibility(list) { visible.value = it }.testTag("list"),
                    state = list,
                    contentPadding = PaddingValues(top = 64.dp),
                ) {
                    items(count) { index -> Text("Room $index", Modifier.fillMaxWidth().height(48.dp)) }
                }
                if (visible.value) Text("Toolbar", Modifier.height(64.dp).testTag("toolbar"))
            }
        }
    }
}
