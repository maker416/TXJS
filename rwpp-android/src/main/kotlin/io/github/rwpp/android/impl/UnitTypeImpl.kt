/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import io.github.rwpp.appKoin
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.units.MovementType
import io.github.rwpp.game.units.UnitType
import io.github.rwpp.inject.SetInterfaceOn

@SetInterfaceOn([com.corrodinggames.rts.game.units.el::class])
interface UnitTypeImpl : UnitType {
    val self: com.corrodinggames.rts.game.units.el
    override val name: String
        get() = self.i()
    override val displayName: String
        get() = self.e()
    override val description: String
        get() = self.f()
    override val movementType: MovementType
        get() = MovementType.valueOf(self.o().name)
    override val isBuilder: Boolean
        get() = self.l()
    override val mod: Mod?
        // 与 Mod.name 同一口径：用引擎的 i.b.a() 回退链（title ?: ?: 文件名），
        // 否则无 title 的模组无法归属到所属 mod
        get() = (self as? com.corrodinggames.rts.game.units.custom.l)?.J?.a()?.let(appKoin.get<ModManager>()::getModByName)
}