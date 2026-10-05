/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.impl.AppContextImpl
import io.github.rwpp.desktop.impl.inject.CustomUnitLoadInject
import io.github.rwpp.modDir
import io.github.rwpp.internalModDir
import org.koin.core.Koin
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModDirectoryTest {
    @Test
    fun desktopUsesOnlyOriginalModDirectory() {
        val scratch = File("build/tmp/mod-directory-test").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "scan-").toFile().canonicalFile
        val context = object : AppContext by AppContextImpl() {
            override fun externalStoragePath(path: String): String = File(root, path).path
        }
        appKoin = Koin().apply { declare<AppContext>(context) }
        try {
            val launcher = File(root, "mods/units").apply { mkdirs() }
            assertEquals(launcher.canonicalFile, File(modDir).canonicalFile)
            assertEquals(modDir, CustomUnitLoadInject.customModDirectory())
            assertEquals(modDir, internalModDir)
        } finally {
            appKoin.close()
            check(root.toPath().startsWith(scratch.toPath()) && root != scratch)
            root.deleteRecursively()
        }
    }
}
