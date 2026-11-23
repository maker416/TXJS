/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import io.github.rwpp.appKoin
import io.github.rwpp.AppContext
import io.github.rwpp.config.Settings
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.expansion.ExpansionPackageInstaller
import io.github.rwpp.expansion.InstalledExpansion
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModSettingsSnapshot
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.gameVersion
import io.github.rwpp.expansion.EXPANSION_MEDIA_TYPE
import io.github.rwpp.net.sync.direct.DIRECT_SYNC_ONLY_CAPABILITY
import io.github.rwpp.net.sync.direct.DirectContentArtifact
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.personalizedThemeCatalog
import io.github.rwpp.theme.validateThemeToml
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExpansionContentControllerTest {
    private lateinit var root: File
    private lateinit var installed: InstalledExpansion
    private lateinit var settings: Settings
    private lateinit var nativeMods: MutableList<Mod>
    private val baseline = ModSettingsSnapshot("ordinary:enabled,network:enabled,other:disabled", 2, 3)
    private var persisted = baseline
    private var loads = 0
    private var failExpansion = false
    private var recoveryBlocked = false
    private var singlePlayerPaused = false
    private var token: Long? = null
    private val gameId = "11111111-1111-1111-1111-111111111111"

    @BeforeTest fun setup() = runBlocking {
        runCatching { stopKoin() }
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        root = Files.createTempDirectory("rwjs-expansion-transaction-").toFile()
        settings = Settings(language = "zh", autoCheckUpdate = false, launcherMusicVolume = 0f)
        logger = LoggerFactory.getLogger("ExpansionContentControllerTest")
        nativeMods = mutableListOf(mod(File(root, "ordinary/sample.rwmod"), true),
            mod(File(root, "ordinary/network.network.rwmod"), true), mod(File(root, "ordinary/disabled.rwmod"), false))
        val manager = Proxy.newProxyInstance(ModManager::class.java.classLoader, arrayOf(ModManager::class.java)) { _, method, args ->
            when (method.name) {
                "getAllMods" -> nativeMods.toList()
                "captureModSettings" -> persisted
                "restoreModSettings" -> { persisted = args!![0] as ModSettingsSnapshot; Unit }
                "applyContentProfile" -> {
                    val profile = args!![0] as ContentProfile
                    loads++
                    ContentProfiles.stage(profile)
                    if (profile is ContentProfile.Expansion) {
                        if (failExpansion) throw IllegalStateException("Fixture parser refused expansion")
                        nativeMods.removeAll { it.isExpansionMod }
                        nativeMods += profile.sources.map { mod(it.file, false) }
                    }
                    nativeMods.forEach { it.isEnabled = ContentProfiles.resolve(listOf(it.path)) ?: it.isEnabled }
                    Unit
                }
                else -> error("Unexpected manager method ${method.name}")
            }
        } as ModManager
        val context = Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, args ->
            when (method.name) {
                "internalStoragePath", "externalStoragePath" -> {
                    if (recoveryBlocked) File(root, "blocked-storage").path else File(root, args!![0] as String).path
                }
                "isDesktop" -> true
                "isAndroid" -> false
                "toString" -> "ExpansionFixtureContext"
                else -> error("Unexpected context method ${method.name}")
            }
        } as AppContext
        val game = Proxy.newProxyInstance(Game::class.java.classLoader, arrayOf(Game::class.java)) { _, method, _ ->
            when (method.name) {
                "isGameCouldContinue" -> singlePlayerPaused
                else -> error("Unexpected game method ${method.name}")
            }
        } as Game
        appKoin = startKoin {
            modules(module {
                single { settings }
                single<ModManager> { manager }
                single<AppContext> { context }
                single<Game> { game }
            })
        }.koin
        koinInit = true
        ExpansionPackages.rootOverride = File(root, "expansions")
        ArtThemeController.themeRootOverride = File(root, "themes")
        File(root, "themes/ordinary/theme.toml").apply { parentFile.mkdirs(); writeText("[theme]\nid=\"ordinary\"\nname=\"Ordinary\"\n") }
        installed = packageFile()
        withContext(Dispatchers.Main) {
            ArtThemeController.scan()
            ArtThemeController.apply("ordinary")
            ExpansionContentController.init()
            GameSessionController.onExternalSessionEnd()
        }
    }

    @AfterTest fun cleanup() = runBlocking {
        token?.let { ExpansionContentController.cancel(it) }
        withContext(Dispatchers.Main) {
            ArtThemeController.apply(null)
            ArtThemeController.themeRootOverride = null
            GameSessionController.onExternalSessionEnd()
        }
        ExpansionPackages.rootOverride = null
        ContentProfiles.finishNormal()
        root.deleteRecursively()
        stopKoin(); koinInit = false
        Dispatchers.resetMain()
    }

    @Test fun oneLoadActivatesOnlyPackageSourcesAndDisconnectRestoresTheActualOrdinarySelection() = runBlocking {
        val ordinaryTheme = ArtThemeController.activeTheme
        val current = ExpansionContentController.prepare(installed, gameId).also { token = it }
        assertSame(ordinaryTheme, ArtThemeController.activeTheme)
        GameSessionController.beginSession(allowExpansion = true) {
            ExpansionContentController.activate(current)
            ExpansionContentController.activate(current)
            withContext(Dispatchers.Main) { ExpansionContentController.onRoomOpened(current); GameSessionController.onRoomOpened() }
        }
        assertEquals(1, loads)
        assertEquals(installed.modFiles.map { it.canonicalPath }.toSet(), nativeMods.filter { it.isEnabled }.map { it.path }.toSet())
        assertTrue(ExpansionContentController.isActive(installed.sha256))
        assertEquals(baseline, persisted)
        assertEquals("ordinary", settings.selectedThemePack)
        assertFalse(File(root, "ordinary/mods/sample.rwmod").exists())
        DisconnectEvent("test").broadcast()
        assertEquals(2, loads)
        assertNull(ExpansionContentController.activeSha)
        assertSame(ordinaryTheme, ArtThemeController.activeTheme)
        assertTrue(nativeMods.filter { !it.isExpansionMod && !it.path.endsWith("disabled.rwmod") }.all { it.isEnabled })
        assertFalse(nativeMods.single { it.path.endsWith("disabled.rwmod") }.isEnabled)
        assertFalse(nativeMods.filter { it.isExpansionMod }.any { it.isEnabled })
        assertEquals(baseline, persisted)
    }

    @Test fun loadingFailureRestoresBaselineWithoutCommittingAPartialThemeOrPackage() = runBlocking {
        val original = ArtThemeController.activeTheme
        failExpansion = true
        val current = ExpansionContentController.prepare(installed, gameId).also { token = it }
        assertFailsWith<IllegalStateException> { GameSessionController.beginSession(allowExpansion = true) { ExpansionContentController.activate(current) } }
        assertEquals(2, loads)
        assertNull(ExpansionContentController.activeSha)
        assertSame(original, ArtThemeController.activeTheme)
        assertEquals(baseline, persisted)
        assertFalse(ContentProfiles.expansionActive)
    }

    @Test fun staleCancellationCannotUndoANewerPreparedOrLoadedGame() = runBlocking {
        val old = ExpansionContentController.prepare(installed, gameId)
        val current = ExpansionContentController.prepare(installed, gameId).also { token = it }
        GameSessionController.beginSession(allowExpansion = true) { ExpansionContentController.activate(current) }
        ExpansionContentController.cancel(old)
        assertTrue(ExpansionContentController.isActive(installed.sha256))
        assertEquals(1, loads)
        ExpansionContentController.cancel(current)
        assertNull(ExpansionContentController.activeSha)
        assertEquals(2, loads)
    }

    @Test fun packageCannotBeActivatedAsAnOfflineContentModeOrForAnotherGame() = runBlocking {
        assertFailsWith<IOException> { ExpansionContentController.prepare(installed, "22222222-2222-2222-2222-222222222222") }
        val current = ExpansionContentController.prepare(installed, gameId).also { token = it }
        assertFailsWith<IllegalStateException> { ExpansionContentController.activate(current) }
        assertEquals(0, loads)
        assertNull(ExpansionContentController.activeSha)
    }

    @Test fun portalPreloadStaysIdleAndTransfersToRoomWithoutReloadingAgain() = runBlocking {
        val original = ArtThemeController.activeTheme
        val current = ExpansionContentController.prepareForPortal(installed, gameId).also { token = it }
        ExpansionContentController.activate(current)
        val handle = PortalExpansionHandle(PortalExpansionKey(gameId, installed.binding, "8.8.8.8:5125"), current, 1)
        assertEquals(GameSessionController.SessionPhase.IDLE, GameSessionController.sessionPhase)
        assertTrue(handle.isReady())
        assertEquals(1, loads)
        GameSessionController.beginSession(allowExpansion = true, portalExpansionToken = current) {
            assertTrue(handle.isReady())
        }
        assertTrue(handle.isReady(), "Failed room entry must leave the portal's preload in place")
        GameSessionController.beginSession(allowExpansion = true, portalExpansionToken = current) {
            withContext(Dispatchers.Main) {
                ExpansionContentController.onRoomOpened(current)
                GameSessionController.onRoomOpened()
            }
        }
        handle.close()
        assertTrue(ExpansionContentController.isActive(installed.sha256))
        assertEquals(1, loads)
        DisconnectEvent("test").broadcast()
        assertEquals(2, loads)
        assertSame(original, ArtThemeController.activeTheme)
    }

    @Test fun portalCannotReplaceAPausedSinglePlayerScene() = runBlocking {
        val current = ExpansionContentController.prepareForPortal(installed, gameId).also { token = it }
        singlePlayerPaused = true
        assertFailsWith<PortalExpansionPausedGameException> { ExpansionContentController.activate(current) }
        assertEquals(0, loads)
        assertNull(ExpansionContentController.activeSha)
    }

    @Test fun syncOnlyManifestMustMatchThePublishedArtifactAndHaveNoRoom() {
        val key = PortalExpansionKey(gameId, installed.binding, "8.8.8.8:5125")
        val artifact = DirectContentArtifact(installed.name, EXPANSION_MEDIA_TYPE, installed.version, installed.size, installed.sha256)
        val manifest = DirectModManifest(4, gameVersion.toString(), "expansion", "ready", false, -1, emptyList(),
            capabilities = DIRECT_SYNC_ONLY_CAPABILITY, contentRevision = installed.sha256, artifact = artifact)
        assertEquals(artifact, requirePortalExpansionManifest(manifest, key))
        for (changed in listOf(manifest.copy(capabilities = 0), manifest.copy(lobby = true),
            manifest.copy(hostUnitsChecksum = 1), manifest.copy(artifact = artifact.copy(size = artifact.size + 1)))) {
            assertFailsWith<IOException> { requirePortalExpansionManifest(changed, key) }
        }
    }

    @Test fun recoveryStorageFailureNeverTouchesTheEngineOrLeavesAStaleRestoreAttempt() = runBlocking {
        val original = ArtThemeController.activeTheme
        val current = ExpansionContentController.prepare(installed, gameId).also { token = it }
        File(root, "blocked-storage").writeText("not a directory")
        recoveryBlocked = true
        assertFailsWith<IOException> { GameSessionController.beginSession(allowExpansion = true) { ExpansionContentController.activate(current) } }
        assertEquals(0, loads)
        assertNull(ExpansionContentController.currentToken)
        assertNull(ExpansionContentController.activeSha)
        assertSame(original, ArtThemeController.activeTheme)
        assertEquals(baseline, persisted)
    }

    @Test fun cancellationDuringTokenDeliveryReleasesOnlyTheUnclaimedPreparation() = runBlocking {
        val delivery = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (ExpansionContentController.currentToken != null && context[Job]?.isActive == true) context[Job]?.cancel()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        supervisorScope {
            val attempt = async(delivery) { ExpansionContentController.prepare(installed, gameId) }
            assertFailsWith<CancellationException> { attempt.await() }
        }
        assertNull(ExpansionContentController.currentToken)
        assertFalse(ExpansionContentController.isReserved(installed.sha256))
        assertEquals(0, loads)
        val next = ExpansionContentController.prepare(installed, gameId).also { token = it }
        assertEquals(next, ExpansionContentController.currentToken)
    }

    private fun packageFile(): InstalledExpansion {
        val source = File(root, "source").apply { mkdirs() }
        val modRoot = File(root, "mod-source").apply { mkdirs() }
        File(modRoot, "mod-info.txt").writeText("[mod]\ntitle: Transaction fixture\n")
        val rwmod = File(source, "mods/sample.rwmod").apply { parentFile.mkdirs() }
        zip(modRoot, rwmod)
        val packId = "22222222-2222-2222-2222-222222222222"
        val theme = File(source, "theme").apply { mkdirs() }
        File(theme, "theme.toml").writeText("""
            [theme]
            id="expansion-theme"
            name="Expansion theme"
            version="1.0.0"
            [personalization]
            platformPackId="$packId"
            [personalization.slots."room.player.frame".variants.gold]
            borderColor="#FFD700"
        """.trimIndent())
        val spec = validateThemeToml(File(theme, "theme.toml").readText()).first!!
        val catalog = personalizedThemeCatalog(theme, spec.personalization)!!
        File(source, "version.txt").writeText("1.0.0\n")
        File(source, "expansion.toml").writeText("""
            schema_version=1
            game_app_id="$gameId"
            name="Transaction fixture"
            [[mods]]
            id="sample"
            path="mods/sample.rwmod"
            size=${rwmod.length()}
            sha256="${ExpansionPackageInstaller.hashFile(rwmod)}"
            tree_sha256="${ExpansionPackageInstaller.portableTreeSha256(modRoot)}"
            [theme]
            path="theme"
            pack_id="$packId"
            version="1.0.0"
            catalog_hash="${catalog.catalogHash}"
        """.trimIndent())
        val archive = File(root, "package.zip")
        zip(source, archive)
        return ExpansionPackageInstaller.install(archive, ExpansionPackages.rootDir)
    }

    private fun zip(source: File, target: File) = ZipOutputStream(target.outputStream()).use { zip ->
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun mod(file: File, enabled: Boolean): Mod = object : Mod {
        override val id = file.path.hashCode()
        override val name = file.name
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = enabled
        override val path = file.canonicalPath
        override fun getRamUsed() = "0"
        override fun getSize() = file.length()
        override fun getBytes() = byteArrayOf()
    }
}
