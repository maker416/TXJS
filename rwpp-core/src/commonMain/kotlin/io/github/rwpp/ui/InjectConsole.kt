/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.inject.BuildLogger
import io.github.rwpp.logger
import io.github.rwpp.widget.BorderCard

/** 注入日志最大保留行数，超出后丢弃最旧行，防止无界增长。 */
private const val MaxInjectLogLines = 500

/**
 * 注入构建日志：有界 SnapshotStateList，追加为 O(1) 且逐行渲染；
 * 替代原先 String/AnnotatedString 的 `+=` 全量拼接（O(n²)，构建后期严重掉帧）。
 */
val injectLogLines = mutableStateListOf<AnnotatedString>()
private val injectLogLock = Any()

fun clearInjectLog() {
    synchronized(injectLogLock) {
        injectLogLines.clear()
    }
}

/** 纯文本导出（复制到剪贴板 / 诊断报告用）。 */
fun injectLogPlainText(): String = synchronized(injectLogLock) {
    injectLogLines.joinToString("\n") { it.text }
}

private fun appendInjectLog(
    level: String,
    message: String,
    color: Color,
) {
    val line = buildAnnotatedString {
        withStyle(style = SpanStyle(color = color)) {
            append("[$level] $message")
        }
    }
    synchronized(injectLogLock) {
        if (injectLogLines.size >= MaxInjectLogLines) {
            injectLogLines.removeRange(0, injectLogLines.size - MaxInjectLogLines + 1)
        }
        injectLogLines.add(line)
    }
}

@Deprecated("Use InjectSetupScreen on Android; kept for desktop inject rebuild UI")
@Composable
fun InjectConsole() {
    BorderCard(
        modifier = Modifier
            .fillMaxSize(.7f),
    ) {
        Text(
            "Inject Console",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 5.dp)
                .align(Alignment.CenterHorizontally)
        )

        HorizontalDivider(
            thickness = 3.dp,
            modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
            color = MaterialTheme.colorScheme.primary
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(5.dp),
        ) {
            items(injectLogLines.size) { index ->
                Text(
                    injectLogLines[index],
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

val defaultBuildLogger: BuildLogger = object : BuildLogger {
    override fun logging(message: String) {
        logger.trace(message)
        appendInjectLog("L", message, Color.Gray)
    }

    override fun info(message: String) {
        logger.info(message)
        appendInjectLog("I", message, Color.Green)
    }

    override fun warn(message: String) {
        logger.warn(message)
        appendInjectLog("W", message, Color.Yellow)
    }

    override fun error(message: String) {
        logger.error(message)
        appendInjectLog("E", message, Color.Red)
    }

    override fun exception(e: Throwable) {
        logger.error(e.message, e)
        appendInjectLog("E", "${e.message}\n${e.stackTraceToString()}", Color.Red)
    }
}