/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.utils

/**
 * Parses the leading numeric component of a version segment (e.g. `"0-beta1"` → `0`).
 * Non-numeric or empty segments resolve to `0`, so API tags like `v1.9.0-beta1` never throw.
 */
internal fun parseVersionPart(part: String): Int {
    val digits = part.trim().takeWhile { it.isDigit() }
    if (digits.isEmpty()) return 0
    return digits.toIntOrNull() ?: 0
}

private fun versionSegments(version: String): List<Int> =
    version.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.')
        .map(::parseVersionPart)

/**
 * Compares two version strings by numeric dot-separated components.
 * @param version1 The first version string to compare.
 * @param version2 The second version string to compare.
 * @return 0 if the versions are equal, 1 if version1 is greater than version2, and -1 if version1 is less than version2.
 */
fun compareVersions(version1: String, version2: String): Int {
    val v1Parts = versionSegments(version1)
    val v2Parts = versionSegments(version2)

    val maxLength = maxOf(v1Parts.size, v2Parts.size)

    for (i in 0 until maxLength) {
        val part1 = if (i < v1Parts.size) v1Parts[i] else 0
        val part2 = if (i < v2Parts.size) v2Parts[i] else 0

        if (part1 > part2) {
            return 1
        } else if (part1 < part2) {
            return -1
        }
    }

    return 0
}