/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateDownloadPlanTest {

    private fun profileOf(vararg assetNames: String): LatestVersionProfile =
        LatestVersionProfile(
            version = "1.6.4",
            body = "",
            prerelease = false,
            assets = assetNames.map { ReleaseAsset(it, "https://gitee.com/dl/$it") },
        )

    @Test
    fun `split volumes are detected ordered and joined with sha256`() {
        val profile = profileOf(
            "RWJS-Setup.zip.002",
            "RWJS-Setup.zip.sha256",
            "RWJS-Setup.zip.001",
            "RWJS-Setup.zip.003",
            "rwpp-android-release.apk",
        )

        val plan = profile.resolveDesktopUpdatePlan()

        assertEquals(
            listOf(
                "https://gitee.com/dl/RWJS-Setup.zip.001",
                "https://gitee.com/dl/RWJS-Setup.zip.002",
                "https://gitee.com/dl/RWJS-Setup.zip.003",
            ),
            plan?.partUrls,
        )
        assertEquals("https://gitee.com/dl/RWJS-Setup.zip.sha256", plan?.sha256Url)
    }

    @Test
    fun `missing sha256 asset yields null sha256Url`() {
        val profile = profileOf("RWJS-Setup.zip.001", "RWJS-Setup.zip.002")

        val plan = profile.resolveDesktopUpdatePlan()

        assertEquals(2, plan?.partUrls?.size)
        assertNull(plan?.sha256Url)
    }

    @Test
    fun `non contiguous volume numbers fall back to exe asset`() {
        // 缺 .002，序号不连续，不允许按分卷更新
        val profile = profileOf(
            "RWJS-Setup.zip.001",
            "RWJS-Setup.zip.003",
            "RWJS-Setup.exe",
        )

        val plan = profile.resolveDesktopUpdatePlan()

        assertEquals(listOf("https://gitee.com/dl/RWJS-Setup.exe"), plan?.partUrls)
        assertNull(plan?.sha256Url)
    }

    @Test
    fun `single volume is not treated as split package`() {
        val profile = profileOf("RWJS-Setup.zip.001", "RWJS-Setup.exe")

        val plan = profile.resolveDesktopUpdatePlan()

        assertEquals(listOf("https://gitee.com/dl/RWJS-Setup.exe"), plan?.partUrls)
    }

    @Test
    fun `legacy single exe release still works`() {
        val profile = profileOf("RWJS-Setup.exe", "rwpp-android-release.apk")

        val plan = profile.resolveDesktopUpdatePlan()

        assertEquals(listOf("https://gitee.com/dl/RWJS-Setup.exe"), plan?.partUrls)
        assertNull(plan?.sha256Url)
    }

    @Test
    fun `no desktop asset returns null`() {
        val profile = profileOf("rwpp-android-release.apk", "notes.txt")

        assertNull(profile.resolveDesktopUpdatePlan())
    }

    @Test
    fun `android plan picks apk asset only`() {
        val profile = profileOf(
            "RWJS-Setup.zip.001",
            "RWJS-Setup.zip.002",
            "rwpp-android-release.apk",
        )

        val plan = profile.resolveAndroidUpdatePlan()

        assertEquals(listOf("https://gitee.com/dl/rwpp-android-release.apk"), plan?.partUrls)
        assertNull(plan?.sha256Url)
    }

    @Test
    fun `zip and tar gz assets are not mistaken for split volumes`() {
        val profile = profileOf("RWJS-Setup.zip", "game.tar.gz")

        assertTrue(profile.resolveDesktopUpdatePlan() == null)
    }
}
