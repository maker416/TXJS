/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.inject.runtime

import javassist.CtClass

/** Installs the same shared select traversal used by the typed client injection adapters. */
object SelectOptimizationBytecode {
    const val SELECT_CLASS = "com.corrodinggames.rts.game.units.custom.logicBooleans.LogicString\$Select"
    private const val SUPPORT = "io.github.rwpp.game.units.logic.ReflectiveSelectSupport"

    @JvmStatic
    fun patch(clazz: CtClass) {
        require(clazz.name == SELECT_CLASS) { "Expected $SELECT_CLASS, got ${clazz.name}" }
        clazz.getDeclaredMethod("setArgumentsRaw").insertBefore(
            "{ if ($SUPPORT.parseInto(\$0, \$1, \$2, \$3)) return; }",
        )
        val logic = "com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean"
        for (name in listOf("read", "readNumber", "readString", "readUnit")) {
            val method = clazz.getDeclaredMethod(name)
            method.setBody("{ return (($logic)$SUPPORT.selectedLeaf(\$0, \$1)).$name(\$1); }")
        }
        clazz.getDeclaredMethod("getMatchFailReasonForPlayer").setBody(
            "{ return $SUPPORT.debugReason(\$0, \$1); }",
        )
    }
}
