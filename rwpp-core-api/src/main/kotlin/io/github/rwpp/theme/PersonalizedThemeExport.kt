/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun PersonalizedThemeCatalog.platformReleaseJson(packId: String, version: String): String {
    val value = buildJsonObject {
        put("platformPackId", packId)
        put("version", version)
        put("catalog_hash", catalogHash)
        put("manifest_sha256", manifestSha256)
        put("assets", buildJsonObject { assets.forEach { (path, hash) -> put(path, hash) } })
        put("variants", buildJsonArray { variants.forEach { variant ->
            add(buildJsonObject { put("slot", variant.slot); put("variant_id", variant.variantId) })
        } })
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value)
}
