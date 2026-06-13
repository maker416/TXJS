/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.os.Build
import io.github.rwpp.app.PermissionHelper
import io.github.rwpp.appKoin
import io.github.rwpp.coreVersion
import io.github.rwpp.generatedLibDir
import io.github.rwpp.packageName
import io.github.rwpp.projectVersion
import io.github.rwpp.ui.injectLogText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOG_TAIL_LINES = 160

/**
 * 生成可供用户一键复制反馈的完整诊断报告。
 *
 * 汇总版本、设备、注入产物状态、错误堆栈与最近日志，方便在初始化出现问题时定位原因。
 * 所有取值都包裹在 [runCatching] 中，确保即使部分信息缺失也能产出尽量完整的报告。
 *
 * @param stage 出错阶段标识，例如 `dexLoad`、`inject`、`engineInit`。
 * @param error 可选的异常，会附带完整堆栈。
 */
fun buildDiagnosticsReport(stage: String, error: Throwable? = null): String = buildString {
    appendLine("==== RWPP 初始化诊断报告 / Diagnostics ====")
    appendLine("stage   : $stage")
    appendLine("time    : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")

    appendLine()
    appendLine("-- version --")
    appendLine("app     : $projectVersion")
    appendLine("core    : $coreVersion")
    appendLine("package : $packageName")

    appendLine()
    appendLine("-- device --")
    appendLine("brand   : ${Build.MANUFACTURER} / ${Build.BRAND}")
    appendLine("model   : ${Build.MODEL} (${Build.DEVICE})")
    appendLine("android : SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
    appendLine("abis    : ${runCatching { Build.SUPPORTED_ABIS.joinToString() }.getOrDefault("?")}")

    appendLine()
    appendLine("-- state --")
    appendLine("hasManageFilePermission: ${runCatching { appKoin.get<PermissionHelper>().hasManageFilePermission() }.getOrNull()}")
    appendLine("requireReloadingLib    : $requireReloadingLib")
    appendLine("gameLoaded             : $gameLoaded")
    appendLine("rebuildAttempts        : ${runCatching { getRebuildAttemptCount() }.getOrNull()}")

    val libDir = runCatching { generatedLibDir }.getOrNull()
    appendLine("generatedLibDir        : $libDir")
    val generatedLib = libDir?.let { File(it, "android-game-lib.jar") }
    appendLine("generatedLib           : exists=${generatedLib?.exists()}, size=${generatedLib?.length()}")

    val dex = runCatching { File(dexFolder, "classes.dex") }.getOrNull()
    appendLine("dexFolder              : ${runCatching { dexFolder.absolutePath }.getOrNull()}")
    appendLine("classes.dex            : exists=${dex?.exists()}, size=${dex?.length()}")

    if (error != null) {
        appendLine()
        appendLine("-- error --")
        appendLine(error.stackTraceToString().trim())
    }

    val injectLog = runCatching { injectLogText.value }.getOrNull()
    if (!injectLog.isNullOrBlank()) {
        appendLine()
        appendLine("-- inject log --")
        appendLine(injectLog.trim().tailLines(MAX_LOG_TAIL_LINES))
    }

    val logFile = File("/storage/emulated/0/rustedWarfare/rwpp-log.txt")
    val fileLog = runCatching { if (logFile.exists()) logFile.readText() else null }.getOrNull()
    if (!fileLog.isNullOrBlank()) {
        appendLine()
        appendLine("-- rwpp-log.txt (tail) --")
        appendLine(fileLog.trim().tailLines(MAX_LOG_TAIL_LINES))
    }
}

private fun String.tailLines(maxLines: Int): String {
    val lines = trimEnd().lines()
    return if (lines.size <= maxLines) this
    else lines.subList(lines.size - maxLines, lines.size).joinToString("\n")
}
