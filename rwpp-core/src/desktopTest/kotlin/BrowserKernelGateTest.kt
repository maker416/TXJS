/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.platform

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.net.browser.*
import net.peanuuutz.tomlkt.Toml
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BrowserKernelGateTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("kernel-gate-ui").toFile()
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val data = byteArrayOf(1, 2, 3)
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("kernel.bin")); z.write(data); z.closeEntry() } }.toByteArray()
        val arch = java.util.UUID.randomUUID().toString()
        val id = "test-1-v1-desktop-$arch"
        val manifest = """{"format":1,"id":"$id","engine":"test","version":"1","platform":"desktop","arch":"$arch","releaseTag":"v1","sha256":"${sha(zip)}","parts":[{"name":"engine.kernel.001","size":${zip.size},"sha256":"${sha(zip)}"}],"files":[{"path":"kernel.bin","size":3,"sha256":"${sha(data)}"}]}"""
        val requirement = BrowserKernelRequirement.read("""{"id":"$id","releaseTag":"v1","manifest":"engine.json","manifestSha256":"${sha(manifest.toByteArray())}","embeddedManifest":$manifest}""")
        val environment = BrowserKernelEnvironment(requirement, root)
        val manifests = java.util.concurrent.ConcurrentHashMap<String, String>().apply { put("engine.json", manifest) }
        fun component(version: String): BrowserKernelRequirement {
            val base = "RWJS-Browser-test-desktop-$arch-v$version"
            val text = """{"format":1,"id":"$id-k$version","engine":"test","version":"1","platform":"desktop","arch":"$arch","releaseTag":"latest","sha256":"${sha(zip)}","parts":[{"name":"$base.kernel.001","size":${zip.size},"sha256":"${sha(zip)}"}],"files":[{"path":"kernel.bin","size":3,"sha256":"${sha(data)}"}],"componentVersion":"$version"}"""
            manifests["$base.json"] = text
            return BrowserKernelRequirement.fromManifest(text, "$base.json")
        }
        val calls = AtomicInteger()
        val fail = AtomicBoolean(false)
        val client = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = if (exchange.requestURI.path.endsWith(".json")) manifests.getValue(exchange.requestURI.path.removePrefix("/")).toByteArray() else zip
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
            }
            start()
        }
        val source = BrowserKernelSource { _, name, _ ->
            calls.incrementAndGet()
            if (fail.get()) throw IOException("Fixture failure")
            "http://127.0.0.1:${server.address.port}/$name"
        }
        init { i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText()) }
        override fun close() { server.stop(0); root.deleteRecursively() }
    }

    @Test fun consentRetryAndReplacementCallerReceiveReadyState(): Unit = Fixture().use { f ->
        runDesktopComposeUiTest {
            var consumer by mutableStateOf(0)
            val first = AtomicBoolean(false); val replacement = AtomicBoolean(false)
            setContent { MaterialTheme {
                val receiver = consumer
                BrowserKernelGateContent(f.environment, f.client, f.source, true,
                    { if (receiver == 0) first.set(it) else replacement.set(it) }) { Text("Browser ready") }
            } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { assertEquals(0, f.calls.get()); assertFalse(first.get()) }
            onNodeWithText("Browser ready").assertDoesNotExist()
            f.fail.set(true); onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("重试下载").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { assertFalse(first.get()) }
            f.fail.set(false); onNodeWithText("重试下载").performClick()
            waitUntil(timeoutMillis = 5000) { first.get() }
            onNodeWithText("Browser ready").assertIsDisplayed()
            runOnIdle { consumer = 1 }
            waitUntil(timeoutMillis = 5000) { replacement.get() }
        }
    }
    @Test fun installedComponentNeedsNoNetworkOrConsent(): Unit = Fixture().use { f ->
        BrowserKernelManager(f.root, f.client).install(f.requirement, f.source, UpdateDownloadSession())
        f.calls.set(0); f.fail.set(true)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Offline browser ready") } } }
            waitUntil(timeoutMillis = 5000) { ready.get() }
            onNodeWithText("Offline browser ready").assertIsDisplayed()
            runOnIdle { assertEquals(0, f.calls.get()) }
        }
    }
    @Test fun latestCompatibleVersionPromptsBeforeDownloadingAndActivatesOnFirstUse(): Unit = Fixture().use { f ->
        val old = f.component("1.0.0"); val newer = f.component("1.1.0")
        val manager = BrowserKernelManager(f.root, f.client)
        manager.install(old, f.source, UpdateDownloadSession()); f.calls.set(0)
        val env = f.environment.copy(requirement = old)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(env, f.client, f.source, true, ready::set,
                discover = { BrowserKernelDiscovery(BrowserKernelUpdate(newer), f.source) }) { Text("Updated browser") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载升级").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { assertFalse(ready.get()); assertEquals(0, f.calls.get()) }
            onNodeWithText("下载升级").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
            assertEquals(newer, BrowserKernelRuntime.binding(old.id)?.requirement)
            assertEquals(newer.id, File(f.root, ".active-test-1-v1-desktop-${f.arch}").readText())
        }
    }
    @Test fun updateOfAnAlreadyLoadedRuntimeIsStagedUntilRestart(): Unit = Fixture().use { f ->
        val old = f.component("1.0.0"); val newer = f.component("1.1.0")
        val manager = BrowserKernelManager(f.root, f.client)
        val directory = manager.install(old, f.source, UpdateDownloadSession())
        BrowserKernelRuntime.bindVerified(old.id, old, directory, manager)
        val env = f.environment.copy(requirement = old)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(env, f.client, f.source, true, ready::set,
                discover = { BrowserKernelDiscovery(BrowserKernelUpdate(newer), f.source) }) { Text("Existing browser") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载升级").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("下载升级").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("新组件已下载并校验。当前进程仍使用旧内核，请重启客户端后启用新版本。").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { assertFalse(ready.get()); assertEquals(old, BrowserKernelRuntime.binding(old.id)?.requirement) }
            assertEquals(newer, manager.newestInstalled(old))
            assertEquals(old.id, File(f.root, ".active-test-1-v1-desktop-${f.arch}").readText())
            onNodeWithText("继续使用当前版本").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
        }
    }
    @Test fun failedLatestCheckStillAllowsVerifiedOfflineUse(): Unit = Fixture().use { f ->
        val local = f.component("1.0.0")
        BrowserKernelManager(f.root, f.client).install(local, f.source, UpdateDownloadSession()); f.calls.set(0)
        val checks = AtomicInteger()
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment.copy(requirement = local), f.client, f.source, true, ready::set,
                discover = { checks.incrementAndGet(); throw IOException("Offline") }) { Text("Offline after check") } } }
            waitUntil(timeoutMillis = 5000) { ready.get() }
            assertEquals(1, checks.get()); assertEquals(0, f.calls.get())
        }
    }
    @Test fun anOlderLatestReleaseDoesNotDowngradeLocalComponent(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0"); val newer = f.component("1.2.0")
        BrowserKernelManager(f.root, f.client).install(newer, f.source, UpdateDownloadSession()); f.calls.set(0)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment.copy(requirement = baseline), f.client, f.source, true, ready::set,
                discover = { BrowserKernelDiscovery(BrowserKernelUpdate(baseline), f.source) }) { Text("Keep newer local component") } } }
            waitUntil(timeoutMillis = 5000) { ready.get() }
            assertEquals(newer, BrowserKernelRuntime.binding(baseline.id)?.requirement); assertEquals(0, f.calls.get())
        }
    }
    @Test fun returningToThePageChecksForUpdatesAgain(): Unit = Fixture().use { f ->
        val local = f.component("1.0.0"); val newer = f.component("1.1.0")
        BrowserKernelManager(f.root, f.client).install(local, f.source, UpdateDownloadSession())
        val checks = AtomicInteger()
        val available = java.util.concurrent.atomic.AtomicReference(local)
        runDesktopComposeUiTest {
            var active by mutableStateOf(true)
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment.copy(requirement = local), f.client, f.source, active, ready::set,
                discover = { checks.incrementAndGet(); BrowserKernelDiscovery(BrowserKernelUpdate(available.get()), f.source) }) { Text("Open browser") } } }
            waitUntil(timeoutMillis = 5000) { ready.get() }
            runOnIdle { active = false }
            runOnIdle { available.set(newer); active = true }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载升级").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, checks.get()); assertFalse(ready.get())
        }
    }
    @Test fun changedContentsWithTheSameVersionNeverReplaceLocalComponent(): Unit = Fixture().use { f ->
        val local = f.component("1.0.0")
        BrowserKernelManager(f.root, f.client).install(local, f.source, UpdateDownloadSession()); f.calls.set(0)
        val changed = BrowserKernelRequirement.fromManifest(local.manifestJson.replaceFirst(local.manifest.sha256, "0".repeat(64)), local.manifestName)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment.copy(requirement = local), f.client, f.source, true, ready::set,
                discover = { BrowserKernelDiscovery(BrowserKernelUpdate(changed), f.source) }) { Text("Keep trusted local content") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("重试下载").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(ready.get()); assertEquals(0, f.calls.get())
            onNodeWithText("继续使用当前版本").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
            assertEquals(local, BrowserKernelRuntime.binding(local.id)?.requirement)
        }
    }
}
