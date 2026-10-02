/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.heap.ModHeapEstimator
import io.github.rwpp.game.mod.heap.imageExtent
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModHeapEstimatorTest {
    @Test
    fun relativeTemplatesDoNotPickAnotherDirectoriesNamesake() {
        val estimate = ModHeapEstimator.estimateSources("pack", listOf(
            "a/base.template" to "[core]\nif=self.hp > 10",
            "b/base.template" to "[core]\nif=select(self.hp > 10, memory.x, memory.y)",
            "a/unit.ini" to "[core]\nname=A\ncopyFrom=base.template",
            "b/unit.ini" to "[core]\nname=B\ncopyFrom=base.template",
        ))
        assertEquals(2, estimate.unitCount)
        assertEquals(3, estimate.units.single { it.name == "A" }.logicNodes)
        assertTrue(estimate.units.single { it.name == "B" }.logicNodes > 3)
        assertTrue(estimate.warnings.none { it.contains("找不到") })
    }

    @Test
    fun rootCopyFromResolvesInsideWrappedArchiveAndTemplatesAreNotUnits() {
        val estimate = ModHeapEstimator.estimateSources("pack", listOf(
            "pack/shared/base.template" to "[core]\nif=self.hp > 10",
            "pack/units/ship.ini" to "[core]\nname=Ship\ncopyFrom=ROOT:shared/base.template",
        ), rootPath = "pack")
        assertEquals(1, estimate.unitCount)
        assertEquals(3, estimate.logicNodes)
        assertTrue(estimate.warnings.isEmpty())
    }

    @Test
    fun dontLoadIsCheckedOnSourceBeforeInheritance() {
        val estimate = ModHeapEstimator.estimateSources("pack", listOf(
            "base.ini" to "[core]\nname=Base\ndont_load=true\nif=self.hp > 10",
            "ship.ini" to "[core]\nname=Ship\ncopyFrom=base.ini",
        ))
        assertEquals(1, estimate.unitCount)
        assertEquals("Ship", estimate.units.single().name)
        assertEquals(3, estimate.logicNodes)
    }

    @Test
    fun nearestAutomaticTemplateIsAppliedButExplicitValuesWin() {
        val estimate = ModHeapEstimator.estimateSources("pack", listOf(
            "all-units.template" to "[core]\nif=select(self.hp > 10, memory.x, memory.y)",
            "nested/all-units.template" to "[core]\nif=self.hp > 10",
            "nested/ship.ini" to "[core]\nname=Ship",
            "nested/boat.ini" to "[core]\nname=Boat\nif=true",
        ))
        assertEquals(2, estimate.unitCount)
        assertEquals(3, estimate.units.single { it.name == "Ship" }.logicNodes)
        assertEquals(0, estimate.units.single { it.name == "Boat" }.logicNodes)
    }

    @Test
    fun skippedSectionDoesNotKeepParentExpression() {
        val estimate = ModHeapEstimator.estimateSources("pack", listOf(
            "base.template" to "[core]\nif=self.hp > 10",
            "ship.ini" to "[core]\nname=Ship\ncopyFrom=base.template\n@copyFrom_skipThisSection=true",
        ))
        assertEquals(0, estimate.logicNodes)
    }

    @Test
    fun parenthesesAndPositionalEqualityDoNotHideLogicNodes() {
        assertEquals(ModHeapEstimator.expressionCost("self.hp > 10"), ModHeapEstimator.expressionCost("((self.hp > 10))"))
        val cost = ModHeapEstimator.expressionCost("select(self.hp == 10, memory.x, memory.y)")
        assertEquals(6, cost.nodes)
        assertEquals(setOf("x", "y"), cost.memoryNames)
    }

    @Test
    fun emptyOrUnsupportedModCannotLookLikeAValidZeroMemoryResult() {
        val dir = createTempDirectory("mod-heap-empty").toFile()
        try {
            assertTrue(ModHeapEstimator.estimate(dir).warnings.any { it.contains("不能据此") })
            val bad = File(dir, "broken.rwmod").apply { writeText("not a zip") }
            assertFailsWith<IllegalArgumentException> { ModHeapEstimator.estimate(bad) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun largeIniFailsWithExplicitSizeLimit() {
        val dir = createTempDirectory("mod-heap-limit").toFile()
        try {
            val huge = File(dir, "huge.ini")
            huge.outputStream().use { output ->
                val block = ByteArray(1024 * 1024) { '#'.code.toByte() }
                repeat(9) { output.write(block) }
            }
            assertTrue(assertFailsWith<IllegalArgumentException> { ModHeapEstimator.estimate(huge) }.message!!.contains("8 MiB"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsBeforeParsingMoreFiles() {
        val dir = createTempDirectory("mod-heap-cancel").toFile()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<java.util.concurrent.CancellationException> { ModHeapEstimator.estimate(dir) }
        } finally {
            Thread.interrupted()
            dir.deleteRecursively()
        }
    }

    @Test
    fun plainUnitIsOnlyTheFixedObjectPlusItsName() {
        val estimate = ModHeapEstimator.estimateSources(
            "plain.ini",
            listOf(
                "plain.ini" to """
                    [core]
                    name=Scout
                    maxHp=100
                    canAttack=true
                """.trimIndent(),
            ),
        )
        val expected = ModHeapEstimator.UNIT_FIXED_BYTES + ModHeapEstimator.stringBytes("Scout")
        assertEquals(1, estimate.unitCount)
        assertEquals(0, estimate.logicNodes)
        assertEquals(expected.toLong(), estimate.heapBytes)
        assertEquals(0, estimate.imageAccountedBytes)
    }

    @Test
    fun comparisonBuildsThreeLogicNodes() {
        val cost = ModHeapEstimator.expressionCost("self.hp > 10")
        val expected = 16 + (24 + ModHeapEstimator.stringBytes("self.hp")) + 16
        assertEquals(3, cost.nodes)
        assertEquals(expected, cost.bytes)
    }

    @Test
    fun keywordNumberStaysInsideTheCallNode() {
        val cost = ModHeapEstimator.expressionCost("isOnTeam(team=0)")
        assertEquals(1, cost.nodes)
        assertEquals(24, cost.bytes)
    }

    @Test
    fun copyFromDuplicatesParentLogicIntoTheChild() {
        val parent = """
            [core]
            name=Base
            if=self.hp > 10
        """.trimIndent()
        val child = """
            [core]
            name=Ship
            copyFrom=Base
        """.trimIndent()
        val estimate = ModHeapEstimator.estimateSources(
            "pack",
            listOf("base.ini" to parent, "ship.ini" to child),
        )
        assertEquals(2, estimate.unitCount)
        assertEquals(6, estimate.logicNodes)
        val base = estimate.units.single { it.name == "Base" }
        val ship = estimate.units.single { it.name == "Ship" }
        assertEquals(base.logicNodes, ship.logicNodes)
        assertEquals(base.heapBytes, ship.heapBytes)
        assertTrue(ship.heapBytes > ModHeapEstimator.UNIT_FIXED_BYTES)
    }

    @Test
    fun copyFromCanTargetTheIniFileName() {
        val estimate = ModHeapEstimator.estimateSources(
            "pack",
            listOf(
                "base.ini" to """
                    [core]
                    name=Base
                    if=self.hp > 10
                """.trimIndent(),
                "ship.ini" to """
                    [core]
                    name=Ship
                    copyFrom=base.ini
                """.trimIndent(),
            ),
        )
        val base = estimate.units.single { it.name == "Base" }
        val ship = estimate.units.single { it.name == "Ship" }
        assertEquals(base.logicNodes, ship.logicNodes)
        assertEquals(base.heapBytes, ship.heapBytes)
        assertTrue(estimate.warnings.none { it.contains("找不到") })
    }

    @Test
    fun copyFromCycleStops() {
        val estimate = ModHeapEstimator.estimateSources(
            "cycle",
            listOf(
                "a.ini" to """
                    [core]
                    name=A
                    copyFrom=B
                """.trimIndent(),
                "b.ini" to """
                    [core]
                    name=B
                    copyFrom=A
                """.trimIndent(),
            ),
        )
        assertEquals(2, estimate.unitCount)
        assertTrue(estimate.warnings.any { it.contains("截断") })
    }

    @Test
    fun imagePixelsAreNotAddedToTheHeap() {
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D,
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 0, 1,
            0, 0, 0, 1,
        )
        assertEquals(1, imageExtent("unit.png", png)?.width)
        assertEquals(1, imageExtent("unit.png", png)?.height)

        val dir = createTempDirectory("mod-heap").toFile()
        try {
            File(dir, "unit.ini").writeText(
                """
                [core]
                name=Scout
                """.trimIndent(),
            )
            File(dir, "unit.png").writeBytes(png)
            val estimate = ModHeapEstimator.estimate(dir)
            assertEquals(
                (ModHeapEstimator.UNIT_FIXED_BYTES + ModHeapEstimator.stringBytes("Scout")).toLong(),
                estimate.heapBytes,
            )
            assertEquals(8, estimate.imageAccountedBytes)
            assertTrue(estimate.formatReport().contains("native"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rwmodZipIsEstimated() {
        val dir = createTempDirectory("mod-heap-zip").toFile()
        val zip = File(dir, "pack.rwmod")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("mod-info.txt"))
                out.write("[mod]\ntitle=Pack\n".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("units/scout.ini"))
                out.write("[core]\nname=Scout\n".toByteArray())
                out.closeEntry()
            }
            val estimate = ModHeapEstimator.estimate(zip)
            assertEquals(1, estimate.unitCount)
            assertEquals("Scout", estimate.units.single().name)
            assertEquals(0, estimate.logicNodes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decalSectionIsCountedApartFromLogic() {
        val estimate = ModHeapEstimator.estimateSources(
            "decal.ini",
            listOf(
                "decal.ini" to """
                    [core]
                    name=Scout
                    [decal_badge]
                    image=badge.png
                    if=self.hp > 10
                """.trimIndent(),
            ),
        )
        assertEquals(1, estimate.childSections)
        assertEquals(3, estimate.logicNodes)
        assertTrue(estimate.heapBytes > ModHeapEstimator.UNIT_FIXED_BYTES + ModHeapEstimator.stringBytes("Scout"))
    }
}
