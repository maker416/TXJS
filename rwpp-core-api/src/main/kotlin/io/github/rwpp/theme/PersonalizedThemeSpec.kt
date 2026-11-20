/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

const val PERSONALIZED_THEME_SCHEMA_VERSION = 1
const val PERSONALIZED_THEME_MAX_VARIANTS_PER_SLOT = 64
val PERSONALIZED_THEME_SLOT_IDS: Set<String> = setOf(
    "room.player.background", "room.player.frame", "room.player.badges", "room.player.info",
)

private val personalizedVariantId = Regex("[a-z][a-z0-9_-]{0,63}")
private val PLATFORM_PACK_ID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** 个性化目录须可在 Windows 与 Android 同时表示，且与平台发布路径规则一致。 */
fun isSafePersonalizedThemeAssetPath(path: String): Boolean {
    if (!isSafeThemeAssetPath(path) || path.toByteArray(Charsets.UTF_8).size > 256 || path.any { it in "<>\"|?*" }) return false
    return path.split('/').all { part ->
        val base = part.substringBefore('.').uppercase()
        part.trimEnd('.', ' ') == part && base !in setOf("CON", "PRN", "AUX", "NUL") &&
            !(base.length == 4 && (base.startsWith("COM") || base.startsWith("LPT")) && base.last() in '1'..'9')
    }
}

/** 只声明包内可选内容；账号与 variant 的分配由开放平台维护，不存入美术包。 */
@Serializable
data class PersonalizedThemeSpec(
    val schemaVersion: Int = PERSONALIZED_THEME_SCHEMA_VERSION,
    val platformPackId: String = "",
    val slots: Map<String, PersonalizedSlotSpec> = emptyMap(),
) {
    fun sanitized(): PersonalizedThemeSpec {
        if (schemaVersion != PERSONALIZED_THEME_SCHEMA_VERSION || !PLATFORM_PACK_ID_REGEX.matches(platformPackId)) {
            return PersonalizedThemeSpec()
        }
        return copy(
            platformPackId = platformPackId.lowercase(),
            slots = slots.filterKeys { it in PERSONALIZED_THEME_SLOT_IDS }.mapValues { (slot, spec) ->
                PersonalizedSlotSpec(spec.variants.asSequence()
                    .filter { (id, _) -> personalizedVariantId.matches(id) }
                    .take(PERSONALIZED_THEME_MAX_VARIANTS_PER_SLOT)
                    .associate { (id, variant) -> id to variant.sanitized(slot) })
            },
        )
    }

    /** 归一化后的实际引用，摘要与渲染必须使用同一集合。 */
    fun referencedAssetPaths(): Set<String> = buildSet {
        sanitized().slots.values.forEach { slot -> slot.variants.values.forEach { variant ->
            if (variant.image.isNotEmpty()) add(variant.image)
            if (variant.avatarFrameImage.isNotEmpty()) add(variant.avatarFrameImage)
            variant.content.rows.forEach { row -> row.cells.forEach { cell ->
                if (cell.iconImage.isNotEmpty()) add(cell.iconImage)
            } }
        } }
    }
}

@Serializable
data class PersonalizedSlotSpec(val variants: Map<String, PersonalizedVariantSpec> = emptyMap())

/** 每种槽位只消费对应的类型字段，不允许远端下发路径、样式或文本。 */
@Serializable
data class PersonalizedVariantSpec(
    val image: String = "",
    val borderColor: String = "",
    val borderWidth: Int = 1,
    val content: RoomPlayerCardContentSpec = RoomPlayerCardContentSpec(),
    val avatarFrameImage: String = "",
    val displayName: String = "",
) {
    fun sanitized(slot: String): PersonalizedVariantSpec = when (slot) {
        "room.player.background" -> PersonalizedVariantSpec(
            image = image.takeIf(::isSafePersonalizedThemeAssetPath).orEmpty(),
            displayName = displayName.trim().filterNot(Char::isISOControl).take(48),
        )
        "room.player.frame" -> PersonalizedVariantSpec(
            avatarFrameImage = avatarFrameImage.takeIf(::isSafePersonalizedThemeAssetPath).orEmpty(),
            displayName = displayName.trim().filterNot(Char::isISOControl).take(48),
            borderColor = borderColor.takeIf { parseColorHex(it) != null }?.trim()?.uppercase().orEmpty(),
            borderWidth = borderWidth.coerceIn(0, 6),
        )
        "room.player.badges", "room.player.info" -> PersonalizedVariantSpec(
            content = content.sanitized().let { safe -> safe.copy(avatarImage = "", rows = safe.rows.map { row ->
                row.copy(cells = row.cells.map { cell -> cell.copy(iconImage = cell.iconImage.takeIf(::isSafePersonalizedThemeAssetPath).orEmpty()) })
            }) },
        )
        else -> PersonalizedVariantSpec()
    }
}

