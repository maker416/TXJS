/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.RootInfo
import io.github.rwpp.inject.runtime.InjectApi
import javassist.CtClass
import javassist.CtField
import javassist.CtNewMethod
import javassist.Loader
import javassist.bytecode.analysis.Analyzer
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.*

class EffectLimitInjectionTest {
    private val engineName = "com.corrodinggames.rts.gameFramework.d.c"
    private val effectName = "com.corrodinggames.rts.gameFramework.d.e"
    private val priorityName = "com.corrodinggames.rts.gameFramework.d.h"

    private fun withEngine(test: (ClassLoader) -> Unit) {
        val root = Toml.decodeFromString(RootInfo.serializer(), File("build/generated/config.toml").readText())
        val hook = root.injectInfos.single { it.path.endsWith("EffectLimitInject.allocateEffect") }
        assertEquals(engineName, hook.className)
        assertEquals("a", hook.methodName)
        assertEquals("(Lcom/corrodinggames/rts/gameFramework/d/h;)Lcom/corrodinggames/rts/gameFramework/d/e;", hook.methodDesc)
        assertTrue(hook.hasReceiver)
        assertFalse(hook.returnClassIsVoid)
        assertEquals(InjectMode.InsertBefore, hook.injectMode)

        val previous = GameLibraries.includes.toSet()
        val library = GameLibraries.`game-lib`
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(library)
            GameLibraries.defClassPool.appendClassPath(File("../lib/android.jar").absolutePath)
            library.load(File("../lib/${library.realName}.jar"))
            val pool = library.classTree.defPool
            val target = pool.get(engineName)
            val effect = pool.get(effectName)

            // No native graphics/device in this JVM. Keep the real effect constructor, allocator,
            // disposal and pool clear; omit only rendering initialization and the FPS supplier.
            target.classInitializer?.setBody("{}")
            target.getDeclaredConstructor(emptyArray()).setBody("{}")
            effect.classInitializer?.setBody("{}")
            effect.declaredConstructors.forEach { constructor ->
                constructor.instrument(object : ExprEditor() {
                    override fun edit(call: MethodCall) {
                        if (call.className.startsWith("com.corrodinggames.rts.gameFramework.utility.") &&
                            call.methodName in setOf("a", "b") && call.signature.endsWith(
                                "Lcom/corrodinggames/rts/gameFramework/m/ag;")) {
                            call.replace("{ \$_ = null; }")
                        }
                    }
                })
            }
            target.addField(CtField.make("public static int testFps = 60;", target))
            effect.addField(CtField.make("public static boolean testFailConstruction = false;", effect))
            effect.declaredConstructors.forEach {
                it.insertBeforeBody("if (testFailConstruction) throw new OutOfMemoryError(\"simulated\");")
            }
            effect.addField(CtField.make("public static int testUpdates = 0;", effect))
            target.addMethod(CtNewMethod.make("public void testEmit($priorityName priority) { a(priority); }", target))
            // Simulate a death/trail callback, retaining the original engine traversal.
            effect.getDeclaredMethod("b", arrayOf(CtClass.floatType)).setBody(
                "{ testUpdates++; if (V == 999f) { V = 0f; ay.testEmit(q); } }")
            target.getDeclaredMethod("a", arrayOf(CtClass.floatType)).instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    if (call.className == "com.corrodinggames.rts.gameFramework.l" &&
                        call.methodName == "B") call.replace("{ \$_ = null; }")
                }
            })
            target.getMethod("a", hook.methodDesc).instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    if (call.className == "com.corrodinggames.rts.gameFramework.l") {
                        if (call.methodName == "B" && call.signature.startsWith("()")) {
                            call.replace("{ \$_ = null; }")
                        } else if (call.methodName == "b" && call.signature == "()I") {
                            call.replace("{ \$_ = testFps; }")
                        }
                    }
                }
            })
            InjectApi.injectMethod(hook.className, hook.hasReceiver, hook.methodName, hook.methodDesc,
                hook.injectMode, hook.path, hook.returnClassIsVoid, hook.pathType)
            val updateHook = root.injectInfos.single { it.path.endsWith("EffectLimitInject.beforeEffectUpdate") }
            assertEquals("(F)V", updateHook.methodDesc)
            assertTrue(updateHook.hasReceiver)
            InjectApi.injectMethod(updateHook.className, updateHook.hasReceiver, updateHook.methodName,
                updateHook.methodDesc, updateHook.injectMode, updateHook.path, updateHook.returnClassIsVoid, updateHook.pathType)
            val clearHook = root.injectInfos.single { it.path.endsWith("EffectLimitInject.beforeEffectClear") }
            assertEquals("(Z)V", clearHook.methodDesc)
            InjectApi.injectMethod(clearHook.className, clearHook.hasReceiver, clearHook.methodName,
                clearHook.methodDesc, clearHook.injectMode, clearHook.path, clearHook.returnClassIsVoid, clearHook.pathType)
            Analyzer().analyze(target, target.getMethod("a", hook.methodDesc).methodInfo)
            Analyzer().analyze(target, target.getDeclaredMethod("a", arrayOf(CtClass.floatType)).methodInfo)
            assertTrue(target.toBytecode().isNotEmpty())

            val loader = Loader(javaClass.classLoader, pool)
            for (prefix in listOf("kotlin.", "org.slf4j.", "io.github.rwpp.game.ui.",
                "io.github.rwpp.inject.", "io.github.rwpp.GlobalKt")) loader.delegateLoadingOf(prefix)
            test(loader)
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }

    private class Core(loader: ClassLoader) {
        val engine = loader.loadClass("com.corrodinggames.rts.gameFramework.d.c")
        val effect = loader.loadClass("com.corrodinggames.rts.gameFramework.d.e")
        val priority = loader.loadClass("com.corrodinggames.rts.gameFramework.d.h")
        val priorities = requireNotNull(priority.enumConstants)
        val hook = loader.loadClass("io.github.rwpp.desktop.impl.inject.EffectLimitInject")
        val hookInstance = hook.getField("INSTANCE").get(null)
        val allocate = engine.getDeclaredMethod("a", priority).apply { isAccessible = true }
        val original = engine.getDeclaredMethod("__original__a", priority).apply { isAccessible = true }
        val constructor = effect.getDeclaredConstructor(engine).apply { isAccessible = true }
        fun configure(limit: Int) { hook.getMethod("configure", Int::class.javaPrimitiveType).invoke(hookInstance, limit) }
        fun beginUpdate(instance: Any) {
            hook.getMethod("beforeEffectUpdate", engine, Float::class.javaPrimitiveType).invoke(hookInstance, instance, 0f)
        }
        fun fresh(size: Int, limit: Int): Any {
            val instance = engine.getDeclaredConstructor().newInstance()
            val pool = ReflectArray.newInstance(effect, size)
            repeat(size) { ReflectArray.set(pool, it, constructor.newInstance(instance)) }
            engine.getField("f").set(null, pool)
            engine.getField("g").setInt(null, 0)
            for (field in listOf("b", "c", "d", "e")) engine.getField(field).setInt(instance, limit)
            engine.getField("i").setInt(instance, 90)
            engine.getField("j").setInt(instance, 210)
            return instance
        }
        fun pool(): Array<*> = engine.getField("f").get(null) as Array<*>
        fun count(instance: Any): Int = engine.getField("a").getInt(instance)
        fun end(): Int = engine.getField("g").getInt(null)
    }

    @Test fun injectedAllocatorGrowsPastBothOriginalCapsAndKeepsEffectsAlive() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        for (initial in listOf(350, 500)) {
            val instance = core.fresh(initial, 1)
            core.engine.getField("testFps").setInt(null, 10)
            val firstPool = core.pool()
            val emitted = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            repeat(1200) { index ->
                val value = assertNotNull(core.allocate.invoke(instance, core.priorities[index % core.priorities.size]))
                assertTrue(emitted.add(value), "An active effect was overwritten at $index")
                assertTrue(core.effect.getField("o").getBoolean(value))
                core.effect.getField("V").setFloat(value, 123f)
            }
            assertEquals(1200, core.count(instance))
            core.beginUpdate(instance)
            assertEquals(1200, core.end())
            assertTrue(core.pool().size >= 1200)
            firstPool.indices.forEach { assertSame(firstPool[it], core.pool()[it]) }
            emitted.forEach { assertEquals(123f, core.effect.getField("V").getFloat(it)) }

            val released = core.pool()[25]!!
            core.effect.getDeclaredMethod("b").apply { isAccessible = true }.invoke(released)
            assertEquals(1199, core.count(instance))
            val retainedPool = core.pool()
            assertSame(released, core.allocate.invoke(instance, core.priorities[0]))
            assertSame(retainedPool, core.pool())
            assertEquals(1200, core.count(instance))
            assertEquals(1200, core.end())

            core.engine.getDeclaredMethod("a", Boolean::class.javaPrimitiveType).invoke(instance, false)
            assertEquals(0, core.count(instance))
            assertEquals(0, core.end())
            assertTrue(core.pool().all { !core.effect.getField("o").getBoolean(it) })
            repeat(1200) { assertNotNull(core.allocate.invoke(instance, core.priorities[0])) }
            assertSame(retainedPool, core.pool())
            assertEquals(1200, core.count(instance))
            assertEquals(1200, core.end())
        }
    }

    @Test fun disabledHookMatchesOriginalZeroAndKeepIncludingPriorityEviction() = withEngine { loader ->
        val core = Core(loader)
        for (limit in listOf(0, 8)) {
            core.configure(limit)
            for (fps in listOf(10, 60)) {
                core.engine.getField("testFps").setInt(null, fps)
                fun outcomes(method: Method): Pair<List<Boolean>, Int> {
                    val instance = core.fresh(8, limit)
                    val allocated = (0 until 40).map { index ->
                        method.invoke(instance, core.priorities[index % core.priorities.size]) != null
                    }
                    assertEquals(8, core.pool().size)
                    return allocated to core.count(instance)
                }
                assertEquals(outcomes(core.original), outcomes(core.allocate), "limit=$limit fps=$fps")
            }
        }
    }

    @Test fun expansionInsideTheOriginalUpdateDefersNewSlotsWithoutOverrunningTheOldArray() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(2, 1)
        val parent = core.allocate.invoke(instance, core.priorities[0])
        core.allocate.invoke(instance, core.priorities[0])
        core.effect.getField("V").setFloat(parent, 999f)
        val update = core.engine.getDeclaredMethod("a", Float::class.javaPrimitiveType)
        update.invoke(instance, 0f)
        assertEquals(3, core.count(instance))
        assertEquals(2, core.end())
        assertEquals(2, core.effect.getField("testUpdates").getInt(null))
        update.invoke(instance, 0f)
        assertEquals(3, core.end())
        assertEquals(5, core.effect.getField("testUpdates").getInt(null))

        val next = core.fresh(2, 1)
        repeat(3) { core.allocate.invoke(next, core.priorities[0]) }
        core.engine.getDeclaredMethod("a", Boolean::class.javaPrimitiveType).invoke(next, true)
        core.beginUpdate(next)
        assertEquals(3, core.count(next))
        assertEquals(3, core.end())
        val cleared = core.fresh(2, 1)
        repeat(3) { core.allocate.invoke(cleared, core.priorities[0]) }
        core.engine.getDeclaredMethod("a", Boolean::class.javaPrimitiveType).invoke(cleared, false)
        core.beginUpdate(cleared)
        assertEquals(0, core.count(cleared))
        assertEquals(0, core.end())
    }

    @Test fun constructorMemoryFailureKeepsTheOldPoolAndStillAllowsReuse() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(2, 1)
        repeat(2) { core.allocate.invoke(instance, core.priorities[0]) }
        val oldPool = core.pool()
        core.effect.getField("testFailConstruction").setBoolean(null, true)
        assertNull(core.allocate.invoke(instance, core.priorities[0]))
        assertSame(oldPool, core.pool())
        assertEquals(2, core.count(instance))
        assertEquals(2, core.end())
        core.effect.getField("testFailConstruction").setBoolean(null, false)
        assertNull(core.allocate.invoke(instance, core.priorities[0]), "Do not repeatedly retry an exhausted engine")
        core.effect.getDeclaredMethod("b").apply { isAccessible = true }.invoke(oldPool[0])
        assertSame(oldPool[0], core.allocate.invoke(instance, core.priorities[0]))
        val next = core.fresh(2, 1)
        repeat(3) { assertNotNull(core.allocate.invoke(next, core.priorities[0])) }
        assertEquals(3, core.count(next))
    }
}
