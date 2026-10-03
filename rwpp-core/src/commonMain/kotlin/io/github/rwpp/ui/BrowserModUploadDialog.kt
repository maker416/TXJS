/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rwpp.config.BrowserUploadSource
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.BrowserFileUploadRequest
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.LargeDropdownMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

internal data class BrowserUploadMod(val name: String, val file: File)
private data class BrowserUploadChoice(val mod: BrowserUploadMod, val uploadName: String) {
    val name: String get() = mod.name
    val file: File get() = mod.file
}
private enum class UploadStage { Source, Mods, Browsing, Packing }

@Composable
internal fun BrowserModUploadDialog(browser: EmbeddedBrowserState) {
    browser.fileUpload?.let { request ->
        key(request) {
            val settings = koinInject<Settings>()
            val configIO = koinInject<ConfigIO>()
            val modManager = koinInject<ModManager>()
            BrowserFileUploadDialog(request, settings, { configIO.saveConfig(settings) }) {
                modManager.getAllMods().map { BrowserUploadMod(it.name, File(it.path)) }
            }
        }
    }
}

@Composable
internal fun BrowserFileUploadDialog(
    request: BrowserFileUploadRequest,
    settings: Settings,
    saveSettings: () -> Unit,
    listMods: () -> List<BrowserUploadMod>,
) {
    val scope = rememberCoroutineScope()
    var stage by remember(request) { mutableStateOf(when {
        !request.accept.supportsMods || settings.browserUploadSource == BrowserUploadSource.Files -> UploadStage.Browsing
        settings.browserUploadSource == BrowserUploadSource.Mods -> UploadStage.Mods
        else -> UploadStage.Source
    }) }
    var mods by remember { mutableStateOf<List<BrowserUploadChoice>?>(null) }
    var selected by remember { mutableStateOf(emptySet<BrowserUploadChoice>()) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun choose(source: BrowserUploadSource, persist: Boolean) {
        if (persist) {
            settings.browserUploadSource = source
            saveSettings()
        }
        stage = if (source == BrowserUploadSource.Files) UploadStage.Browsing else UploadStage.Mods
    }

    val currentStage = stage
    LaunchedEffect(currentStage) {
        if (currentStage == UploadStage.Browsing) request.browseFiles()
        if (currentStage == UploadStage.Mods && mods == null) {
            try {
                mods = withContext(Dispatchers.IO) {
                    listMods().mapNotNull { mod ->
                        if (!mod.file.exists()) null
                        else request.uploadName(mod.file, mod.name)?.let { BrowserUploadChoice(mod, it) }
                    }
                        .distinctBy { it.file.canonicalPath }.sortedBy { it.name.lowercase() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName; mods = emptyList() }
        }
    }
    DisposableEffect(request) { onDispose { request.cancel() } }
    BackHandler(stage != UploadStage.Browsing) { request.cancel() }
    if (stage == UploadStage.Browsing) return

    fun toggle(mod: BrowserUploadChoice) {
        selected = if (mod in selected) selected - mod
        else if (request.multiple) selected + mod else setOf(mod)
    }

    fun upload() {
        if (selected.isEmpty() || stage != UploadStage.Mods) return
        stage = UploadStage.Packing
        error = null
        scope.launch {
            try {
                val files = withContext(NonCancellable + Dispatchers.IO) {
                    selected.map { mod ->
                        request.cache.prepare(mod.file, mod.uploadName, request::checkActive)
                    }
                }
                request.selectFiles(files)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName; stage = UploadStage.Mods }
        }
    }

    Dialog(onDismissRequest = { request.cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BorderCard(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(readI18n(if (stage == UploadStage.Source) "browser.uploadSourceTitle" else "browser.uploadModsTitle"),
                        style = MaterialTheme.typography.titleLarge)
                    if (stage == UploadStage.Source) {
                        Text(readI18n("browser.uploadSourceHint"))
                        TextButton(onClick = { choose(BrowserUploadSource.Files, true) }, modifier = Modifier.fillMaxWidth()) {
                            Text(readI18n("browser.uploadFiles"))
                        }
                        TextButton(onClick = { choose(BrowserUploadSource.Mods, true) }, modifier = Modifier.fillMaxWidth()) {
                            Text(readI18n("browser.uploadMods"))
                        }
                    } else if (stage == UploadStage.Packing) {
                        Text(readI18n("browser.uploadPreparing"))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text(readI18n("browser.uploadModsHint"))
                        OutlinedTextField(search, { search = it }, label = { Text(readI18n("browser.search")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        val visible = mods?.filter { it.name.contains(search, true) || it.file.name.contains(search, true) }
                        if (visible == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (visible.isEmpty()) Text(readI18n("browser.uploadNoMods"))
                        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                            items(visible, key = { it.file.path }) { mod ->
                                Row(Modifier.fillMaxWidth().clickable { toggle(mod) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(mod in selected, { toggle(mod) })
                                    Column(Modifier.weight(1f)) {
                                        Text(mod.name)
                                        Text(mod.uploadName, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { choose(BrowserUploadSource.Files, true) }) {
                            Text(readI18n("browser.uploadSwitchToFiles"))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { request.cancel() }) { Text(readI18n("common.cancel")) }
                    if (stage == UploadStage.Mods) {
                        TextButton(onClick = ::upload, enabled = selected.isNotEmpty()) { Text(readI18n("browser.uploadSelected")) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowserUploadSourceSetting(settings: Settings, configIO: ConfigIO) {
    val sources = listOf(null) + BrowserUploadSource.entries
    var source by remember(settings) { mutableStateOf(settings.browserUploadSource) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(readI18n("browser.defaultUploadSource"), style = MaterialTheme.typography.bodyMedium)
        LargeDropdownMenu(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), label = "",
            items = sources.map { readI18n(when (it) {
                BrowserUploadSource.Files -> "browser.uploadFiles"
                BrowserUploadSource.Mods -> "browser.uploadMods"
                null -> "browser.uploadAsk"
            }) }, selectedIndex = sources.indexOf(source),
            onItemSelected = { index, _ ->
                source = sources[index]
                settings.browserUploadSource = source
                configIO.saveConfig(settings)
            })
    }
}
