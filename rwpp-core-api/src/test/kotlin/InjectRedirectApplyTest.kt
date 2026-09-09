/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.inject

import io.github.rwpp.inject.runtime.InjectApi
import javassist.CtClass
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 模组同步 v2「握手放行」注入机制的回归测试。
 *
 * 用与运行时完全相同的 [InjectApi] 对真实 lib/game-lib.jar 的 `ad` 类依次应用：
 * 1. RedirectMethod：把 `ad.c(au)` 内的 `l.z()`（单位校验和读取，比较与踢人日志共两处）
 *    重定向到 `NetworkInject.redirectUnitsChecksum`；
 * 2. InsertBefore：`ad.c(au)` 的 onReceivePacket 注入（会把方法体复制为 `__original__c` 并改写跳板）。
 *
 * 验证的关键不变量：redirect 必须先于 inject 应用（与 Builder.applyConfig 的顺序一致），
 * 否则 InsertBefore 先把方法体搬走，redirect 在跳板里找不到 `l.z()` 会静默失效。
 */
class InjectRedirectApplyTest {

    private val gameLib = File("../lib/game-lib.jar")

    @Test
    fun redirectThenInsertBeforeKeepsRedirection() {
        assertTrue(gameLib.exists(), "lib/game-lib.jar 不存在（构建前置条件），无法运行注入机制测试")
        GameLibraries.includes.add(GameLibraries.`game-lib`)
        GameLibraries.`game-lib`.load(gameLib)
        val adName = "com.corrodinggames.rts.gameFramework.j.ad"

        // 1. RedirectMethod（配置与 rwpp-desktop NetworkInject.redirectUnitsChecksum 一致）
        InjectApi.redirect(
            className = adName,
            hasReceiver = true,
            methodName = "c",
            methodDesc = "(Lcom/corrodinggames/rts/gameFramework/j/au;)V",
            targetClassName = "com.corrodinggames.rts.gameFramework.l",
            targetMethodName = "z",
            targetMethodDesc = "()I",
            injectFunctionPath = "io.github.rwpp.desktop.impl.inject.NetworkInject.redirectUnitsChecksum",
            pathType = PathType.Path,
        )

        val ad = GameLibraries.includes.first().classTree.defPool.get(adName)
        val auParam = arrayOf(GameLibraries.includes.first().classTree.defPool.get("com.corrodinggames.rts.gameFramework.j.au"))
        // 生成了 __redirect__c__z 辅助方法，且 ad.c(au) 内两处 l.z() 调用全部被替换
        assertNotNull(ad.declaredMethods.firstOrNull { it.name == "__redirect__c__z" })
        assertEquals(0, countCalls(ad, "c", auParam, "com.corrodinggames.rts.gameFramework.l", "z"))
        assertEquals(2, countCalls(ad, "c", auParam, adName, "__redirect__c__z"))

        // 2. InsertBefore（配置与 NetworkInject.onReceivePacket 一致）：
        //    复制体 __original__c 必须保留重定向结果，跳板 ad.c 不再含校验和调用
        InjectApi.injectMethod(
            className = adName,
            hasReceiver = false,
            methodName = "c",
            methodDesc = "(Lcom/corrodinggames/rts/gameFramework/j/au;)V",
            injectMode = InjectMode.InsertBefore,
            injectFunctionPath = "io.github.rwpp.desktop.impl.inject.NetworkInject.onReceivePacket",
            returnClassIsVoid = false,
            pathType = PathType.Path,
        )
        assertNotNull(ad.declaredMethods.firstOrNull { it.name == "__original__c" })
        assertEquals(0, countCalls(ad, "__original__c", auParam, "com.corrodinggames.rts.gameFramework.l", "z"))
        assertEquals(2, countCalls(ad, "__original__c", auParam, adName, "__redirect__c__z"))
        assertEquals(0, countCalls(ad, "c", auParam, adName, "__redirect__c__z"))
    }

    private fun countCalls(
        clazz: CtClass,
        methodName: String,
        params: Array<CtClass>,
        targetClass: String,
        targetMethod: String,
    ): Int {
        var count = 0
        clazz.getDeclaredMethod(methodName, params).instrument(object : ExprEditor() {
            override fun edit(m: MethodCall) {
                if (m.className == targetClass && m.methodName == targetMethod) count++
            }
        })
        return count
    }
}