fun validatePersonalizedTheme(spec: PersonalizedThemeSpec): ThemeValidation {
    if (spec.schemaVersion != PERSONALIZED_THEME_SCHEMA_VERSION) return ThemeValidation(
        warnings = listOf("不支持的个性化 schemaVersion，已使用美术包默认外观: ${spec.schemaVersion}"),
    )
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    if (spec.platformPackId.isNotEmpty() || spec.slots.isNotEmpty()) {
        if (!PLATFORM_PACK_ID_REGEX.matches(spec.platformPackId)) errors += "个性化 platformPackId 必须为完整 UUID"
    }
    fun path(key: String, value: String) {
        if (value.isNotEmpty() && !isSafePersonalizedThemeAssetPath(value)) errors += "个性化资源路径非法: $key = $value"
    }
    fun color(key: String, value: String) {
        if (value.isNotEmpty() && parseColorHex(value) == null) warnings += "个性化色值非法已忽略: $key = $value"
    }
    spec.slots.forEach { (slot, definition) ->
        if (slot !in PERSONALIZED_THEME_SLOT_IDS) {
            warnings += "未知个性化槽位已忽略: $slot"
            return@forEach
        }
        if (definition.variants.size > PERSONALIZED_THEME_MAX_VARIANTS_PER_SLOT) {
            warnings += "个性化槽位 $slot 的 variants 超过 $PERSONALIZED_THEME_MAX_VARIANTS_PER_SLOT，已截断"
        }
        definition.variants.forEach { (id, variant) ->
            if (!personalizedVariantId.matches(id)) warnings += "非法个性化 variant id 已忽略: $slot/$id"
            val key = "personalization.slots.$slot.variants.$id"
            path("$key.image", variant.image)
            path("$key.avatarFrameImage", variant.avatarFrameImage)
            color("$key.borderColor", variant.borderColor)
            path("$key.content.avatarImage", variant.content.avatarImage)
            if (variant.borderWidth !in 0..6) warnings += "个性化描边宽度已限制为 0..6: $key"
            if (variant.content.rows.size > 12) warnings += "个性化内容行超过 12，已截断: $key"
            variant.content.rows.forEachIndexed { rowIndex, row ->
                if (row.cells.size > 12) warnings += "个性化内容格超过 12，已截断: $key.rows[$rowIndex]"
                row.cells.forEachIndexed { cellIndex, cell ->
                    val cellKey = "$key.content.rows[$rowIndex].cells[$cellIndex]"
                    path("$cellKey.iconImage", cell.iconImage)
                    color("$cellKey.contentColor", cell.contentColor)
                    color("$cellKey.backgroundColor", cell.backgroundColor)
                    color("$cellKey.borderColor", cell.borderColor)
                }
            }
        }
    }
    return ThemeValidation(errors, warnings)
}

@Serializable
data class PersonalizedThemeCatalogVariant(val slot: String, val variantId: String)

/** 平台发布时登记的本地实测目录；catalogHash 同时绑定原始清单及全部引用资源。 */
@Serializable
data class PersonalizedThemeCatalog(
    val catalogHash: String,
    val manifestSha256: String,
    val assets: Map<String, String>,
    val variants: List<PersonalizedThemeCatalogVariant>,
)

private const val PERSONALIZED_ASSET_MAX_BYTES = 32L * 1024 * 1024
private const val PERSONALIZED_ASSETS_MAX_BYTES = 128L * 1024 * 1024

/** IO 阶段调用一次并缓存；资源缺失、越界、超限或 IO 失败只关闭个性化。 */
fun personalizedThemeCatalog(dir: File, spec: PersonalizedThemeSpec): PersonalizedThemeCatalog? =
    personalizedThemeCatalog(dir, spec, allowEmptyVariants = false)

internal fun personalizedThemeCatalog(
    dir: File, spec: PersonalizedThemeSpec, allowEmptyVariants: Boolean,
): PersonalizedThemeCatalog? = runCatching {
    if (!validatePersonalizedTheme(spec).isValid) return null
    val safe = spec.sanitized()
    if (safe.platformPackId.isEmpty() || !allowEmptyVariants && safe.slots.values.all { it.variants.isEmpty() }) return null
    val root = dir.toPath().toRealPath()
    fun checkedPath(relative: String): Path {
        require(isSafePersonalizedThemeAssetPath(relative))
        return root.resolve(relative).toRealPath().also { path ->
            require(path.startsWith(root) && Files.isRegularFile(path))
        }
    }
    fun hash(relative: String): Pair<String, Long> {
        val path = checkedPath(relative)
        require(Files.size(path) <= PERSONALIZED_ASSET_MAX_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= PERSONALIZED_ASSET_MAX_BYTES)
                digest.update(buffer, 0, count)
            }
        }
        require(checkedPath(relative) == path)
        return digest.digest().hex() to size
    }
    val manifest = hash("theme.toml").first
    var total = 0L
    // UTF-8 字节序与 Go sort.Strings 一致；不能使用 JVM 的 UTF-16 字符串序。
    val paths = safe.referencedAssetPaths().map { it to it.toByteArray(Charsets.UTF_8) }
        .sortedWith { first, second -> compareUnsignedBytes(first.second, second.second) }
        .map { it.first }
    require(paths.size <= 256 && paths.map { it.lowercase() }.distinct().size == paths.size)
    val assets = paths.associateWith { path ->
        val (sha256, size) = hash(path)
        total += size
        require(total <= PERSONALIZED_ASSETS_MAX_BYTES)
        sha256
    }
    val input = buildString {
        append("RWJS-ART-CATALOG-1\n").append(manifest).append('\n')
        assets.forEach { (path, sha256) -> append(path).append('\t').append(sha256).append('\n') }
    }
    val catalogHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).hex()
    val variants = safe.slots.toSortedMap().flatMap { (slot, value) ->
        value.variants.keys.sorted().map { id -> PersonalizedThemeCatalogVariant(slot, id) }
    }
    PersonalizedThemeCatalog(catalogHash, manifest, assets, variants)
}.getOrNull()

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
    for (index in 0 until minOf(first.size, second.size)) {
        val difference = (first[index].toInt() and 255) - (second[index].toInt() and 255)
        if (difference != 0) return difference
    }
    return first.size.compareTo(second.size)
}
