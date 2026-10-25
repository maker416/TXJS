/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.*

class DesktopRuntimeLogTest {
    @Test fun utf8StackTraceAndConsoleOutputReachLogTogether() {
        val console = ByteArrayOutputStream()
        val log = ByteArrayOutputStream()
        val output = PrintStream(DesktopRuntimeLog.tee(console, log, Any()), true, StandardCharsets.UTF_8)
        output.println("等待室异常")
        IllegalStateException("测试原因").printStackTrace(output)
        assertContentEquals(console.toByteArray(), log.toByteArray())
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("测试原因"))
    }
}
