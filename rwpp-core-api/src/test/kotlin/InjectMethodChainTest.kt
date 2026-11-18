/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.inject

import io.github.rwpp.inject.runtime.InjectApi
import javassist.CtNewMethod
import javassist.LoaderClassPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

object InjectMethodChainHooks {
    val calls = mutableListOf<String>()
    fun before() { calls += "before" }
    fun original() { calls += "original" }
    fun after() { calls += "after" }
    fun secondAfter() { calls += "secondAfter" }
}

class InjectMethodChainTest {
    @Test fun actualPathInjectionsKeepBeforeAndAfterAroundTheOriginalBody() {
        for (reverse in listOf(false, true)) {
            GameLibraries.includes.add(GameLibraries.`game-lib`)
            GameLibraries.`game-lib`.load(File("../lib/game-lib.jar"))
            val pool = GameLibraries.includes.first().classTree.defPool
            pool.appendClassPath(LoaderClassPath(InjectMethodChainHooks::class.java.classLoader))
            val name = "rwpp.test.InjectionChain${System.nanoTime()}"
            val target = pool.makeClass(name)
            target.addConstructor(javassist.CtNewConstructor.defaultConstructor(target))
            target.addMethod(CtNewMethod.make("public void run() { io.github.rwpp.inject.InjectMethodChainHooks.INSTANCE.original(); }", target))
            val hooks = listOf(InjectMode.InsertBefore to "before", InjectMode.InsertAfter to "after")
            (if (reverse) hooks.reversed() else hooks).forEach { (mode, method) ->
                InjectApi.injectMethod(name, false, "run", "()V", mode,
                    "io.github.rwpp.inject.InjectMethodChainHooks.$method", true, PathType.Path)
            }
            InjectApi.injectMethod(name, false, "run", "()V", InjectMode.InsertAfter,
                "io.github.rwpp.inject.InjectMethodChainHooks.secondAfter", true, PathType.Path)
            val bytes = target.toBytecode()
            val loader = object : ClassLoader(InjectMethodChainHooks::class.java.classLoader) {
                fun define(): Class<*> = defineClass(name, bytes, 0, bytes.size)
            }
            InjectMethodChainHooks.calls.clear()
            val klass = loader.define()
            klass.getMethod("run").invoke(klass.getDeclaredConstructor().newInstance())
            assertEquals(listOf("before", "original", "after", "secondAfter"), InjectMethodChainHooks.calls)
        }
    }

    @Test fun previouslyGeneratedLibrariesRequireReinjectionWhenTheCompositionMechanismChanges() {
        val root = java.nio.file.Files.createTempDirectory("rwjs-injector-artifact-test-").toFile()
        try {
            kotlin.test.assertFalse(io.github.rwpp.inject.runtime.Builder.currentInjectorArtifact(root))
            val marker = File(root, "injector-revision.txt")
            marker.writeText("1")
            kotlin.test.assertFalse(io.github.rwpp.inject.runtime.Builder.currentInjectorArtifact(root))
            marker.writeText(io.github.rwpp.inject.runtime.Builder.INJECTOR_ARTIFACT_REVISION)
            kotlin.test.assertTrue(io.github.rwpp.inject.runtime.Builder.currentInjectorArtifact(root))
        } finally { root.deleteRecursively() }
    }
}
