/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.mod.ProtectedRwmodDetector
import io.github.rwpp.internalModDir
import io.github.rwpp.modDir
import io.github.rwpp.widget.MemoryUsagePanel
import io.github.rwpp.widget.ProtectedModLoadNotice
import io.github.rwpp.widget.clearProtectedModLoadHint
import io.github.rwpp.widget.loadingProtectedModNames
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.defaultRWPPColorScheme
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

/** 首帧不依赖国际化、扩展、主题字体或游戏引擎；它们尚在准备时也能绘制和响应。 */
@Composable
internal fun DesktopStartupView(
    message: String,
    prepared: Boolean = false,
    error: Throwable? = null,
    onFrameRendered: () -> Unit = {},
    titleLoader: () -> Painter? = ::loadDesktopStartupTitle,
    onExit: () -> Unit = {},
) {
    // painterResource 在 JVM 首次解码会同步等待，标题不能挡住转圈的首帧。
    val title by produceState<Painter?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { titleLoader() }.getOrNull()
        }
    }
    if (prepared) {
        LaunchedEffect(Unit) {
            loadingProtectedModNames = withContext(Dispatchers.IO) {
                runCatching {
                    ProtectedRwmodDetector.displayNamesOfEnabled(emptyList(), null, listOf(File(modDir), File(internalModDir)))
                }.getOrDefault(emptyList())
            }
        }
        DisposableEffect(Unit) { onDispose { clearProtectedModLoadHint() } }
    }
    MaterialTheme(colorScheme = defaultRWPPColorScheme) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black, Color(2, 48, 32), Color.Black))),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(0.8f).verticalScroll(rememberScrollState()).padding(24.dp)
                    // 放在滚动内容的绘制层内；父层可复用记录，不能据父 draw 次数统计动画帧。
                    .drawWithContent { drawContent(); onFrameRendered() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
                    title?.let { Image(it, null, Modifier.fillMaxWidth(0.7f), contentScale = ContentScale.Fit) }
                }
                if (error == null) Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    LineSpinFadeLoaderIndicator(radius = 22f, penThickness = 8f)
                }
                Text(message, color = Color.White)
                if (error == null) UiLinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    Text(error.toString(), color = Color.White)
                    TextButton(onClick = onExit) { Text("退出 / Exit") }
                }
                if (prepared) {
                    MemoryUsagePanel(compact = true, darkBackground = true)
                    ProtectedModLoadNotice(loadingText = message, darkSplash = true)
                }
            }
        }
    }
}

private fun loadDesktopStartupTitle(): Painter? = ClassLoader.getSystemResourceAsStream(
    "composeResources/io.github.rwpp.rwpp_core.generated.resources/drawable/title.png"
)?.use { ImageIO.read(it)?.toPainter() }
