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
import java.nio.file.AccessDeniedException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val failure = AtomicReference<BrowserKernelException?>(null)
        val blocked = AtomicBoolean(false)
        val client = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = if (exchange.requestURI.path.endsWith(".json")) manifests.getValue(exchange.requestURI.path.removePrefix("/")).toByteArray() else zip
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
            }
            start()
        }
        val source = BrowserKernelSource { _, name, session ->
            calls.incrementAndGet()
            while (blocked.get()) { session.checkActive(); Thread.sleep(10) }
            failure.get()?.let { throw it }
            if (fail.get()) throw IOException("Fixture failure")
            "http://127.0.0.1:${server.address.port}/$name"
        }
        init { i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText()) }
        override fun close() { blocked.set(false); server.stop(0); root.deleteRecursively() }
    }

    @Test fun consentRetryAndReplacementCallerReceiveReadyState(): Unit = Fixture().use { f ->
        runDesktopComposeUiTest {
            var consumer by mutableStateOf(0)
            val first = AtomicBoolean(false); val replacement = AtomicBoolean(false)
            val checks = AtomicInteger()
            setContent { MaterialTheme {
                val receiver = consumer
                BrowserKernelGateContent(f.environment, f.client, f.source, true,
                    { if (receiver == 0) first.set(it) else replacement.set(it) },
                    discover = { checks.incrementAndGet(); BrowserKernelDiscovery(BrowserKernelUpdate(f.requirement), f.source) }) { Text("Browser ready") }
            } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { assertEquals(0, f.calls.get()); assertFalse(first.get()) }
            onNodeWithText("Browser ready").assertDoesNotExist()
            f.fail.set(true); onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("无法下载浏览器组件，请检查网络连接后重试。").assertIsDisplayed()
            runOnIdle { assertFalse(first.get()) }
            f.fail.set(false); onNodeWithText("重试").performClick()
            waitUntil(timeoutMillis = 5000) { first.get() }
            onNodeWithText("Browser ready").assertIsDisplayed()
            runOnIdle { assertEquals(1, checks.get(), "An installation retry must proceed without another consent click") }
            runOnIdle { consumer = 1 }
            waitUntil(timeoutMillis = 5000) { replacement.get() }
        }
    }
    @Test fun failureKindsShowTheirOwnReasonAndKeepRetryVisibleInASmallViewport() {
        val explanations = mapOf(
            BrowserKernelFailureKind.NETWORK to "无法下载浏览器组件，请检查网络连接后重试。",
            BrowserKernelFailureKind.VERIFICATION to "浏览器组件校验失败，请重试；若持续失败，请联系维护者检查发布文件。",
            BrowserKernelFailureKind.FILE_ACCESS to "无法访问组件文件，请检查路径权限、文件占用或安全软件拦截记录后重试。",
            BrowserKernelFailureKind.INSTALLATION to "浏览器组件安装失败，请查看下方原因后重试。",
        )
        for ((kind, explanation) in explanations) Fixture().use { f ->
            val path = File(f.root, "nested/".repeat(16) + "chrome_elf.dll").absolutePath
            val reason = if (kind == BrowserKernelFailureKind.FILE_ACCESS) "Access is denied" else "Fixture ${kind.name.lowercase()} failure"
            f.failure.set(BrowserKernelException(kind, BrowserKernelStage.INSTALLING,
                if (kind == BrowserKernelFailureKind.FILE_ACCESS) path else null, reason, reason, IOException(reason)))
            runDesktopComposeUiTest(width = 360, height = 240) {
                val ready = AtomicBoolean(false)
                setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Browser ready") } } }
                waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("下载并启用").performClick()
                waitUntil(timeoutMillis = 5000) { onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(explanation).assertExists()
                onNodeWithTag("browser-kernel-failure-details").assertTextContains(reason, substring = true)
                if (kind == BrowserKernelFailureKind.FILE_ACCESS) {
                    onNodeWithTag("browser-kernel-failure-details").assertTextContains(path, substring = true)
                }
                onNodeWithTag("browser-kernel-body").assert(hasScrollAction())
                onNodeWithText("重试").assertIsDisplayed()
                onNodeWithText("浏览器组件准备失败，请检查网络后重试。").assertDoesNotExist()
                runOnIdle { assertFalse(ready.get()) }
            }
        }
    }
    @Test fun cancellingPreparationDoesNotShowAnInstallationFailure(): Unit = Fixture().use { f ->
        f.blocked.set(true)
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Browser ready") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { f.calls.get() > 0 }
            onNodeWithText("取消").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("browser-kernel-failure-details").assertDoesNotExist()
            onNodeWithText("重试").assertDoesNotExist()
            runOnIdle { assertFalse(ready.get()) }
            f.blocked.set(false)
            onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
        }
    }
    @Test fun transientFileAccessShowsRetryAttemptsThenReturnsToPreparationAndBecomesReady(): Unit = Fixture().use { f ->
        val path = File(f.root, "chrome_elf.dll").absolutePath
        f.failure.set(BrowserKernelException.from(AccessDeniedException(path, null, "Access is denied"), BrowserKernelStage.INSTALLING))
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Browser ready") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("组件文件暂时无法访问，正在自动重试（1/3）…").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("取消").assertIsDisplayed()
            onNodeWithTag("browser-kernel-failure-details").assertDoesNotExist()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("组件文件暂时无法访问，正在自动重试（2/3）…").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { f.blocked.set(true); f.failure.set(null) }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("正在准备浏览器组件…").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("取消").assertIsDisplayed()
            onNodeWithTag("browser-kernel-failure-details").assertDoesNotExist()
            runOnIdle { assertFalse(ready.get()); f.blocked.set(false) }
            waitUntil(timeoutMillis = 5000) { ready.get() }
            onNodeWithText("Browser ready").assertIsDisplayed()
        }
    }
    @Test fun cancellingAnAutomaticFileAccessRetryDoesNotShowFailureOrInstall(): Unit = Fixture().use { f ->
        val path = File(f.root, "chrome_elf.dll").absolutePath
        f.failure.set(BrowserKernelException.from(AccessDeniedException(path, null, "Access is denied"), BrowserKernelStage.INSTALLING))
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Browser ready") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("组件文件暂时无法访问，正在自动重试（1/3）…").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("取消").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("browser-kernel-failure-details").assertDoesNotExist()
            onNodeWithText("重试").assertDoesNotExist()
            runOnIdle {
                assertFalse(ready.get())
                assertNull(BrowserKernelManager(f.root, f.client).installed(f.requirement))
            }
        }
    }
    @Test fun exhaustedAutomaticRetriesKeepTheFileReasonAndAllowManualRetry(): Unit = Fixture().use { f ->
        val path = File(f.root, "chrome_elf.dll").absolutePath
        f.failure.set(BrowserKernelException.from(AccessDeniedException(path, null, "Access is denied"), BrowserKernelStage.INSTALLING))
        runDesktopComposeUiTest {
            val ready = AtomicBoolean(false)
            setContent { MaterialTheme { BrowserKernelGateContent(f.environment, f.client, f.source, true, ready::set) { Text("Browser ready") } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("下载并启用").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("下载并启用").performClick()
            waitUntil(timeoutMillis = 12000) { onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("无法访问组件文件，请检查路径权限、文件占用或安全软件拦截记录后重试。").assertIsDisplayed()
            onNodeWithTag("browser-kernel-failure-details").assertTextContains(path, substring = true)
                .assertTextContains("Access is denied", substring = true)
            runOnIdle { assertFalse(ready.get()); f.failure.set(null) }
            onNodeWithText("重试").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
            onNodeWithText("Browser ready").assertIsDisplayed()
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
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(ready.get()); assertEquals(0, f.calls.get())
            onNodeWithText("继续使用当前版本").performClick()
            waitUntil(timeoutMillis = 5000) { ready.get() }
            assertEquals(local, BrowserKernelRuntime.binding(local.id)?.requirement)
        }
    }
}
