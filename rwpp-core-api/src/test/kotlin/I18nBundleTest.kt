/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * i18n bundle 的运行时解析保障：`bundle_*.toml` 只在运行时才被 `Toml.parseToTomlTable` 解析，
 * 编译期零校验，非法 TOML 会导致 App 启动即崩。这里用与线上一致的解析器实跑一遍，
 * 并校验模组同步新增的键确实存在于 `[mod]` 段。
 */
class I18nBundleTest {
    private fun bundleFile(name: String): File {
        // Gradle Test 的 workingDir 通常是模块目录（rwpp-core-api），兼容仓库根目录的情况
        val candidates = listOf(
            File("../rwpp-core/src/commonMain/composeResources/files/$name"),
            File("rwpp-core/src/commonMain/composeResources/files/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("bundle not found, tried: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun zhAndEnBundlesParseAndContainModSyncKeys() {
        val requiredModKeys = listOf(
            "manifestTimeout",
            "manifestFailed",
            "downloadFailed",
            "integrityFailed",
            "hostDisconnected",
            "chunkRetryExceeded",
            "peerLeftDuringTransfer",
        )
        listOf("bundle_zh.toml", "bundle_en.toml").forEach { name ->
            val file = bundleFile(name)
            val table = Toml.parseToTomlTable(file.readText())
            val mod = table["mod"]
            assertTrue(mod is TomlTable, "[mod] section missing in $name")
            requiredModKeys.forEach { key ->
                assertNotNull(mod[key], "missing key mod.$key in $name")
            }
        }
    }
}
