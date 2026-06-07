/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/** Quick-host command family used when creating a multiplayer room from the UI. */
enum class HostCommandPrefix {
    /** Q-series: Qnews / Qmods / QC / QCM with optional P/U/C/Z suffix params. */
    Q,
    /** R-series: Rnewp / Rnewupp / Rmodp / Rmodupp with max player embedded in the prefix. */
    R,
}

/** Default max players when hosting with [HostCommandPrefix.R] and no value is entered. */
const val DEFAULT_R_HOST_MAX_PLAYERS = 10
