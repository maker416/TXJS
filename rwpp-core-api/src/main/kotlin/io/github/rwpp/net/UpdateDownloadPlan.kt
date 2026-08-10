/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/**
 * 一次自动更新的下载计划。
 *
 * @param partUrls 需要按顺序下载的分卷下载地址；单文件安装包时只有一个元素
 * @param sha256Url 可选的合并 zip 的 SHA-256 校验文件地址（仅桌面端分卷更新可能存在）
 */
data class UpdateDownloadPlan(
    val partUrls: List<String>,
    val sha256Url: String? = null,
)

/** 匹配分卷资产名，如 `RWJS-Setup.zip.001`（7-Zip/WinRAR 分卷命名约定） */
private val SPLIT_PART_REGEX = Regex("""^(.+)\.zip\.(\d+)$""")

/**
 * 从 release 资产中解析桌面端更新下载计划。
 *
 * 优先识别 zip 分卷组（`xxx.zip.001`、`xxx.zip.002`…，Gitee 单文件 100MB 限制下的
 * 桌面安装包发布形式，至少 2 卷）：按前缀分组、序号排序，要求序号从 1 开始连续。
 * 同时查找对应的 `<前缀>.zip.sha256` 校验资产。
 *
 * 没有任何分卷组（或分卷序号不连续）时，回退到旧的单 `.exe` 资产；
 * 两者都不存在时返回 null。
 */
fun LatestVersionProfile.resolveDesktopUpdatePlan(): UpdateDownloadPlan? {
    val groups = assets.mapNotNull { asset ->
        val match = SPLIT_PART_REGEX.matchEntire(asset.name) ?: return@mapNotNull null
        Triple(match.groupValues[1], match.groupValues[2].toInt(), asset.downloadUrl)
    }.groupBy({ it.first }, { it.second to it.third })

    // 优先取前缀含 "setup" 的组，其次取分卷数最多的组
    val best = groups.entries.maxWithOrNull(
        compareBy(
            { if (it.key.contains("setup", ignoreCase = true)) 1 else 0 },
            { it.value.size },
        )
    )

    if (best != null && best.value.size >= 2) {
        val sorted = best.value.sortedBy { it.first }
        val contiguous = sorted.mapIndexed { index, part -> part.first } == (1..sorted.size).toList()
        if (contiguous) {
            val sha256Url = assets.firstOrNull {
                it.name.equals("${best.key}.zip.sha256", ignoreCase = true)
            }?.downloadUrl
            return UpdateDownloadPlan(sorted.map { it.second }, sha256Url)
        }
    }

    // 回退：旧版单 exe 安装包
    val exeAsset = assets.firstOrNull { it.name.endsWith(".exe") } ?: return null
    return UpdateDownloadPlan(listOf(exeAsset.downloadUrl))
}

/**
 * 从 release 资产中解析 Android 更新下载计划（单个 `.apk` 资产）。
 */
fun LatestVersionProfile.resolveAndroidUpdatePlan(): UpdateDownloadPlan? {
    val apkAsset = assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
    return UpdateDownloadPlan(listOf(apkAsset.downloadUrl))
}
