/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.UiCircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** UI-owned state for file-only reads. Engine snapshots must be captured before calling [load]. */
internal class PageFileLoadState<T : Any>(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var value: T? by mutableStateOf(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var failure: Exception? by mutableStateOf(null)
        private set
    val isReady: Boolean get() = !closed && value != null && !isLoading && failure == null

    private var generation = 0L
    private var closed = false

    /** Newer refreshes win, including when a cancelled filesystem call finishes late. */
    suspend fun load(readFiles: suspend () -> T): T? {
        if (closed) return null
        val request = ++generation
        isLoading = true
        failure = null
        try {
            val result = withContext(ioDispatcher) { readFiles() }
            currentCoroutineContext().ensureActive()
            if (closed || request != generation) return null
            value = result
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed && request == generation) failure = e
            return null
        } finally {
            if (!closed && request == generation) isLoading = false
        }
    }

    fun close() {
        closed = true
        generation++
    }
}

@Composable
internal fun <T : Any> rememberPageFileLoadState(): PageFileLoadState<T> {
    val state = remember { PageFileLoadState<T>() }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

@Composable
internal fun PageFileLoading(
    state: PageFileLoadState<*>,
    pageTitle: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp).testTag("page-files-loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (state.isLoading) {
            UiCircularProgressIndicator()
            Text(readI18n("mod.loadingStage", I18nType.RWPP, pageTitle))
        } else {
            Text(readI18n("common.failed"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(readI18n("maps.refresh")) }
        }
    }
}
