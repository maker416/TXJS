/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import java.io.File
import java.util.Base64

/** 使用 Windows Shell 提权运行 Inno 安装器，路径作为单引号字面量传递。 */
object WindowsInstallerCommand {
    fun script(installer: File): String {
        val path = installer.absolutePath.replace("'", "''")
        return "${'$'}ErrorActionPreference = 'Stop'; ${'$'}ProgressPreference = 'SilentlyContinue'; " +
            "[Console]::OutputEncoding = [Text.UTF8Encoding]::new(${'$'}false); " +
            "Start-Process -FilePath '$path' " +
            "-ArgumentList '/RWJS_UPDATE=1','/SILENT','/SP-','/NORESTART','/LOG' " +
            "-Verb RunAs -WindowStyle Normal -PassThru | Out-Null"
    }

    fun command(installer: File): List<String> = listOf(
        File(System.getenv("SystemRoot") ?: "C:/Windows", "System32/WindowsPowerShell/v1.0/powershell.exe").path,
        "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-OutputFormat", "Text", "-EncodedCommand",
        Base64.getEncoder().encodeToString(script(installer).toByteArray(Charsets.UTF_16LE)),
    )
}
