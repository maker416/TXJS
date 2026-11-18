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
import javassist.expr.FieldAccess
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

    private fun withEngine(countSlotReads: Boolean = false, test: (ClassLoader) -> Unit) {
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

            if (countSlotReads) {
                // Instrument only this loader's copy of the allocator, not the engine's update/draw loops.
                val hookName = hook.path.substringBeforeLast('.')
                val bytes = requireNotNull(javaClass.getResourceAsStream("/${hookName.replace('.', '/')}.class"))
                val copy = bytes.use { pool.makeClass(it) }
                copy.addField(CtField.make("public static int testSlotReads;", copy))
                copy.getDeclaredMethod("allocateEffect").instrument(object : ExprEditor() {
                    override fun edit(field: FieldAccess) {
                        if (field.isReader && field.className == effectName && field.fieldName == "o") {
                            field.replace("{ testSlotReads++; \$_ = \$proceed(); }")
                        }
                    }
                })
            }

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
            // 999 emits a trail; 998 dies and emits just before the real release method returns.
            effect.getDeclaredMethod("b", arrayOf(CtClass.floatType)).setBody(
                "{ testUpdates++; if (V == 999f) { V = 0f; ay.testEmit(q); } else if (V == 998f) b(); }")
            effect.getDeclaredMethod("b", emptyArray()).insertAfter(
                "{ if (V == 998f) { V = 0f; ay.testEmit(q); } }")
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
        val update = engine.getDeclaredMethod("a", Float::class.javaPrimitiveType)
        val clear = engine.getDeclaredMethod("a", Boolean::class.javaPrimitiveType)
        val release = effect.getDeclaredMethod("b").apply { isAccessible = true }
        val constructor = effect.getDeclaredConstructor(engine).apply { isAccessible = true }
        fun emit(instance: Any): Any? = allocate.invoke(instance, priorities[0])
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
            core.release.invoke(released)
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

    @Test fun denseBurstAndFusedFullPoolAvoidPrefixScans() = withEngine(countSlotReads = true) { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(1200, 1)
        val pool = core.pool()
        val reads = core.hook.getField("testSlotReads")
        val emit = { core.allocate.invoke(instance, core.priorities[0]) }

        for (slot in pool) assertSame(slot, emit())
        val denseReads = reads.getInt(null)
        assertTrue(denseReads <= pool.size, "$denseReads slot probes for ${pool.size} dense allocations")
        core.effect.getField("testFailConstruction").setBoolean(null, true)
        repeat(32) { assertNull(emit()) }
        assertEquals(denseReads, reads.getInt(null), "A full pool needs no slot probes, even after OOM")

        val hole = pool[1100]
        core.release.invoke(hole)
        assertSame(hole, emit())
        assertTrue(reads.getInt(null) > denseReads, "The counter must see the fallback scan")
    }

    @Test fun alternatingHolesAreRefilledInOrderInOnePass() = withEngine(countSlotReads = true) { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(4096, 1)
        val pool = core.pool()
        for (slot in pool) assertSame(slot, core.emit(instance))
        for (i in pool.indices step 2) core.release.invoke(pool[i])

        val reads = core.hook.getField("testSlotReads")
        reads.setInt(null, 0)
        for (i in pool.indices step 2) {
            if (i == pool.size / 2) {
                core.clear.invoke(instance, true)
                core.update.invoke(instance, 0f)
            }
            assertSame(pool[i], core.emit(instance), "Expected first free slot $i")
        }
        assertEquals(pool.size - 1, reads.getInt(null), "One probe per slot through the last hole (4094)")
        assertEquals(pool.size, core.count(instance))
        assertSame(pool, core.pool())
    }

    @Test fun deathDuringUpdateRechecksThePrefixBeforeEmittingItsChild() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(8, 1)
        val pool = core.pool()
        repeat(8) { core.emit(instance) }
        for (i in listOf(3, 5, 7)) core.release.invoke(pool[i])
        assertSame(pool[3], core.emit(instance)) // Prefix [0, 4) is full; 5 and 7 are free.
        val count = core.count(instance)
        core.effect.getField("V").setFloat(pool[1], 998f)
        core.update.invoke(instance, 0f)
        assertEquals(count, core.count(instance), "Death and child emission cancel out across the update")
        assertTrue(core.effect.getField("o").getBoolean(pool[1]))
        assertFalse(core.effect.getField("o").getBoolean(pool[5]))
        assertEquals(count, core.effect.getField("testUpdates").getInt(null), "The child must not tick twice")
        assertSame(pool[5], core.emit(instance))
        core.release.invoke(pool[0])
        assertSame(pool[0], core.emit(instance), "A later release below the new prefix must also win")
    }

    @Test fun returningFromOriginalModeWithTheSameCountRechecksLowerHoles() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(4, 16)
        repeat(5) { core.emit(instance) }
        val pool = core.pool()
        val count = core.count(instance)
        assertEquals(4, core.end()) // Slot 4 is pending.
        core.configure(16)
        assertSame(pool[5], core.emit(instance))
        assertSame(pool[6], core.emit(instance))
        core.release.invoke(pool[5])
        core.release.invoke(pool[1])
        assertEquals(count, core.count(instance)) // Same count, same engine and same array, different holes.
        core.configure(Int.MAX_VALUE)
        assertSame(pool[1], core.emit(instance), "Neither the old prefix nor the old pending end is sufficient")
        assertSame(pool[5], core.emit(instance))
        core.clear.invoke(instance, false)
        core.beginUpdate(instance)
        assertEquals(0, core.end())
        assertSame(pool[0], core.emit(instance))
    }

    @Test fun sameSizeReplacementPoolWithTheSameCountDoesNotInheritThePrefix() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(8, 1)
        val pool = core.pool()
        repeat(8) { core.emit(instance) }
        for (i in listOf(4, 6, 7)) core.release.invoke(pool[i])
        assertSame(pool[4], core.emit(instance))
        // Same engine, count and objects; only the array identity and order change. Holes move to 0 and 1.
        val replacement = pool.reversedArray()
        core.engine.getField("f").set(null, replacement)
        assertSame(replacement[0], core.emit(instance))
        assertSame(replacement[1], core.emit(instance))
        assertSame(replacement, core.pool())
    }

    @Test fun holesAcrossThePendingRangeAreRefilledInSlotOrder() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(4, 1)
        val emit = { core.allocate.invoke(instance, core.priorities[0]) }
        repeat(7) { emit() }
        val pool = core.pool()
        for (index in listOf(5, 1, 4)) core.release.invoke(pool[index])
        // count == g and slot g is free, but slot 6 is live beyond the old array and slot 1 is free.
        assertEquals(4, core.end())
        assertEquals(4, core.count(instance))
        for (index in listOf(1, 4, 5, 7)) assertSame(pool[index], emit(), "Expected first free slot $index")
        assertEquals(4, core.end())
        core.beginUpdate(instance)
        assertEquals(8, core.end())
    }

    @Test fun reentrantEmissionReusesAnAlreadyVisitedHoleWithoutUpdatingItTwice() = withEngine { loader ->
        val core = Core(loader)
        core.configure(Int.MAX_VALUE)
        val instance = core.fresh(8, 1)
        repeat(6) { core.allocate.invoke(instance, core.priorities[0]) }
        val pool = core.pool()
        core.release.invoke(pool[4])
        core.release.invoke(pool[1])
        core.effect.getField("V").setFloat(pool[2], 999f)
        val update = core.engine.getDeclaredMethod("a", Float::class.javaPrimitiveType)
        val updates = core.effect.getField("testUpdates")
        update.invoke(instance, 0f)
        val active = core.effect.getField("o")
        assertTrue(active.getBoolean(pool[1]))
        assertFalse(active.getBoolean(pool[4]))
        assertEquals(4, updates.getInt(null), "Slot 1 was already passed when slot 2 emitted")
        update.invoke(instance, 0f)
        assertEquals(9, updates.getInt(null), "The child is visited in the next update")
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
        core.release.invoke(oldPool[0])
        assertSame(oldPool[0], core.allocate.invoke(instance, core.priorities[0]))
        core.clear.invoke(instance, false)
        core.configure(0)
        core.configure(Int.MAX_VALUE)
        for (slot in oldPool) assertSame(slot, core.emit(instance))
        assertNull(core.emit(instance), "Resetting the hint must not reset the engine's OOM fuse")
        val next = core.fresh(2, 1)
        repeat(3) { assertNotNull(core.allocate.invoke(next, core.priorities[0])) }
        assertEquals(3, core.count(next))
    }
}
