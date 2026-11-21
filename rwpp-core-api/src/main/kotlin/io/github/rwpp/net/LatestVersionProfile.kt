/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long = 0,
)

data class LatestVersionProfile(
    val version: String,
    val body: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset> = emptyList(),
)

internal enum class ClientUpdatePlatform(val directory: String) {
    WINDOWS("windows"), ANDROID("android"),
}

/** An index directory serves a separate signed-installer manifest for each platform. */
internal fun fetchClientUpdate(client: OkHttpClient, apiUrl: String, platform: ClientUpdatePlatform?): LatestVersionProfile? {
    val staticIndex = apiUrl.endsWith('/')
    val indexUrl = if (staticIndex) "${apiUrl}${platform?.directory ?: return null}/latest.json" else apiUrl
    return runCatching {
        val request = Request.Builder().url(indexUrl).build()
        client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = Json.parse(response.body?.string() ?: return null).asObject()
            val version = json.getString("tag_name", "")
            require(version.matches(Regex("[vV]?\\d+(?:\\.\\d+)+(?:[-+].*)?"))) { "Invalid release version" }
            val assets = json.get("assets")?.asArray()?.map {
                val obj = it.asObject()
                ReleaseAsset(obj.getString("name", ""), obj.getString("browser_download_url", ""), obj.getLong("size", 0))
            }.orEmpty()
            val profile = LatestVersionProfile(version, json.getString("body", ""), json.getBoolean("prerelease", false), assets)
            if (staticIndex) {
                val plan = when (platform) {
                    ClientUpdatePlatform.WINDOWS -> profile.resolveDesktopUpdatePlan()
                    ClientUpdatePlatform.ANDROID -> profile.resolveAndroidUpdatePlan()
                    null -> null
                } ?: return null
                // Never require an update from a static index without a complete, verifiable installer.
                if (plan.partSizes.any { it <= 0 } || plan.sha256Url?.toHttpUrlOrNull()?.isHttps != true ||
                    plan.partUrls.any { it.toHttpUrlOrNull()?.isHttps != true }) return null
            }
            profile
        }
    }.getOrNull()
}
