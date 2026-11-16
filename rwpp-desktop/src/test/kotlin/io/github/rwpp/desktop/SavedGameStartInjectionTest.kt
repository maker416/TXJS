/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.RootInfo
import io.github.rwpp.inject.runtime.InjectApi
import javassist.bytecode.analysis.Analyzer
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedGameStartInjectionTest {
    @Test fun bothOriginalRoomLoadResultsAreGuardedByProductionHooks() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val hooks = root.redirectMethodInfos.filter { it.path.contains("SavedGameStartInject.") }
        assertEquals(2, hooks.size, "Host-file and received-stream saves both require a result check")
        val previous = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`game-lib`)
            GameLibraries.`game-lib`.load(File("../lib/game-lib.jar"))
            hooks.forEach { hook ->
                InjectApi.redirect(hook.className, hook.hasReceiver, hook.method, hook.methodDesc,
                    hook.targetClassName, hook.targetMethod, hook.targetMethodDesc, hook.path, hook.pathType)
            }
            val target = GameLibraries.`game-lib`.classTree.defPool.get(hooks.first().className)
            val start = target.getDeclaredMethod("r")
            var directLoads = 0
            var guardedLoads = 0
            start.instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    if (call.className == "com.corrodinggames.rts.gameFramework.y") directLoads++
                    if (call.methodName.startsWith("__redirect__r__")) guardedLoads++
                }
            })
            assertEquals(0, directLoads)
            assertEquals(2, guardedLoads)
            Analyzer().analyze(target, start.methodInfo)
            assertTrue(target.toBytecode().isNotEmpty())
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }
}
