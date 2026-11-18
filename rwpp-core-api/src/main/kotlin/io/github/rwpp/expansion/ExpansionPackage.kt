/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.expansion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val EXPANSION_MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024

@Serializable
data class ExpansionArtBinding(
    @SerialName("pack_id") val packId: String,
    val version: String,
    @SerialName("catalog_hash") val catalogHash: String,
)

@Serializable
data class ExpansionBinding(
    val version: String,
    val sha256: String,
    val size: Long,
    @SerialName("art_binding") val artBinding: ExpansionArtBinding,
) {
    fun valid(): Boolean = VERSION.matches(version) && SHA.matches(sha256) && size in 1..EXPANSION_MAX_ARCHIVE_BYTES &&
        UUID.matches(artBinding.packId) && VERSION.matches(artBinding.version) && SHA.matches(artBinding.catalogHash)
}

private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val SHA = Regex("[0-9a-f]{64}")
private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
