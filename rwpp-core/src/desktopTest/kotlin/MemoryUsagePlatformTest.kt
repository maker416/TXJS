/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.platform.readMemoryUsage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryUsagePlatformTest {
    @Test
    fun sampleUsesLiveHeapAndCurrentResidentMemoryOnSupportedHost() {
        val usage = readMemoryUsage()
        assertTrue(usage.heapUsedBytes > 0)
        assertTrue(usage.heapMaxBytes >= usage.heapUsedBytes)
        assertTrue(usage.heapUsageFraction in 0f..1f)

        val os = System.getProperty("os.name").lowercase()
        if (os.startsWith("windows") || os.startsWith("linux") || os.startsWith("mac")) {
            // Exercises the native ABI / proc parser rather than just testing a fake sample.
            assertTrue(assertNotNull(usage.processResidentBytes) > 0)
        }
    }
}
