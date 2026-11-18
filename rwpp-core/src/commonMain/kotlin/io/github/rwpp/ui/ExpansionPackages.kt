/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.content.ExpansionPackages
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.expansion.InstalledExpansion
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.games.OnlineGameClient
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.UiCircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun expansionDisplaySize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.1f KiB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

@Composable
internal fun ExpansionPackagesView(onExit: () -> Unit) {
    BackHandler(true, onExit)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InstalledExpansion?>(null) }
    val packages = ExpansionPackages.installed
    val active = ExpansionPackages.activeSha
    val room = appKoin.getOrNull<Game>()?.gameRoom
    val sessionActive = active != null || room?.let { it.isConnecting || it.isStartGame || it.isStartingGame } == true
    LaunchedEffect(Unit) {
        try { ExpansionPackages.refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = readI18n("expansion.failed"); error = true }
    }
    fun refresh() {
        if (busy) return
        scope.launch {
            busy = true
            try { ExpansionPackages.refresh(); notice = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = readI18n("expansion.failed"); error = true }
            finally { busy = false }
        }
    }
    val onImportFile: () -> Unit = {
        appKoin.get<ExternalHandler>().openFileChooser { file ->
            scope.launch {
                if (busy) return@launch
                busy = true
                try {
                    check(file.extension.equals("zip", ignoreCase = true))
                    val installed = ExpansionPackages.import(file)
                    notice = readI18n("expansion.imported") + " " + installed.name
                    error = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = readI18n("expansion.failed"); error = true }
                finally { busy = false }
            }
        }
    }
    ExpansionPackagesContent(packages, active, busy, sessionActive, notice, error,
        onImport = onImportFile, onRefresh = ::refresh, onDelete = { deleteTarget = it },
        onCheck = { installed -> scope.launch {
            if (busy) return@launch
            busy = true
            try {
                val binding = withContext(Dispatchers.IO) { OnlineGameClient(resolveOpenPlatformUrl()).game(installed.gameAppId)?.expansionBinding }
                notice = readI18n(when {
                    binding == null -> "expansion.unavailable"
                    binding == installed.binding -> "expansion.current"
                    else -> "expansion.updateAvailable"
                })
                error = binding == null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = readI18n("expansion.checkFailed"); error = true }
            finally { busy = false }
        } },
        onJoin = { installed ->
            UI.pendingExpansionGameAppId = installed.gameAppId
            navigateTo(LauncherPage.GameplayHall)
        }, onExit = onExit)
    deleteTarget?.let { installed ->
        AnimatedAlertDialog(true, onDismissRequest = { deleteTarget = null }) { dismiss ->
            BorderCard(Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(readI18n("expansion.deleteConfirm") + " " + installed.name + " · " + installed.version)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss) { Text(readI18n("expansion.cancel")) }
                        TextButton(onClick = { scope.launch {
                            busy = true
                            try {
                                val deleted = ExpansionPackages.delete(installed.sha256)
                                notice = readI18n(if (deleted) "expansion.deleted" else "expansion.inUse")
                                error = !deleted
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = readI18n("expansion.failed"); error = true }
                            finally { busy = false; deleteTarget = null }
                        } }) { Text(readI18n("expansion.delete")) }
                    }
                }
            }
        }
    }
}

/** Actual management layout, independently testable without opening a file chooser or a game engine. */
@Composable
internal fun ExpansionPackagesContent(
    packages: List<InstalledExpansion>, activeSha: String?, busy: Boolean, sessionActive: Boolean,
    notice: String?, isError: Boolean,
    onImport: () -> Unit, onRefresh: () -> Unit, onDelete: (InstalledExpansion) -> Unit,
    onCheck: (InstalledExpansion) -> Unit, onJoin: (InstalledExpansion) -> Unit, onExit: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().padding(10.dp).testTag("expansion-library"), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(readI18n("expansion.packagesTab"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onExit) { Text(readI18n("expansion.back")) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = !busy, modifier = Modifier.testTag("expansion-import")) { Text(readI18n("expansion.import")) }
                OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.testTag("expansion-refresh")) { Text(readI18n("expansion.refresh")) }
            }
            Text(readI18n("expansion.explanation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                UiCircularProgressIndicator(Modifier.size(22.dp))
                Text(readI18n("expansion.working"))
            }
            notice?.let { Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("expansion-notice")) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (packages.isEmpty()) item {
                    Text(readI18n("expansion.empty"), Modifier.padding(vertical = 28.dp).testTag("expansion-empty"))
                }
                items(packages, key = { it.sha256 }) { installed ->
                    val active = installed.sha256 == activeSha
                    OutlinedCard(Modifier.fillMaxWidth().testTag("expansion-${installed.sha256}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(installed.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${installed.version} · ${expansionDisplaySize(installed.size)} · ${installed.manifest.mods.size} " + readI18n("expansion.mods"),
                                style = MaterialTheme.typography.bodySmall)
                            Text(readI18n("expansion.game") + " " + installed.gameAppId, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (active) Text(readI18n("expansion.inUse"), color = MaterialTheme.colorScheme.primary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onJoin(installed) }, enabled = !busy && !sessionActive,
                                    modifier = Modifier.testTag("expansion-join-${installed.sha256}")) { Text(readI18n("expansion.join")) }
                                TextButton(onClick = { onCheck(installed) }, enabled = !busy) { Text(readI18n("expansion.check")) }
                                TextButton(onClick = { onDelete(installed) }, enabled = !busy && !active,
                                    modifier = Modifier.testTag("expansion-delete-${installed.sha256}")) { Text(readI18n("expansion.delete")) }
                            }
                        }
                    }
                }
            }
        }
    }
}
