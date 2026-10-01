/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceBrowserOrientationTest {
    @Test
    fun oldSettingsStillAskOnFirstVisit() {
        val settings = Toml.decodeFromString(Settings.serializer(), "language = \"zh\"\nconfigVersion = 2")
        settings.migrate()
        assertNull(settings.resourceBrowserOrientation)
    }

    @Test
    fun savedChoiceSurvivesReloadAndMigration() {
        ResourceBrowserOrientation.entries.forEach { orientation ->
            val settings = Settings(resourceBrowserOrientation = orientation)
            val encoded = Toml.encodeToString(Settings.serializer(), settings)
            val restored = Toml.decodeFromString(Settings.serializer(), encoded)
            restored.migrate()
            assertEquals(orientation, restored.resourceBrowserOrientation)
        }
    }

    @Test
    fun resettingChoiceRestoresFirstVisitPromptAfterReload() {
        val settings = Settings(resourceBrowserOrientation = ResourceBrowserOrientation.Portrait)
        settings.resourceBrowserOrientation = null
        val encoded = Toml.encodeToString(Settings.serializer(), settings)
        assertNull(Toml.decodeFromString(Settings.serializer(), encoded).resourceBrowserOrientation)
    }
}
