/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import org.koin.core.component.KoinComponent

interface ModManager : KoinComponent {
    /**
     * 重新加载 mod 列表。
     *
     * @param forceImmediate 是否强制在当前线程立即执行重载，而非投递到游戏主线程后等待。
     *        默认 false：通过 [io.github.rwpp.game.Game.post] 把重载逻辑投递到游戏主循环消费，
     *        适用于游戏已运行（主循环在跑）的场景，如 Mods 页手动重载。
     *        传 true：直接在当前协程线程同步执行引擎重载方法，绕过主循环依赖。
     *        用于 mod 同步：下载完成时加入者仍处于加载阶段、游戏主循环 [com.corrodinggames.rts.game.i.b] 尚未启动，
     *        此时投递到主线程的 action 永远不会被消费，会导致 latch 永久阻塞、loading 弹窗卡死。
     * @param enabledByFileName 按磁盘文件名指定期望启用状态。在引擎扫完目录、加载单位定义之前应用。
     *        传入时：map 中有的按指定值启用/禁用；不在 map 中的新模组默认禁用。
     *        未启用的模组只登记元数据，不解析其单位定义。
     *        为 null 时保持引擎默认（仅按 modSettings 恢复；全新模组默认启用），供联机 MOD 同步等场景使用。
     */
    suspend fun modReload(
        forceImmediate: Boolean = false,
        enabledByFileName: Map<String, Boolean>? = null,
    )

    suspend fun modUpdate()

    /**
     * 重新扫描模组并重建单位定义注册表。
     *
     * 反编译确认 `bW.a(false, false)` 会先扫描目录，再加载 `f==false`（启用）的模组。
     * 此方法不携带 UI 选择状态，仅保留给兼容调用；模组管理页应使用 [modReload] 或
     * [modSaveChange]，确保扫描后、单位加载前应用明确的启用状态。
     */
    suspend fun modReregister()

    /**
     * 保存当前开关并重建单位注册表。
     *
     * [enabledByFileName] 与 [modReload] 含义相同。模组管理页必须传入完整 UI 状态，
     * 因为引擎重建注册表时也会再次扫描目录。
     */
    suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>? = null)

    fun getModByName(name: String): Mod?

    fun getAllMods(): List<Mod>
}