/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.game.units.custom.ab
import com.corrodinggames.rts.game.units.custom.l
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.ReloadAbortToVanilla
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.logger
import java.util.HashMap

/**
 * 进房后同步期间推迟 `l.a(ab, HashMap)` 抛出的缺单位异常。
 * 该方法在读完整包后再抛，跳过它不会打乱后续包解析，连接才能在下载期保持可聊天。
 */
@InjectClass(l::class)
object CustomUnitInject {
    @Inject(
        "a",
        InjectMode.InsertBefore,
        "(Lcom/corrodinggames/rts/game/units/custom/ab;Ljava/util/HashMap;)V",
    )
    @Suppress("UNUSED_PARAMETER")
    fun deferMissingUnits(mismatch: ab, mods: HashMap<*, *>): Any {
        if (!ModSyncController.shouldDeferMissingUnitsCheck()) return Unit
        logger.info("[MODSYNC] defer engine missing-unit check until in-room sync finishes")
        return InterruptResult.Unit
    }
}

/**
 * 桌面 `ag.h()` 按目录加载入口（与 Android `ag.e()` 同签名，此方法为 public）。
 * 取消同步时抛 [ReloadAbortToVanilla]，中止后续自定义模组解析。
 */
@InjectClass(com.corrodinggames.rts.game.units.custom.ag::class)
object CustomUnitLoadInject {
    @Inject(
        "a",
        InjectMode.InsertBefore,
        "(Ljava/lang/String;IZLcom/corrodinggames/rts/gameFramework/i/b;Ljava/lang/String;Ljava/lang/String;)V",
    )
    @Suppress("UNUSED_PARAMETER")
    fun abortCustomModLoadIfRequested(
        path: String?,
        type: Int,
        recursive: Boolean,
        mod: com.corrodinggames.rts.gameFramework.i.b?,
        source: String?,
        extra: String?,
    ): Any {
        if (mod != null && KeepConnectedReload.shouldAbortCustomModLoad()) {
            logger.info("[MODSYNC] abort custom mod load: ${mod.a()}")
            throw ReloadAbortToVanilla()
        }
        return Unit
    }
}
