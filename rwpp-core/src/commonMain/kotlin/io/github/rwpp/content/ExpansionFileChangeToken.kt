/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import java.io.File

internal data class ExpansionFileChange(val token: String, val epochSecond: Long)

internal expect fun readExpansionFileChange(file: File): ExpansionFileChange?

/** Called by the installation verifier on its I/O thread. */
internal fun expansionFileChangeToken(file: File): String? {
    val first = readExpansionFileChange(file) ?: return null
    val now = System.currentTimeMillis()
    val second = now / 1000
    if (first.epochSecond !in 1..second) return null
    if (first.epochSecond < second) return first.token

    // A fresh file may still change within this filesystem timestamp tick. Sample it again
    // after the tick so the initial activation can persist a reusable verification.
    try {
        Thread.sleep(1000 - now % 1000)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    }
    val latest = readExpansionFileChange(file) ?: return null
    return latest.token.takeIf { latest.epochSecond in 1 until System.currentTimeMillis() / 1000 }
}
