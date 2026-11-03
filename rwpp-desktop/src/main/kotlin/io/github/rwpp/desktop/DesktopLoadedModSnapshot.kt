/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.i.b
import io.github.rwpp.appKoin
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.LoadedModSnapshot
import io.github.rwpp.game.mod.LoadedModSource
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.logger

/** Only successful startup/reload boundaries may publish what the native engine actually loaded. */
object DesktopLoadedModSnapshot {
    private val snapshot = LoadedModSnapshot()

    fun beginLoad() = snapshot.beginLoad()

    fun beforeLoad(records: List<b>) {
        try {
            snapshot.beforeLoad(records.map {
                val path = it.h()
                LoadedModSource(it.s.orEmpty(), path, !it.f, it.R, path.contains(".network.rwmod"))
            }, appKoin.get<NetworkModCache>())
        } catch (error: Exception) {
            snapshot.invalidate()
            logger.debug("[MODSYNC] cannot capture pre-load sources: {}", error.message)
        }
    }

    fun completeLoad(token: Long, mods: List<Mod>) {
        if (GameStartSafety.requiresRestart) {
            snapshot.invalidate()
            return
        }
        try {
            snapshot.completeLoad(token, mods.map(LoadedModSource::from), appKoin.get<NetworkModCache>())
        } catch (error: Exception) {
            snapshot.invalidate()
            logger.debug("[MODSYNC] cannot capture loaded sources: {}", error.message)
        }
    }

    fun isExact(
        mods: List<Mod>, descriptors: List<NetworkModDescriptor>, cache: NetworkModCache,
        checkCancelled: () -> Unit,
    ): Boolean = !GameStartSafety.requiresRestart &&
        snapshot.isExact(mods.map(LoadedModSource::from), descriptors, cache, checkCancelled)
}
