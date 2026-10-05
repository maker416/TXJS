/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Base64
import kotlin.test.*

class WindowsInstallerCommandTest {
    @Test fun powershellTreatsSpecialInstallerPathAsLiteralAndPreservesUpdateArguments() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", true))
        val installer = File("C:/安装目录/O'Brien ${'$'}(throw 'injected') ; setup.exe")
        // 覆盖 Start-Process 为纯参数捕获函数，不运行安装器、不触发提权。
        val capture = """
            function Start-Process {
                param([string]${'$'}FilePath, [string[]]${'$'}ArgumentList, [string]${'$'}Verb,
                    [string]${'$'}WindowStyle, [switch]${'$'}PassThru)
                ${'$'}script:captured = @{ path = ${'$'}FilePath; arguments = ${'$'}ArgumentList; verb = ${'$'}Verb }
            }
            ${WindowsInstallerCommand.script(installer)}
            ${'$'}script:captured | ConvertTo-Json -Compress
        """.trimIndent()
        val command = WindowsInstallerCommand.command(installer).toMutableList()
        command[command.lastIndex] = Base64.getEncoder().encodeToString(capture.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), result)
        val json = Json.parseToJsonElement(result.trim()).jsonObject
        assertEquals(installer.absolutePath, json["path"]!!.jsonPrimitive.content)
        assertEquals("RunAs", json["verb"]!!.jsonPrimitive.content)
        assertEquals(listOf("/RWJS_UPDATE=1", "/SILENT", "/SP-", "/NORESTART", "/LOG"),
            json["arguments"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
