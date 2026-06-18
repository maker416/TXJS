/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

@Single
@Serializable
data class Settings(
    var showWelcomeMessage: Boolean? = null,
    var ignoreVersion: String? = null,
    var autoCheckUpdate: Boolean = true,
    var enhancedReinforceTroops: Boolean = false,
    var backgroundImagePath: String? = null,
    var selectedTheme: String? = null,
    var backgroundTransparency: Float = 0.7f,
    var backgroundImageTransparency: Float = 1f,
    var showBuildingAttackRange: Boolean = false,
    var showExtraButton: Boolean = false,
    /** @see unitAttackRangeTypes */
    var showAttackRangeUnit: String = "Never",
    var enableAnimations: Boolean = false,
    var maxDisplayUnitGroupCount: Int = 7,
    var displayUnitGroupXOffset: Int = 0,
    var changeGameTheme: Boolean = false,
    var showUnitTargetLine: Boolean = false,
    var improvedHealthBar: Boolean = false,
    var mouseMoveView: Boolean = false,
    //var pathfindingOptimization: Boolean = false,
    var boldText: Boolean = false,
    var forceEnglish: Boolean = false, // @Deprecated: 已由 language 字段替代，保留仅用于旧配置兼容
    var language: String = "zh", // "auto": 自动检测, "zh": 简体中文, "en": 英文
    var enableOffscreenPanel: Boolean = false,
    var displayTimeInGame: Boolean = false,
    var effectLimitForAllEffects: String = "Keep", // Zero, Keep, Unlimited

    // --- Android ---
    var enableVolumeKeyMapping: Boolean = false,
    var enableLargerKeys: Boolean = false,
    // ---------------

    // --- Desktop ---
    var renderingBackend: String = "OpenGL", // Default, Software, OpenGL
    /**
     * Decide whether to allow game in full screen (Only PC)
     */
    var isFullscreen: Boolean = true,
    // ---------------

    // Offscreen Panel
    var enableQuickSelectMenu: Boolean = false,

    /**
     * 配置版本号，用于迁移旧配置。null 表示旧版本配置（迁移前）。
     */
    var configVersion: Int? = null
) : Config {
    companion object {
        val unitAttackRangeTypes = listOf("Never", "Land", "Air", "All")
    }

    override fun migrate() {
        if (configVersion == null) {
            // v1: enableAnimations 默认值由 true 改为 false，重置旧配置中由旧默认值写入的值
            enableAnimations = false
            configVersion = 1
        }
    }
}
