/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.RootInfo
import io.github.rwpp.inject.runtime.InjectApi
import javassist.bytecode.analysis.Analyzer
import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.*

class AndroidModSourceCacheInjectionTest {
    @Test fun sourceInvalidationAndSelectionHooksApplyToTheRealScanner() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val names = listOf("invalidateChangedModSources", "applyReloadSelectionAfterScan")
        val hooks = root.injectInfos.filter { hook -> names.any { hook.path.endsWith(".$it") } }
        assertEquals(2, hooks.size)
        assertEquals(InjectMode.InsertBefore, hooks.single { it.path.endsWith(".invalidateChangedModSources") }.injectMode)
        assertTrue(hooks.all { it.hasReceiver && it.methodName == "j" && it.methodDesc == "()Ljava/util/ArrayList;" })
        val previous = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`android-game-lib`)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            GameLibraries.`android-game-lib`.load(File("../lib/android-game-lib.jar"))
            hooks.forEach { hook ->
                InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName, hook.methodDesc,
                    hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
            }
            val target = GameLibraries.`android-game-lib`.classTree.defPool.get(hooks.first().className)
            Analyzer().analyze(target, target.getMethod("j", "()Ljava/util/ArrayList;").methodInfo)
            assertTrue(target.toBytecode().isNotEmpty())
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }
}
