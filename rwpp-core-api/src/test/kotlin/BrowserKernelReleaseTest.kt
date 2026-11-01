/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.net.browser.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BrowserKernelReleaseTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("kernel-release-test").toFile()
        val server = MockWebServer()
        val files = mutableMapOf<String, ByteArray>()
        val client = OkHttpClient()
        val manager = BrowserKernelManager(root, client)
        val source = BrowserKernelSource { _, name, _ -> require(files.containsKey(name)); server.url("/$name").toString() }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = files[request.path!!.removePrefix("/")]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
            server.start()
        }
        fun component(version: String, engineVersion: String = "157.0", format: Int = 1): BrowserKernelRequirement {
            val data = "native-$version".toByteArray()
            val zip = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { z -> z.putNextEntry(ZipEntry("lib/native.so")); z.write(data); z.closeEntry() } }.toByteArray()
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val base = "RWJS-Browser-gecko-android-arm64-v8a-v$version"
            val manifest = BrowserKernelManifest(format, "gecko-$engineVersion-v$format-android-arm64-v8a-k$version", "gecko", engineVersion, "android", "arm64-v8a", "latest", sha(zip),
                listOf(BrowserKernelPart("$base.kernel.001", zip.size.toLong(), sha(zip))), listOf(BrowserKernelFile("lib/native.so", data.size.toLong(), sha(data))), version)
            val text = Json.encodeToString(manifest)
            files["$base.json"] = text.toByteArray(); files["$base.kernel.001"] = zip
            return BrowserKernelRequirement.fromManifest(text, "$base.json")
        }
        fun release() = BrowserKernelRelease("new-client-tag", files.keys.map { BrowserKernelReleaseAsset(it, "https://gitee.com/assets/$it") })
        override fun close() { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun numericVersionsAreStrictAndNotLexicographic() {
        assertTrue(BrowserKernelVersion.parse("1.10.0")!! > BrowserKernelVersion.parse("1.9.99")!!)
        for (bad in listOf("1.0", "01.0.0", "1.0.0-beta", "1.-1.0", "999999999999.0.0", "../1.0.0")) assertNull(BrowserKernelVersion.parse(bad))
    }
    @Test fun filenamesSelectOnlyTheRequestedPlatformAndArchitecture(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0"); f.component("1.10.0"); f.component("1.9.0")
        val release = f.release().copy(assets = f.release().assets + BrowserKernelReleaseAsset("RWJS-Browser-gecko-android-x86_64-v9.0.0.json", "https://gitee.com/other"))
        assertEquals(listOf("1.10.0", "1.9.0", "1.0.0"), release.candidates(baseline).map { it.version.toString() })
        assertFails { release.copy(assets = release.assets + release.assets.first { it.name.endsWith(".json") }).candidates(baseline) }
    }
    @Test fun newestCompatibleComponentSkipsUnmatchedNativeApiAndFormat(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0"); val compatible = f.component("1.2.0")
        f.component("2.0.0", engineVersion = "158.0"); f.component("3.0.0", format = 2)
        val result = BrowserKernelUpdateResolver(f.client).resolve(baseline, f.release(), UpdateDownloadSession(), f.source)
        assertEquals(compatible, result.requirement); assertTrue(result.newerClientRequired)
    }
    @Test fun versionInFilenameMustMatchManifest(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0")
        val fake = baseline.manifestName.replace("v1.0.0", "v2.0.0")
        f.files[fake] = f.files.getValue(baseline.manifestName)
        assertFails { BrowserKernelUpdateResolver(f.client).resolve(baseline, f.release(), UpdateDownloadSession(), f.source) }
    }
    @Test fun missingVolumeAndInsecureOrDuplicateAssetsAreRejected(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0")
        f.files.remove(baseline.manifest.parts.single().name)
        assertFails { BrowserKernelUpdateResolver(f.client).resolve(baseline, f.release(), UpdateDownloadSession(), f.source) }
        val release = BrowserKernelRelease("latest", listOf(BrowserKernelReleaseAsset("test", "http://example.com/file")))
        assertFails { release.source().asset("old", "test", UpdateDownloadSession()) }
        assertFails { release.copy(assets = release.assets + release.assets).source().asset("old", "test", UpdateDownloadSession()) }
    }
    @Test fun latestApiIsUsedEvenWhenOldSourceTagWasDeleted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v9","assets":[{"name":"component","browser_download_url":"https://gitee.com/new/component"}]}"""))
            val source = GiteeBrowserKernelSource(OkHttpClient(), server.url("/releases/latest").toString())
            assertEquals("https://gitee.com/new/component", source.asset("deleted-tag", "component", UpdateDownloadSession()))
            assertEquals("/releases/latest", server.takeRequest().path)
        }
    }
    @Test fun refreshingLatestDoesNotChangeAnInFlightReleaseSnapshot() {
        MockWebServer().use { server ->
            for (version in listOf("v1", "v2")) server.enqueue(MockResponse().setBody("""{"tag_name":"$version","assets":[{"name":"component","browser_download_url":"https://gitee.com/$version/component"}]}"""))
            val source = GiteeBrowserKernelSource(OkHttpClient(), server.url("/releases/latest").toString())
            val first = source.latest(UpdateDownloadSession()).source()
            source.latest(UpdateDownloadSession())
            assertEquals("https://gitee.com/v1/component", first.asset("deleted", "component", UpdateDownloadSession()))
            assertEquals("https://gitee.com/v2/component", source.asset("deleted", "component", UpdateDownloadSession()))
        }
    }
    @Test fun installedNewVersionSurvivesRestartAndCorruptionFallsBack(): Unit = Fixture().use { f ->
        val baseline = f.component("1.0.0"); val newer = f.component("1.1.0")
        f.manager.install(baseline, f.source, UpdateDownloadSession())
        f.manager.install(newer, f.source, UpdateDownloadSession())
        val restarted = BrowserKernelManager(f.root, f.client)
        assertEquals(newer, restarted.newestInstalled(baseline))
        restarted.activate(newer)
        assertEquals(newer.id, File(f.root, ".active-gecko-157.0-v1-android-arm64-v8a").readText())
        File(f.root, "${newer.id}/lib/native.so").writeText("broken")
        assertEquals(baseline, restarted.newestInstalled(baseline))
    }
    @Test fun releaseCannotDowngradeBelowClientMinimumComponent(): Unit = Fixture().use { f ->
        val older = f.component("1.0.0"); val baseline = f.component("1.1.0")
        val result = BrowserKernelUpdateResolver(f.client).resolve(baseline, f.release().copy(assets = f.release().assets.filter { !it.name.contains("v1.1.0") }), UpdateDownloadSession(), f.source)
        assertNull(result.requirement)
        assertFalse(BrowserKernelManager.compatible(baseline, older))
    }
}
