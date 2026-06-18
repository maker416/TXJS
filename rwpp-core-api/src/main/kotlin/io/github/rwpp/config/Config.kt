/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

/**
 * Describe a config.
 *
 * Any config that can be saved should be implemented under this package, and the corresponding code should be generated using Koin.
 *
 * @see ConfigIO
 * @see ConfigModule
 */
interface Config {
    /**
     * 从磁盘读取配置后调用，用于将旧版本配置中的值迁移到新默认值。
     *
     * 仅在 [ConfigIO.readAllConfig] 中于 `setPropertyFromObject` 之后调用一次。
     */
    fun migrate() {}
}