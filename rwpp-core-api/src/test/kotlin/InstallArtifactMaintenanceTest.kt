/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.InstallArtifactMaintenance
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallArtifactMaintenanceTest {
    private fun inInstallation(test: (File, InstallArtifactMaintenance) -> Unit) {
        val root = Files.createTempDirectory("rwpp-update-test").toFile()
        try {
            test(root, maintenance(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun maintenance(root: File) = InstallArtifactMaintenance(
        File(root, "private/files"), File(root, "private/app_dexfiles"), File(root, "private/cache")
    )

    private fun write(root: File, path: String, content: String = "keep"): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    @Test fun firstUpgradeRemovesOnlyGeneratedArtifactsAndDefersPublicResources() = inInstallation { root, maintenance ->
        val jar = write(root, "private/files/generated_lib/android-game-lib.jar")
        val dex = write(root, "private/app_dexfiles/classes.dex")
        val extensionDex = write(root, "private/app_dexfiles/classes-old-extension.dex")
        val overlay = write(root, "shared/rustedWarfare/resource_generated/units/old.png")
        val temporaryOverlay = write(root, "shared/rustedWarfare/resource_generated_tmp/units/partial.png")
        val userFiles = listOf(
            "private/shared_prefs/settings.xml", "private/shared_prefs/account.xml",
            "private/files/mozilla/profile/cookies.sqlite", "private/files/mozilla/profile/storage/site",
            "external-app/files/units/host.network.rwmod", "shared/rustedWarfare/units/user.rwmod",
            "shared/rustedWarfare/maps/user.tmx", "shared/rustedWarfare/saves/game",
            "shared/rustedWarfare/replays/game.replay", "shared/rustedWarfare/themes/custom/game/units/new.png",
            "shared/rustedWarfare/extension/custom.rwres"
        ).map { write(root, it) }

        assertTrue(maintenance.prepareGeneratedArtifacts("new-install"))
        listOf(jar, dex, extensionDex).forEach { assertFalse(it.exists(), it.path) }
        assertTrue(overlay.exists(), "Public resources wait until permission is available")
        assertTrue(maintenance.prepareResources("new-install", overlay.parentFile.parentFile))
        assertFalse(overlay.exists())
        assertFalse(temporaryOverlay.exists())
        userFiles.forEach { assertEquals("keep", it.readText(), it.path) }
    }

    @Test fun rebuildingAndRestartingSameInstallKeepsNewArtifactsButNextInstallInvalidatesThem() = inInstallation { root, maintenance ->
        val resources = File(root, "shared/rustedWarfare/resource_generated")
        maintenance.prepareGeneratedArtifacts("install-1")
        maintenance.prepareResources("install-1", resources)
        val jar = write(root, "private/files/generated_lib/android-game-lib.jar", "new")
        val dex = write(root, "private/app_dexfiles/classes.dex", "new")
        val overlay = write(root, "shared/rustedWarfare/resource_generated/units/new.png", "new")

        val restarted = maintenance(root)
        assertFalse(restarted.prepareGeneratedArtifacts("install-1"))
        assertFalse(restarted.prepareResources("install-1", resources))
        listOf(jar, dex, overlay).forEach { assertEquals("new", it.readText()) }
        assertTrue(restarted.prepareGeneratedArtifacts("install-2"))
        assertTrue(restarted.prepareResources("install-2", resources))
        listOf(jar, dex, overlay).forEach { assertFalse(it.exists()) }
    }

    @Test fun failedCleanupDoesNotMarkInstallationComplete() = inInstallation { root, _ ->
        val obstruction = write(root, "blocked")
        val failing = InstallArtifactMaintenance(File(root, "private/files"), File(obstruction, "dex"), File(root, "private/cache"))
        assertFailsWith<IOException> { failing.prepareGeneratedArtifacts("install") }
        assertFalse(File(root, "private/files/.rwpp-artifacts-install").exists())
        assertTrue(obstruction.delete())
        assertTrue(failing.prepareGeneratedArtifacts("install"))
        assertFalse(failing.prepareGeneratedArtifacts("install"))
    }

    @Test fun normalStartupKeepsRecentInstallerApkAndUnrelatedCache() = inInstallation { root, maintenance ->
        val now = System.currentTimeMillis()
        val recent = write(root, "private/cache/rwpp-update-recent.apk")
        val expired = write(root, "private/cache/rwpp-update-old.apk").apply { setLastModified(now - 25L * 60 * 60 * 1000) }
        val upload = write(root, "private/cache/rwjs-browser-upload-abandoned/mod/snapshot.rwmod")
        val jar = write(root, "private/cache/android-game-lib123.jar")
        val unrelated = write(root, "private/cache/user-file.apk")
        maintenance.cleanTemporaryFiles(false, now)
        assertTrue(recent.exists(), "The system installer may still be reading a recent APK")
        listOf(expired, upload, jar).forEach { assertFalse(it.exists()) }
        assertEquals("keep", unrelated.readText())
        maintenance.cleanTemporaryFiles(true, now)
        assertFalse(recent.exists())
        assertEquals("keep", unrelated.readText())
    }
}
