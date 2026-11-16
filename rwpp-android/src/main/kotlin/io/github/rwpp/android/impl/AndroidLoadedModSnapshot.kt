/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import io.github.rwpp.appKoin
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.LoadedModSnapshot
import io.github.rwpp.game.mod.LoadedModSource
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor

internal object AndroidLoadedModSnapshot {
    private val snapshot = LoadedModSnapshot()
    fun beginLoad() = snapshot.beginLoad()
    fun invalidate() = snapshot.invalidate()
    fun beforeLoad() {
        try {
            snapshot.beforeLoad(appKoin.get<ModManager>().getAllMods().map(LoadedModSource::from), appKoin.get())
        } catch (_: Exception) { snapshot.invalidate() }
    }
    fun completeLoad(token: Long, mods: List<Mod>) {
        if (GameStartSafety.requiresRestart) { snapshot.invalidate(); return }
        try { snapshot.completeLoad(token, mods.map(LoadedModSource::from), appKoin.get()) }
        catch (_: Exception) { snapshot.invalidate() }
    }
    fun isExact(mods: List<Mod>, descriptors: List<NetworkModDescriptor>, cache: NetworkModCache, check: () -> Unit) =
        !GameStartSafety.requiresRestart && snapshot.isExact(mods.map(LoadedModSource::from), descriptors, cache, check)
}
