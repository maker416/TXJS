/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.MatchStartGate
import javassist.ClassPool
import javassist.CtClass
import javassist.Loader
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.*

/** 执行真实两端地图解析器和生产开局异常边界；仅替换图形初始化、缓存与日志。 */
class MapStartFailureTest {
    @Test fun malformedDesktopMapIsRecoveredWithoutPresentingGame() = verify(false)
    @Test fun malformedAndroidMapIsRecoveredWithoutLaunchingActivity() = verify(true)

    private fun verify(android: Boolean) {
        val pool = ClassPool(true).apply {
            appendClassPath(File("../lib/${if (android) "android-game-lib" else "game-lib"}.jar").absolutePath)
            appendClassPath(File("../lib/android.jar").absolutePath)
        }
        val map = pool.get("com.corrodinggames.rts.game.b.b")
        map.classInitializer?.setBody("{}")
        map.declaredConstructors.forEach { it.setBody("{ this.A = new java.util.ArrayList(); }") }
        map.getDeclaredMethod("a", arrayOf(pool.get("java.io.InputStream"), CtClass.booleanType)).instrument(object : ExprEditor() {
            override fun edit(call: MethodCall) {
                if (call.indexOfBytecode() > 32) return
                if (call.className in listOf("com.corrodinggames.rts.game.b.h", "com.corrodinggames.rts.game.b.j") &&
                    call.methodName == "b" && call.signature == "()V") {
                    call.replace("{}")
                    return
                }
                if (call.className in listOf("com.corrodinggames.rts.gameFramework.l", "com.corrodinggames.rts.gameFramework.k") &&
                    call.signature == "(Ljava/lang/String;)V") call.replace("{}")
            }
        })
        val loader = Loader(pool).apply { delegateLoadingOf("org.w3c."); delegateLoadingOf("org.xml.") }
        val type = loader.loadClass(map.name)
        val parse = type.getDeclaredMethod("a", InputStream::class.java, Boolean::class.javaPrimitiveType)
        for ((width, height) in listOf("invalid" to "1", "1" to "invalid")) {
            val gate = MatchStartGate()
            val attempt = gate.beginLoading()!!
            var recovered = false
            val xml = "<map orientation=\"orthogonal\" width=\"$width\" height=\"$height\"/>"
            gate.runLoading(attempt, {
                parse.invoke(type.getDeclaredConstructor().newInstance(), ByteArrayInputStream(xml.toByteArray()), false)
            }, { fail("Malformed map must never present a game") }, { error ->
                assertIs<NumberFormatException>(error)
                assertFalse(gate.fail(attempt)!!.keepRoom)
                recovered = true
            })
            assertTrue(recovered)
        }
        // 对照原版可识别的地图格式错误和损坏 XML，也必须关闭本次已开始的对局。
        for (xml in listOf("<map orientation=\"isometric\" width=\"1\" height=\"1\"/>", "<map")) {
            val gate = MatchStartGate()
            val attempt = gate.beginLoading()!!
            var recovered = false
            gate.runLoading(attempt, {
                parse.invoke(type.getDeclaredConstructor().newInstance(), ByteArrayInputStream(xml.toByteArray()), false)
            }, { fail("Invalid map was presented") }, {
                assertTrue(it is Exception)
                assertFalse(gate.fail(attempt)!!.keepRoom)
                recovered = true
            })
            assertTrue(recovered)
        }
    }
}
