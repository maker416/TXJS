/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("unused")

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.game.units.bp
import com.corrodinggames.rts.game.units.ce
import com.corrodinggames.rts.game.units.custom.l
import com.corrodinggames.rts.game.units.custom.logicBooleans.BooleanParseException
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanLoader
import com.corrodinggames.rts.game.units.custom.logicBooleans.`LogicString$Select` as CoreSelect
import com.corrodinggames.rts.game.units.custom.logicBooleans.`LogicBoolean$ReturnType` as CoreReturnType
import io.github.rwpp.game.units.logic.IterativeSelectSupport
import io.github.rwpp.inject.Accessor
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.inject.SetInterfaceOn

/** 保留原版 Select 对象及字段，注入访问器避免反射进入每次求值。 */
@SetInterfaceOn([CoreSelect::class])
interface LogicSelectAccess {
    @Accessor("selector")
    var selectSelector: LogicBoolean?

    @Accessor("childA")
    var selectChildA: LogicBoolean?

    @Accessor("childB")
    var selectChildB: LogicBoolean?

    @Accessor("commonType")
    var selectCommonType: CoreReturnType?
}

@InjectClass(CoreSelect::class)
object LogicSelectInject {
    @Inject("setArgumentsRaw", InjectMode.InsertBefore)
    @Suppress("UNUSED_PARAMETER")
    fun CoreSelect.parseSelectArguments(arguments: String?, meta: l?, context: String?): Any {
        if (arguments == null || javaClass != CoreSelect::class.java) return Unit
        return if (IterativeSelectSupport.parseInto(this, arguments, SelectAdapter(meta))) {
            InterruptResult.Unit
        } else {
            Unit
        }
    }

    @Inject("read", InjectMode.Override)
    fun CoreSelect.readSelect(unit: bp?): Boolean =
        IterativeSelectSupport.selectedLeaf(this, SelectAdapter(unit = unit)).read(unit)

    @Inject("readNumber", InjectMode.Override)
    fun CoreSelect.readSelectNumber(unit: bp?): Float =
        IterativeSelectSupport.selectedLeaf(this, SelectAdapter(unit = unit)).readNumber(unit)

    @Inject("readString", InjectMode.Override)
    fun CoreSelect.readSelectString(unit: bp?): String? =
        IterativeSelectSupport.selectedLeaf(this, SelectAdapter(unit = unit)).readString(unit)

    @Inject("readUnit", InjectMode.Override)
    fun CoreSelect.readSelectUnit(unit: bp?): ce? =
        IterativeSelectSupport.selectedLeaf(this, SelectAdapter(unit = unit)).readUnit(unit)

    @Inject("getMatchFailReasonForPlayer", InjectMode.Override)
    fun CoreSelect.selectDebugReason(unit: bp?): String =
        IterativeSelectSupport.debugReason(this, SelectAdapter(unit = unit)) {
            it.getMatchFailReasonForPlayer(unit)
        }
}

private class SelectAdapter(
    private val meta: l? = null,
    private val unit: bp? = null,
) : IterativeSelectSupport.Adapter<LogicBoolean> {
    override fun parseLeaf(text: String, booleanExpected: Boolean): LogicBoolean? =
        LogicBooleanLoader.parseBooleanBlock(meta, text, booleanExpected)

    override fun newSelect(): LogicBoolean = CoreSelect()

    override fun setSelector(node: LogicBoolean, value: LogicBoolean?) {
        access(node).selectSelector = value ?: throw BooleanParseException("Expected non-null argument")
    }

    override fun setChildA(node: LogicBoolean, value: LogicBoolean?) {
        access(node).selectChildA = value ?: throw BooleanParseException("Expected non-null argument")
    }

    override fun setChildB(node: LogicBoolean, value: LogicBoolean?) {
        access(node).selectChildB = value ?: throw BooleanParseException("Expected non-null argument")
    }

    override fun finish(node: LogicBoolean) {
        val access = access(node)
        val firstType = access.selectChildA!!.returnType
        val secondType = access.selectChildB!!.returnType
        access.selectCommonType = firstType
        if (firstType !== secondType) {
            throw BooleanParseException(
                "Select() expected 2 and 3 argument to be the same type, got: $firstType and $secondType",
            )
        }
    }

    override fun requireBoolean(node: LogicBoolean, expression: () -> String) {
        val type = node.returnType
        if (type === CoreReturnType.voidReturn) {
            node.throwVoidReturnError(LogicBooleanLoader.breakOuterLayerBrackets(expression().trim()))
            throw RuntimeException("throwVoidReturnError")
        }
        if (type !== CoreReturnType.bool) {
            val text = LogicBooleanLoader.breakOuterLayerBrackets(expression().trim())
            throw BooleanParseException(
                "Function:'$text' is expected to return a boolean type but it returns type: $type",
            )
        }
    }

    override fun isSelect(node: LogicBoolean): Boolean = node.javaClass == CoreSelect::class.java

    override fun selector(node: LogicBoolean): LogicBoolean = access(node).selectSelector!!

    override fun childA(node: LogicBoolean): LogicBoolean = access(node).selectChildA!!

    override fun childB(node: LogicBoolean): LogicBoolean = access(node).selectChildB!!

    override fun readBoolean(node: LogicBoolean): Boolean = node.read(unit)

    private fun access(node: LogicBoolean): LogicSelectAccess = node as LogicSelectAccess
}
