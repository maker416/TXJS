/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import com.android.dex.Dex
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.runtime.Builder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimizedGameDexCompilerTest {
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("rwjs-dex-compiler-").toFile()
        try {
            test(directory)
        } finally {
            directory.walkBottomUp().forEach { it.setWritable(true) }
            directory.deleteRecursively()
        }
    }

    @Test fun realCoreRetainsClassesAndMethodsWhileReducingHotCode() = inDirectory { directory ->
        val input = File("../lib/android-game-lib.jar")
        val baseline = File(directory, "baseline.dex")
        javassist.android.DexFile().apply {
            addJarFile(input.absolutePath)
            writeFile(baseline.absolutePath)
        }
        val optimized = File(directory, "optimized.dex")
        OptimizedGameDexCompiler.compile(input, optimized)
        val oldDex = Dex(baseline)
        val newDex = Dex(optimized)
        assertEquals(oldDex.classDefs().map { oldDex.typeNames()[it.typeIndex] }.toSet(),
            newDex.classDefs().map { newDex.typeNames()[it.typeIndex] }.toSet())
        val oldMethods = methods(oldDex)
        val newMethods = methods(newDex)
        assertEquals(oldMethods.keys, newMethods.keys)
        for (signature in listOf(
            "Lcom/corrodinggames/rts/game/units/bp;->a(F)V",
            "Lcom/corrodinggames/rts/game/units/custom/j;->a(F)V",
        )) {
            assertTrue(newMethods.getValue(signature) < oldMethods.getValue(signature) * 0.75,
                "DX optimization must actually reduce $signature")
        }
    }

    @Test fun completeProductionInjectionCompilesWithOptimization() = inDirectory { directory ->
        val previousLibraries = GameLibraries.includes.toSet()
        val previousRoot = Builder.rootInfo
        val library = GameLibraries.`android-game-lib`
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(library)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            library.load(File("../lib/android-game-lib.jar"))
            Builder.rootInfo = File("build/generated/config.toml").reader().use { Builder.loadConfig(it) }
            assertTrue(Builder.rootInfo!!.injectInfos.isNotEmpty())
            Builder.applyConfig()
            val injectedJar = File(directory, "injected.jar")
            Builder.buildJar(injectedJar, library.classTree.allClasses, library.lib)
            val optimized = File(directory, "injected.dex")
            OptimizedGameDexCompiler.compile(injectedJar, optimized)
            val dex = Dex(optimized)
            assertEquals(library.classTree.allClasses.size, dex.classDefs().count())
            val signatures = methods(dex).keys
            assertTrue("Lcom/corrodinggames/rts/game/units/ce;->__original__a(FZ)V" in signatures)
            assertTrue("Lcom/corrodinggames/rts/game/units/ce;->__redirect__a__a(Landroid/graphics/RectF;Landroid/graphics/Paint;)V" in signatures)
            assertTrue("Lcom/corrodinggames/rts/game/units/ce;->getAttackRangeHighlighted()Z" in signatures)
        } finally {
            Builder.rootInfo = previousRoot
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previousLibraries)
        }
    }

    private fun methods(dex: Dex): Map<String, Int> = buildMap {
        for (clazz in dex.classDefs()) {
            if (clazz.classDataOffset == 0) continue
            for (method in dex.readClassData(clazz).allMethods()) {
                val index = method.methodIndex
                val id = dex.methodIds()[index]
                val parameters = dex.parameterTypeIndicesFromMethodIndex(index)
                    .joinToString("") { dex.typeNames()[it.toInt() and 0xffff] }
                val signature = dex.typeNames()[id.declaringClassIndex] + "->" +
                    dex.strings()[id.nameIndex] + "(" + parameters + ")" +
                    dex.typeNames()[dex.returnTypeIndexFromMethodIndex(index)]
                put(signature, if (method.codeOffset == 0) 0 else dex.readCode(method).instructions.size)
            }
        }
    }
}
