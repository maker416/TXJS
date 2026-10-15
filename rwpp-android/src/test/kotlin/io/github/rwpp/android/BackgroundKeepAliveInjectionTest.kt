/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import io.github.rwpp.android.impl.inject.EngineMusicInject
import io.github.rwpp.android.impl.inject.EngineMusicPreparedInject
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.inject.RootInfo
import io.github.rwpp.inject.runtime.InjectApi
import javassist.ClassPool
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackgroundKeepAliveInjectionTest {
    @Test
    fun generatedHooksCanBeAppliedToTheActualAndroidEngineJar() {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val prefix = "io.github.rwpp.android.impl.inject."
        val expected = setOf("BaseEngineInject.onMainLoop", "BaseEngineInject.onActivityStop",
            "DeferredGameStopInject.onDeferredStop", "MusicInject.onResume", "EngineMusicPreparedInject.onPrepared")
        val hooks = root.injectInfos.filter { it.path.removePrefix(prefix) in expected }
        assertEquals(expected, hooks.map { it.path.removePrefix(prefix) }.toSet())

        val previousIncludes = GameLibraries.includes.toSet()
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(GameLibraries.`android-game-lib`)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            GameLibraries.`android-game-lib`.load(File("../lib/android-game-lib.jar"))
            val pool = GameLibraries.`android-game-lib`.classTree.defPool
            hooks.forEach { hook ->
                InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName, hook.methodDesc,
                    hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
            }
            hooks.map { it.className }.distinct().forEach { name ->
                assertTrue(pool.get(name).toBytecode().isNotEmpty(), "Cannot emit injected $name")
            }
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previousIncludes)
        }
    }

    @Test
    fun bothNativeMusicStartEntrypointsAndTheDeferredStopContractAreCovered() {
        val pool = ClassPool(true).apply {
            appendClassPath(File("../lib/android.jar").absolutePath)
            insertClassPath(File("../lib/android-game-lib.jar").absolutePath)
        }
        val starts = mutableSetOf<String>()
        listOf("bc", "be").forEach { simpleName ->
            pool.get("com.corrodinggames.rts.gameFramework.$simpleName").declaredMethods.forEach { method ->
                method.instrument(object : ExprEditor() {
                    override fun edit(call: MethodCall) {
                        if (call.className == "android.media.MediaPlayer" && call.methodName == "start") {
                            starts.add("$simpleName.${method.name}${method.signature}")
                        }
                    }
                })
            }
        }
        assertEquals(setOf("bc.b()V", "be.onPrepared(Landroid/media/MediaPlayer;)V"), starts)
        assertEquals("()V", pool.get("com.corrodinggames.rts.game.i").getDeclaredMethod("ap").signature)
        val timer = pool.get("com.corrodinggames.rts.game.j").getDeclaredMethod("run")
        var stops = 0
        timer.instrument(object : ExprEditor() {
            override fun edit(call: MethodCall) {
                if (call.className == "com.corrodinggames.rts.game.i" && call.methodName == "C") stops++
            }
        })
        assertEquals(1, stops)
    }

    @Test
    fun backgroundMenuRefreshAndLatePreparationCannotRestartMusic() {
        val foreground = BackgroundKeepAlive::class.java.getDeclaredField("isAppInForeground")
            .apply { isAccessible = true }
        val oldValue = foreground.getBoolean(null)
        try {
            foreground.setBoolean(null, false)
            assertIs<InterruptResult>(EngineMusicInject.onResume())
            assertIs<InterruptResult>(EngineMusicPreparedInject.onPrepared(null))
            foreground.setBoolean(null, true)
            assertEquals(Unit, EngineMusicInject.onResume())
            assertEquals(Unit, EngineMusicPreparedInject.onPrepared(null))
        } finally {
            foreground.setBoolean(null, oldValue)
        }
    }
}
