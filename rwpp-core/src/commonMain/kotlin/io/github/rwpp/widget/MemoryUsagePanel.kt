/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.readMemoryUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 模组页与加载视图共用采样器，避免弹窗叠在模组页时重复查询系统。
 * 最后一个视图退出组合后立即停止采样，并清除旧值；下次显示时重新采样。
 */
private val memoryUsageSamples = flow<MemoryUsage?> {
    while (currentCoroutineContext().isActive) {
        val sample = try {
            readMemoryUsage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        emit(sample)
        delay(1_000L)
    }
}.stateIn(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
    initialValue = null,
)

/**
 * 展示当前 Java 堆配额与进程实际驻留内存。采样状态只在本组件内读取，
 * 每秒刷新不会使整个模组列表或加载视图重组，也不触碰引擎线程或主动触发 GC。
 */
@Composable
fun MemoryUsagePanel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    darkBackground: Boolean = false,
) {
    var usage by remember { mutableStateOf<MemoryUsage?>(null) }
    LaunchedEffect(Unit) {
        memoryUsageSamples.collect { usage = it }
    }

    MemoryUsageContent(usage, modifier, compact, darkBackground)
}

/** 不启动采样的展示层，便于预览不同内存压力与窄屏布局。 */
@Composable
internal fun MemoryUsageContent(
    usage: MemoryUsage?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    darkBackground: Boolean = false,
) {
    val sample = usage
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (darkBackground) Color.White else scheme.onSurface
    val secondaryColor = if (darkBackground) Color.White.copy(alpha = 0.78f) else scheme.onSurfaceVariant
    val fraction = sample?.heapUsageFraction ?: 0f
    val critical = sample != null && fraction >= 0.90f
    val elevated = sample != null && fraction >= 0.80f
    val accentColor = when {
        critical && darkBackground -> Color(0xFFFFA7A0)
        critical -> scheme.error
        elevated && darkBackground -> Color(0xFFFFD18A)
        elevated -> scheme.tertiary
        darkBackground -> Color(0xFFA7D989)
        else -> scheme.primary
    }
    val numberStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (darkBackground) Color.Black.copy(alpha = 0.24f)
            else scheme.surfaceContainer.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = if (elevated) 0.65f else 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 7.dp else 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
        ) {
            if (!compact) {
                Text(
                    text = readI18n("memory.title"),
                    modifier = Modifier.fillMaxWidth(),
                    color = contentColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (sample == null) {
                Text(
                    text = readI18n("memory.sampling"),
                    modifier = Modifier.fillMaxWidth(),
                    color = secondaryColor,
                    style = numberStyle,
                )
            } else {
                Text(
                    text = readI18n(
                        "memory.javaHeap", I18nType.RWPP,
                        formatMemoryBytes(sample.heapUsedBytes),
                        formatMemoryBytes(sample.heapMaxBytes),
                        (fraction * 100).roundToInt().toString(),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    color = if (elevated) accentColor else contentColor,
                    style = numberStyle,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accentColor,
                    trackColor = contentColor.copy(alpha = 0.12f),
                )

                val resident = sample.processResidentBytes?.let(::formatMemoryBytes)
                    ?: readI18n("memory.unavailable")
                val physicalUsage = readI18n("memory.processResident", I18nType.RWPP, resident)
                val nativeUsage = sample.nativeHeapBytes?.let {
                    readI18n("memory.nativeHeap", I18nType.RWPP, formatMemoryBytes(it))
                }
                // 不限制行数：窄屏允许自然换行，不能截断内存数值或上限。
                Text(
                    text = if (compact && nativeUsage != null) "$physicalUsage · $nativeUsage" else physicalUsage,
                    modifier = Modifier.fillMaxWidth(),
                    color = secondaryColor,
                    style = numberStyle,
                )
                if (!compact && nativeUsage != null) {
                    Text(
                        text = nativeUsage,
                        modifier = Modifier.fillMaxWidth(),
                        color = secondaryColor,
                        style = numberStyle,
                    )
                }

                if (!compact) {
                    Text(
                        text = readI18n(
                            "memory.headroom", I18nType.RWPP,
                            formatMemoryBytes(sample.heapHeadroomBytes),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        color = secondaryColor,
                        style = numberStyle,
                    )
                    Text(
                        text = readI18n("memory.explanation"),
                        modifier = Modifier.fillMaxWidth(),
                        color = secondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (elevated) {
                    Text(
                        text = readI18n(if (critical) "memory.critical" else "memory.elevated"),
                        modifier = Modifier.fillMaxWidth(),
                        color = accentColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun formatMemoryBytes(bytes: Long): String {
    val useGiB = bytes >= 1_073_741_824L
    val divisor = if (useGiB) 1_073_741_824.0 else 1_048_576.0
    val tenths = (bytes.coerceAtLeast(0L) / divisor * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} ${if (useGiB) "GiB" else "MiB"}"
}
