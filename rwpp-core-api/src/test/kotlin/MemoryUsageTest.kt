/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.core.parseProcStatusResidentBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryUsageTest {
    @Test
    fun headroomUsesRuntimeCeilingInsteadOfCommittedHeap() {
        val usage = MemoryUsage(heapUsedBytes = 384, heapMaxBytes = 512)
        assertEquals(0.75f, usage.heapUsageFraction)
        assertEquals(128L, usage.heapHeadroomBytes)
    }

    @Test
    fun invalidSamplesCannotProduceNegativeHeadroomOrInvalidProgress() {
        for (limit in listOf(0L, -1L, Long.MIN_VALUE)) {
            val usage = MemoryUsage(Long.MAX_VALUE, limit)
            assertEquals(0f, usage.heapUsageFraction)
            assertEquals(0L, usage.heapHeadroomBytes)
        }
        assertEquals(0f, MemoryUsage(Long.MIN_VALUE, Long.MAX_VALUE).heapUsageFraction)
        assertEquals(Long.MAX_VALUE, MemoryUsage(Long.MIN_VALUE, Long.MAX_VALUE).heapHeadroomBytes)
        assertEquals(1f, MemoryUsage(Long.MAX_VALUE, 512).heapUsageFraction)
        assertEquals(0L, MemoryUsage(Long.MAX_VALUE, 512).heapHeadroomBytes)
    }

    @Test
    fun procStatusReadsCurrentResidentBytesWithKernelWhitespace() {
        val lines = sequenceOf(
            "Name:\tgame",
            "VmPeak:\t123456 kB",
            "VmSize:\t100000 kB",
            "VmHWM:\t50000 kB",
            "VmRSS:\t  32768 kB",
            "RssAnon:\t10000 kB",
        )
        assertEquals(32L * 1024 * 1024, parseProcStatusResidentBytes(lines))
        assertEquals(0L, parseProcStatusResidentBytes(sequenceOf("VmRSS: 0 kB")))
    }

    @Test
    fun unavailableOrInvalidProcMetricIsNotReportedAsZeroOrAnotherMetric() {
        assertNull(parseProcStatusResidentBytes(sequenceOf("VmHWM: 8192 kB", "VmSize: 9999 kB")))
        for (line in listOf(
            "VmRSS: -1 kB", "VmRSS: 50 MB", "VmRSS: 50", "VmRSS: unknown kB",
            "VmRSS: ${Long.MAX_VALUE} kB", "VmRSS: 9223372036854775808 kB",
        )) {
            assertNull(parseProcStatusResidentBytes(sequenceOf(line)), line)
        }
    }

    @Test
    fun procStatusStopsReadingAfterResidentSample() {
        val lines = sequence {
            yield("VmRSS: 10 kB")
            error("The rest of the status file is unnecessary")
        }
        assertEquals(10240L, parseProcStatusResidentBytes(lines))
    }
}
