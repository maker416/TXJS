/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.logger
import org.koin.compose.koinInject
import kotlin.math.abs

/** 设置 → 开发者 →「账号弹窗调试 HUD」；仅桌面生效。 */
internal const val ACCOUNT_DIALOG_DEBUG_CONFIG_KEY = "accountDialogDebugHud"

private const val ACCOUNT_DIALOG_DEBUG_CONFIG_GROUP = "io.github.rwpp.ui.accountDialogDebug"

internal object AccountDialogDebugState {
    var hudEnabled by mutableStateOf(false)
    private var loadedFromDisk = false

    fun ensureLoaded(configIO: ConfigIO) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        hudEnabled = readAccountDialogDebugHudEnabled(configIO)
    }
}

internal fun readAccountDialogDebugHudEnabled(configIO: ConfigIO): Boolean =
    runCatching {
        configIO.readSingleConfig(ACCOUNT_DIALOG_DEBUG_CONFIG_GROUP, ACCOUNT_DIALOG_DEBUG_CONFIG_KEY)
            ?.toBooleanStrictOrNull()
    }.getOrNull() ?: false

internal fun setAccountDialogDebugHudEnabled(configIO: ConfigIO, enabled: Boolean) {
    AccountDialogDebugState.hudEnabled = enabled
    runCatching {
        configIO.saveSingleConfig(ACCOUNT_DIALOG_DEBUG_CONFIG_GROUP, ACCOUNT_DIALOG_DEBUG_CONFIG_KEY, enabled)
    }.onFailure {
        logger.warn("Failed to save account dialog debug HUD setting", it)
    }
}

internal object AccountDialogDebugMetrics {
    var cardTopInRootPx by mutableFloatStateOf(0f)
    var cardHeightPx by mutableFloatStateOf(0f)
    var nicknameFieldFocused by mutableStateOf(false)
    var primaryButtonHovered by mutableStateOf(false)
    var desktopSkipsImePadding by mutableStateOf(false)

    fun updateCardLayout(topPx: Float, heightPx: Float) {
        if (abs(topPx - cardTopInRootPx) > 0.5f) {
            cardTopInRootPx = topPx
        }
        if (abs(heightPx - cardHeightPx) > 0.5f) {
            cardHeightPx = heightPx
        }
    }
}

@Composable
internal fun rememberAccountDialogDebugEnabled(): Boolean {
    val appContext = koinInject<AppContext>()
    if (!appContext.isDesktop()) return false
    val configIO = koinInject<ConfigIO>()
    AccountDialogDebugState.ensureLoaded(configIO)
    return AccountDialogDebugState.hudEnabled
}

@Composable
internal fun AccountDialogDebugHud() {
    if (!rememberAccountDialogDebugEnabled()) return
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
    val m = AccountDialogDebugMetrics
    val line = buildString {
        append("cardY=${m.cardTopInRootPx.toInt()}px h=${m.cardHeightPx.toInt()}px")
        append(" | ime=${imeBottomPx}px nav=${navBarBottomPx}px")
        append(" | skipImePad=${m.desktopSkipsImePadding}")
        append(" | fieldFocus=${m.nicknameFieldFocused}")
        append(" | primaryHover=${m.primaryButtonHovered}")
    }
    LaunchedEffect(
        m.cardTopInRootPx,
        m.cardHeightPx,
        imeBottomPx,
        navBarBottomPx,
        m.desktopSkipsImePadding,
        m.nicknameFieldFocused,
        m.primaryButtonHovered,
    ) {
        println("[AccountDialogDebug] $line")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF8FE08F),
        )
    }
}
