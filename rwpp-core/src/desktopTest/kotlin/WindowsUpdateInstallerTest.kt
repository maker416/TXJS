/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class WindowsUpdateInstallerTest {
    @Test fun rejectedElevationIsFailureAndSpecialPathRemainsLiteral() {
        val dir = Files.createTempDirectory("installer-test-").toFile()
        try {
            val file = File(dir, "安装 O'Brien ${'$'}(name); Setup.exe").apply { writeText("test") }
            var calls = 0
            val denied = WindowsUpdateInstaller { target, parameters ->
                calls++
                assertEquals(file.absolutePath, target.absolutePath)
                assertEquals("/RWJS_UPDATE=1 /SILENT /SP- /NORESTART /LOG", parameters)
                5
            }
            assertFailsWith<IOException> { denied.launch(file) }
            assertEquals(1, calls)
            WindowsUpdateInstaller { _, _ -> 33 }.launch(file)
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingInstallerNeverInvokesSystemLauncher() {
        var called = false
        assertFailsWith<IllegalArgumentException> {
            WindowsUpdateInstaller { _, _ -> called = true; 33 }.launch(File("missing/RWJS-update.exe"))
        }
        assertFalse(called)
    }
}
