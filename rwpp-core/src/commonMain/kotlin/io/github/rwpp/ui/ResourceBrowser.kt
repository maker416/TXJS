/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.EmbeddedBrowser
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.widget.BorderCard

private const val RESOURCE_BROWSER_URL = "http://192.168.1.102:8080"

@Composable
fun ResourceBrowser(onExit: () -> Unit) {
    val browser = remember { EmbeddedBrowserState(RESOURCE_BROWSER_URL) }
    BackHandler(true) {
        if (!browser.goBack()) onExit()
    }
    DisposableEffect(Unit) {
        onDispose { CloseUIPanelEvent("browser").broadcastIn() }
    }

    BorderCard(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { browser.goBack() }, enabled = browser.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, readI18n("browser.back"))
            }
            IconButton(onClick = browser::goForward, enabled = browser.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, readI18n("browser.forward"))
            }
            IconButton(onClick = browser::goHome) {
                Icon(Icons.Default.Home, readI18n("browser.home"))
            }
            Text(
                browser.url,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = browser::reload) {
                Icon(Icons.Default.Refresh, readI18n("browser.refresh"))
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, readI18n("common.close"))
            }
        }

        if (browser.isLoading) {
            val progress = browser.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        } else {
            Spacer(Modifier.height(3.dp))
        }

        browser.error?.let { error ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(readI18n("browser.loadFailed"), color = MaterialTheme.colorScheme.error)
                Text(error, style = MaterialTheme.typography.bodySmall)
            }
        }

        EmbeddedBrowser(browser, Modifier.fillMaxWidth().weight(1f))
    }
}
