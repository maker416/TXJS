/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.game.units.custom.l
import com.corrodinggames.rts.game.units.custom.logicBooleans.`LogicString$Select`
import io.github.rwpp.game.mod.SelectChainParser
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult

@InjectClass(`LogicString$Select`::class)
object SelectLoadInject {
    @Inject("setArgumentsRaw", InjectMode.InsertBefore,
        "(Ljava/lang/String;Lcom/corrodinggames/rts/game/units/custom/l;Ljava/lang/String;)V")
    @Suppress("UNUSED_PARAMETER")
    fun `LogicString$Select`.parseDeepSelect(arguments: String?, unit: l?, function: String?): Any =
        if (SelectChainParser.apply(this, arguments, unit)) InterruptResult.Unit else Unit
}
