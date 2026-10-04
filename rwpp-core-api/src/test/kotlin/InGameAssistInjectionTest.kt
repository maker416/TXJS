/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.PathType
import io.github.rwpp.inject.runtime.InjectApi
import javassist.CtClass
import javassist.CtField
import javassist.CtNewMethod
import javassist.bytecode.analysis.Analyzer
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import java.io.File
import kotlin.test.*

/** 用真实两端游戏 jar 验证绘制重定向与追加注入的组合，避免只验证 Kotlin 能编译。 */
class InGameAssistInjectionTest {
    @Test fun desktopHooksKeepOriginalHealthAndMenuLogic() = verify(false)
    @Test fun androidHooksKeepOriginalHealthAndMenuLogic() = verify(true)

    private fun verify(android: Boolean) {
        val previous = GameLibraries.includes.toSet()
        val library = if (android) GameLibraries.`android-game-lib` else GameLibraries.`game-lib`
        try {
            GameLibraries.includes.clear()
            GameLibraries.includes.add(library)
            library.load(File("../lib/${library.realName}.jar"))
            val pool = library.classTree.defPool
            if (android) pool.appendClassPath("../lib/android.jar")
            val unitName = "com.corrodinggames.rts.game.units." + if (android) "ce" else "am"
            val unit = pool.get(unitName)
            val rect = pool.get("android.graphics.RectF")
            val paint = pool.get("android.graphics.Paint")
            val renderer = "com.corrodinggames.rts.gameFramework.m." + if (android) "fi" else "y"
            val args = arrayOf(CtClass.floatType, CtClass.booleanType)
            val originalCalls = countCalls(unit, "a", args, renderer, "a", "(Landroid/graphics/RectF;Landroid/graphics/Paint;)V")
            assertTrue(originalCalls > 1, "必须命中实际血条的绘制调用")

            // 桩回调签名与平台对象相同；不启动 native 图形环境也能分析生成字节码。
            val hook = pool.makeClass("test.assist.UnitHook")
            hook.addField(CtField.make("public static test.assist.UnitHook INSTANCE;", hook))
            hook.addMethod(CtNewMethod.make(CtClass.voidType, "drawAssist", arrayOf(unit, *args), emptyArray(), "{}", hook))
            hook.addMethod(CtNewMethod.make(CtClass.voidType, "drawHealthBar", arrayOf(unit, rect, paint), emptyArray(), "{}", hook))
            InjectApi.redirect(unitName, true, "a", "(FZ)V", renderer, "a",
                "(Landroid/graphics/RectF;Landroid/graphics/Paint;)V", "test.assist.UnitHook.drawHealthBar", PathType.Path)
            InjectApi.injectMethod(unitName, true, "a", "(FZ)V", InjectMode.InsertAfter,
                "test.assist.UnitHook.drawAssist", true, PathType.Path)
            assertEquals(originalCalls, countCalls(unit, "__original__a", args, unitName, "__redirect__a__a", null))
            assertEquals(1, countCalls(unit, "a", args, unitName, "__original__a", null))
            assertEquals(1, countCalls(unit, "a", args, hook.name, "drawAssist", null))
            Analyzer().analyze(unit, unit.getDeclaredMethod("a", args).methodInfo)

            val marker = pool.makeInterface("test.assist.RangeMarker")
            InjectApi.injectInterface(marker.name, unitName, listOf("attackRangeHighlighted" to "boolean"), emptyList())
            assertEquals(CtClass.booleanType, unit.getDeclaredField("attackRangeHighlighted").type)
            assertEquals("()Z", unit.getDeclaredMethod("getAttackRangeHighlighted").signature)
            assertEquals("(Z)V", unit.getDeclaredMethod("setAttackRangeHighlighted").signature)
            assertTrue(unit.toBytecode().isNotEmpty())

            val hud = pool.get("com.corrodinggames.rts.gameFramework.f.a")
            val hudName = if (android) "e" else "a"
            val hudArgs = if (android) arrayOf(CtClass.floatType) else args
            hook.addMethod(CtNewMethod.make(CtClass.voidType, "drawHud", hudArgs, emptyArray(), "{}", hook))
            InjectApi.injectMethod(hud.name, false, hudName, if (android) "(F)V" else "(FZ)V",
                InjectMode.InsertAfter, "test.assist.UnitHook.drawHud", true, PathType.Path)
            assertEquals(1, countCalls(hud, hudName, hudArgs, hud.name, "__original__$hudName", null))
            Analyzer().analyze(hud, hud.getDeclaredMethod(hudName, hudArgs).methodInfo)
            assertTrue(hud.toBytecode().isNotEmpty())

            val input = pool.get("com.corrodinggames.rts.gameFramework.f." + if (android) "i" else "g")
            InjectApi.injectInterface(pool.makeInterface("test.assist.InputAccess").name, input.name,
                emptyList(), listOf("released" to "U", "dragging" to "T"))
            assertEquals("()Z", input.getDeclaredMethod("getReleased").signature)
            assertEquals("(Z)V", input.getDeclaredMethod("setReleased").signature)
            assertEquals("()Z", input.getDeclaredMethod("getDragging").signature)
            assertTrue(input.toBytecode().isNotEmpty())

            hook.addMethod(CtNewMethod.make("public Object size() { return kotlin.Unit.INSTANCE; }", hook))
            for (action in listOf("f", "i")) {
                val button = pool.get("com.corrodinggames.rts.game.units.a.$action")
                InjectApi.injectMethod(button.name, false, "l", "()F", InjectMode.InsertBefore,
                    "test.assist.UnitHook.size", false, PathType.Path)
                assertEquals("()F", button.getDeclaredMethod("__original__l").signature)
                Analyzer().analyze(button, button.getDeclaredMethod("l").methodInfo)
                assertTrue(button.toBytecode().isNotEmpty())
            }
        } finally {
            GameLibraries.includes.clear()
            GameLibraries.includes.addAll(previous)
        }
    }

    private fun countCalls(clazz: CtClass, name: String, args: Array<CtClass>, target: String,
                           method: String, signature: String?): Int {
        var count = 0
        clazz.getDeclaredMethod(name, args).instrument(object : ExprEditor() {
            override fun edit(call: MethodCall) {
                if (call.className == target && call.methodName == method &&
                    (signature == null || call.signature == signature)) count++
            }
        })
        return count
    }
}
