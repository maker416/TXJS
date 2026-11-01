/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.Net
import io.github.rwpp.net.browser.*
import kotlinx.coroutines.*
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BrowserKernelEnvironment(val requirement: BrowserKernelRequirement, val root: File, val legacyDirectory: File? = null)
@Composable internal expect fun rememberBrowserKernelEnvironment(): BrowserKernelEnvironment
internal expect val browserKernelDownloadRequired: Boolean

/** Native runtimes bind to one verified directory for this process; reopening a page is offline. */
internal object BrowserKernelRuntime {
    data class Binding(val directory: File, val requirement: BrowserKernelRequirement?)
    private val verified = ConcurrentHashMap<String, Binding>()
    fun directory(id: String): File? = verified[id]?.directory
    fun binding(id: String): Binding? = verified[id]
    fun bind(id: String, directory: File) { verified.putIfAbsent(id, Binding(directory, null)) }
    @Synchronized fun bindVerified(id: String, requirement: BrowserKernelRequirement, directory: File, manager: BrowserKernelManager): Binding {
        verified[id]?.let { return it }
        manager.activate(requirement)
        return Binding(directory, requirement).also { verified[id] = it }
    }
}

internal data class BrowserKernelDiscovery(val update: BrowserKernelUpdate, val source: BrowserKernelSource)

@Composable
internal fun BrowserKernelGate(active: Boolean, onReady: (Boolean) -> Unit, content: @Composable () -> Unit) {
    if (!browserKernelDownloadRequired) { SideEffect { onReady(true) }; content(); return }
    val environment = rememberBrowserKernelEnvironment()
    val net = koinInject<Net>()
    val client = remember(net) { net.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build() }
    val source = remember(client) { GiteeBrowserKernelSource(client) }
    val metadataClient = remember(client) { client.newBuilder().callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }
    BrowserKernelGateContent(environment, client, source, active, onReady, discover = { session ->
        val release = source.latest(session)
        BrowserKernelDiscovery(BrowserKernelUpdateResolver(metadataClient).resolve(environment.requirement, release, session), release.source())
    }, content = content)
}

