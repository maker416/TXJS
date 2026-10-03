/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.resolveForumUrl
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.ForumSsoSession
import io.github.rwpp.net.account.ForumSsoUrls
import kotlinx.coroutines.CancellationException
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.EmbeddedBrowser
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.platform.ResourceBrowserLayout
import io.github.rwpp.widget.BorderCard
import org.koin.compose.koinInject

@Composable
fun ResourceBrowser(onExit: () -> Unit) {
    val settings = koinInject<Settings>()
    val configIO = koinInject<ConfigIO>()
    val accountPrefs = koinInject<AccountPreferences>()
    var orientation by remember(settings) { mutableStateOf(settings.resourceBrowserOrientation) }
    // 退出动画尚未 dispose 时也要立即恢复方向，以免其他页面暂时留在竖屏。
    val active = launcherPage == LauncherPage.ResourceBrowser
    if (orientation == null && active) {
        ResourceBrowserOrientationDialog(
            onSelected = {
                settings.resourceBrowserOrientation = it
                configIO.saveConfig(settings)
                orientation = it
            },
            onDismissRequest = onExit,
        )
    }

    val forumUrl = resolveForumUrl(accountPrefs.forumUrl)
    val normalizedUrl = remember(forumUrl) { runCatching { ForumSsoUrls.base(forumUrl) }.getOrDefault(forumUrl) }
    val bootstrapUrl = remember(normalizedUrl) { runCatching { ForumSsoUrls.bootstrap(normalizedUrl) }.getOrDefault(normalizedUrl) }
    val browser = remember(normalizedUrl, AccountSession.token, AccountSession.loggedIn, ForumSsoSession.generation) {
        EmbeddedBrowserState(bootstrapUrl, normalizedUrl).also { it.clientAuthenticating = true }
    }
    var attempt by remember { mutableIntStateOf(0) }
    browser.retryClientLogin = { attempt++ }
    LaunchedEffect(browser, attempt, orientation, active) {
        if (!active || orientation == null) return@LaunchedEffect
        try {
            AccountSession.restoreIfNeeded()
            val session = AccountSession.playtimeIdentityOrNull()?.first
            val prepared = ForumSsoSession.prepare(normalizedUrl, session)
            browser.error = null
            browser.submitClientLogin(bootstrapUrl, prepared?.handoff)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            browser.error = readI18n("browser.autoLoginFailed")
            browser.isLoading = false
        }
    }
    BackHandler(true) {
        if (!browser.goBack()) onExit()
    }
    DisposableEffect(browser) {
        onDispose {
            browser.modDownload?.transfer?.cancel()
            browser.modDownload = null
            CloseUIPanelEvent("browser").broadcastIn()
        }
    }

    ResourceBrowserLayout(
        orientation = orientation.takeIf { active },
        modifier = Modifier.fillMaxSize().padding(10.dp),
    ) {
        BorderCard(
            modifier = Modifier.fillMaxSize(),
            backgroundColor = MaterialTheme.colorScheme.surface,
        ) {
            ResourceBrowserToolbar(browser, onExit)

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
                    Row {
                        TextButton(onClick = { attempt++ }) { Text(readI18n("browser.retryLogin")) }
                        TextButton(onClick = browser::openAsGuest) { Text(readI18n("browser.guest")) }
                    }
                }
            }

            // 选择完成后才创建原生浏览器，让首次弹窗拥有完整的输入焦点。
            if (orientation != null) {
                key(browser) { EmbeddedBrowser(browser, Modifier.fillMaxWidth().weight(1f)) }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
    BrowserModDownloadDialog(browser)
}

@Composable
internal fun ResourceBrowserToolbar(browser: EmbeddedBrowserState, onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 480.dp
        Column {
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
                if (compact) {
                    Spacer(Modifier.weight(1f))
                } else {
                    ResourceBrowserAddress(browser.url, Modifier.weight(1f).padding(horizontal = 8.dp))
                }
                IconButton(onClick = browser::reload) {
                    Icon(Icons.Default.Refresh, readI18n("browser.refresh"))
                }
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.Close, readI18n("common.close"))
                }
            }
            if (compact) {
                ResourceBrowserAddress(browser.url, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ResourceBrowserAddress(url: String, modifier: Modifier) {
    Text(
        url,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
