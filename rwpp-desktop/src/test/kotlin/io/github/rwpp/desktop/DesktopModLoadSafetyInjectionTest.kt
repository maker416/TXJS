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
import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.*

class DesktopModLoadSafetyInjectionTest {
    @Test fun generatedBudgetAndRecoveryHooksApplyToTheRealEngine() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val names = listOf("checkUnitLoadBudget", "stopWrappedMemoryFailure", "checkExpressionBudget", "preserveSelectionDuringRecovery", "parseDeepSelect")
        val hooks = root.injectInfos.filter { hook -> names.any { hook.path.endsWith(".$it") } }
        assertEquals(5, hooks.size)
        val select = hooks.single { it.path.endsWith("SelectLoadInject.parseDeepSelect") }
        assertTrue(select.hasReceiver)
        assertEquals("com.corrodinggames.rts.game.units.custom.logicBooleans.LogicString\$Select", select.className)
        assertEquals("setArgumentsRaw", select.methodName)
        assertEquals("(Ljava/lang/String;Lcom/corrodinggames/rts/game/units/custom/l;Ljava/lang/String;)V", select.methodDesc)
        assertEquals(io.github.rwpp.inject.InjectMode.InsertBefore, select.injectMode)
        assertFalse(select.returnClassIsVoid)
        val previous = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`game-lib`)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            GameLibraries.`game-lib`.load(File("../lib/game-lib.jar"))
            hooks.groupBy { it.className }.forEach { (name, group) ->
                group.forEach { hook ->
                    InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName, hook.methodDesc,
                        hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
                }
                val target = GameLibraries.`game-lib`.classTree.defPool.get(name)
                group.forEach { Analyzer().analyze(target, target.getMethod(it.methodName, it.methodDesc).methodInfo) }
                assertTrue(target.toBytecode().isNotEmpty())
            }
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }
}