@Composable
internal fun BrowserKernelGateContent(environment: BrowserKernelEnvironment, client: okhttp3.OkHttpClient,
    source: BrowserKernelSource, active: Boolean, onReady: (Boolean) -> Unit,
    discover: ((UpdateDownloadSession) -> BrowserKernelDiscovery)? = null, content: @Composable () -> Unit) {
    val manager = remember(environment) { BrowserKernelManager(environment.root, client) }
    var ready by remember(environment) { mutableStateOf(false) }
    var checking by remember(environment) { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var restartNeeded by remember { mutableStateOf(false) }
    var clientUpdateRequired by remember { mutableStateOf(false) }
    var local by remember(environment) { mutableStateOf<BrowserKernelRequirement?>(null) }
    var target by remember(environment) { mutableStateOf<BrowserKernelRequirement?>(null) }
    var downloadSource by remember(environment) { mutableStateOf(source) }
    var recheck by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<BrowserKernelProgress?>(null) }
    val scope = rememberCoroutineScope()
    var cancellation by remember { mutableStateOf<UpdateDownloadSession?>(null) }

    suspend fun enable(requirement: BrowserKernelRequirement, directory: File = File(environment.root, requirement.id)) {
        val binding = withContext(Dispatchers.IO) { BrowserKernelRuntime.bindVerified(environment.requirement.id, requirement, directory, manager) }
        local = binding.requirement ?: environment.requirement
        ready = true
    }
    LaunchedEffect(environment, active, recheck) {
        if (!active) { cancellation?.cancel(); return@LaunchedEffect }
        cancellation?.cancel()
        running = false; progress = null
        checking = true; ready = false; failed = false; restartNeeded = false; clientUpdateRequired = false; target = null
        val session = UpdateDownloadSession().also { cancellation = it }
        try {
            val cached = withContext(Dispatchers.IO) {
                manager.newestInstalled(environment.requirement) ?: environment.legacyDirectory?.let {
                    manager.adoptLegacy(environment.requirement, it)?.let { environment.requirement }
                }
            }
            val bound = BrowserKernelRuntime.binding(environment.requirement.id)
            local = bound?.let { it.requirement ?: environment.requirement } ?: cached
            val discovery = try { withContext(Dispatchers.IO) { discover?.invoke(session) } }
            catch (error: Exception) {
                if (error is CancellationException || session.isCancelled) throw error
                org.slf4j.LoggerFactory.getLogger("browser-kernel").warn("Cannot check latest browser component; retaining verified local version", error)
                if (local == null) throw error
                null
            }
            clientUpdateRequired = discovery?.update?.newerClientRequired == true
            downloadSource = discovery?.source ?: source
            val remote = discovery?.update?.requirement
            val selected = listOfNotNull(cached, local, remote).maxByOrNull { requireNotNull(BrowserKernelVersion.parse(it.manifest.componentVersion)) }
                ?: if (discover == null) environment.requirement else throw java.io.IOException("Latest Release has no compatible browser component")
            if (remote != null && local?.manifest?.componentVersion == remote.manifest.componentVersion && local?.manifestSha256 != remote.manifestSha256) {
                throw java.io.IOException("Browser component changed without a version bump")
            }
            target = selected
            val installed = withContext(Dispatchers.IO) { manager.installed(selected) }
            if (installed != null) {
                if (bound != null && selected.id != local?.id) restartNeeded = true
                else enable(selected, installed)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!session.isCancelled) {
                failed = true
                org.slf4j.LoggerFactory.getLogger("browser-kernel").warn("Cannot prepare browser component", error)
            }
        } finally {
            checking = false
            if (cancellation === session) cancellation = null
        }
    }
    // Account changes replace the caller's browser state while the verified runtime stays ready.
    // Notify the current callback on every composition, including that replacement.
    SideEffect { onReady(ready) }
    val currentOnReady by rememberUpdatedState(onReady)
    DisposableEffect(environment) { onDispose { cancellation?.cancel(); currentOnReady(false) } }
    if (ready) {
        if (clientUpdateRequired) Column(Modifier.fillMaxSize()) {
            Text(readI18n("browser.kernelClientUpdate"), modifier = Modifier.padding(12.dp))
            Box(Modifier.weight(1f)) { content() }
        } else content()
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(readI18n("browser.kernelTitle"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(readI18n(if (checking) "browser.kernelChecking" else if (restartNeeded) "browser.kernelRestart" else if (failed) "browser.kernelFailed" else if (running) "browser.kernelPreparing" else if (local != null) "browser.kernelUpdateRequired" else "browser.kernelRequired"))
        if (clientUpdateRequired) Text(readI18n("browser.kernelClientUpdate"))
        val desired = target ?: environment.requirement
        Text(readI18n("browser.kernelVersion") + " " + (local?.manifest?.componentVersion?.let { "$it → " } ?: "") + desired.manifest.componentVersion)
        if (!restartNeeded) Text("${desired.manifest.parts.sumOf { it.size } / (1024 * 1024)} MiB")
        if (running) {
            val update = progress
            Spacer(Modifier.height(12.dp))
            if (update != null && update.total > 0 && update.stage == BrowserKernelStage.DOWNLOADING) {
                LinearProgressIndicator(progress = { (update.bytes.toFloat() / update.total).coerceIn(0f, 1f) }, modifier = Modifier.width(280.dp))
                Text("${update.bytes / (1024 * 1024)} / ${update.total / (1024 * 1024)} MiB")
            }
        }
        if (!checking && !restartNeeded) {
            TextButton(onClick = {
                if (running) { cancellation?.cancel(); return@TextButton }
                if (failed && discover != null) { recheck++; return@TextButton }
                val requirement = target
                if (requirement == null) { recheck++; return@TextButton }
                val session = UpdateDownloadSession().also { cancellation = it }
                running = true; failed = false
                scope.launch {
                    try {
                        val installed = withContext(Dispatchers.IO) {
                            manager.install(requirement, downloadSource, session) { update ->
                                scope.launch { if (cancellation === session) progress = update }
                            }
                        }
                        val bound = BrowserKernelRuntime.binding(environment.requirement.id)
                        if (bound != null && (bound.requirement ?: environment.requirement).id != requirement.id) restartNeeded = true
                        else enable(requirement, installed)
                    } catch (error: Exception) {
                        if (!session.isCancelled && error !is CancellationException) {
                            org.slf4j.LoggerFactory.getLogger("browser-kernel").error("Cannot prepare browser kernel ${environment.requirement.id}", error)
                            failed = true
                        }
                    } finally { if (cancellation === session) { running = false; cancellation = null; progress = null } }
                }
            }) { Text(readI18n(if (running) "browser.kernelCancel" else if (failed) "browser.kernelRetry" else if (local != null) "browser.kernelUpgrade" else "browser.kernelDownload")) }
        }
        if (!checking && !running && local != null) TextButton(onClick = {
            val current = local ?: return@TextButton
            scope.launch { enable(current) }
        }) { Text(readI18n("browser.kernelUseCurrent")) }
    }
}
