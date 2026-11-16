/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

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

class MatchStartInjectionTest {
    @Test fun hostedAndReceivedSavedGamesCannotIgnoreALoadFailure() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val hooks = root.redirectMethodInfos.filter {
            it.path.endsWith("MultiplayerRoomInject.loadHostedSave") ||
                it.path.endsWith("MultiplayerRoomInject.loadReceivedSave")
        }
        assertEquals(2, hooks.size)
        val previous = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`android-game-lib`)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            GameLibraries.`android-game-lib`.load(File("../lib/android-game-lib.jar"))
            hooks.forEach { hook ->
                InjectApi.redirect(hook.className, hook.hasReceiver, hook.method, hook.methodDesc,
                    hook.targetClassName, hook.targetMethod, hook.targetMethodDesc, hook.path, hook.pathType)
            }
            val target = GameLibraries.`android-game-lib`.classTree.defPool.get(hooks.first().className)
            val start = target.getDeclaredMethod("startGameCommon")
            var directLoads = 0
            var guardedLoads = 0
            start.instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    if (call.className == "com.corrodinggames.rts.gameFramework.aj") directLoads++
                    if (call.methodName.startsWith("__redirect__startGameCommon__")) guardedLoads++
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

    @Test fun generatedStartCallbackCanBeAppliedToTheActualAndroidEngine() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val hook = root.injectInfos.single { it.path.endsWith("MultiplayerRoomInject.onStartGame") }
        assertEquals("()V", hook.methodDesc)
        val previous = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`android-game-lib`)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            GameLibraries.`android-game-lib`.load(File("../lib/android-game-lib.jar"))
            InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName, hook.methodDesc,
                hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
            val target = GameLibraries.`android-game-lib`.classTree.defPool.get(hook.className)
            Analyzer().analyze(target, target.getDeclaredMethod(hook.methodName).methodInfo)
            assertTrue(target.toBytecode().isNotEmpty())
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }
}
