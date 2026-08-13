/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.mod.ProtectedRwmodDetector
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.internalModDir
import io.github.rwpp.modDir
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.title
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import java.io.File

@Composable
fun MenuLoadingView(
    message: String,
) {
    LaunchedEffect(Unit) {
        val names = withContext(Dispatchers.IO) {
            runCatching {
                ProtectedRwmodDetector.displayNamesOfEnabled(
                    emptyList(),
                    null,
                    listOf(File(modDir), File(internalModDir)),
                )
            }.getOrDefault(emptyList())
        }
        loadingProtectedModNames = names
    }

    DisposableEffect(Unit) {
        onDispose { clearProtectedModLoadHint() }
    }

    RWPPTheme(true) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val compact = maxHeight < 520.dp
            val logoMaxWidth = when {
                maxHeight < 650.dp -> maxWidth * 0.55f
                maxWidth < 700.dp -> maxWidth * 0.48f
                else -> maxWidth * 0.38f
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(if (compact) 0.10f else 0.16f))

                Image(
                    painter = painterResource(Res.drawable.title),
                    contentDescription = "Menu",
                    modifier = Modifier
                        .widthIn(max = logoMaxWidth)
                        .fillMaxWidth(if (compact) 0.36f else 0.42f)
                        .padding(10.dp),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.weight(if (compact) 0.06f else 0.10f))

                LineSpinFadeLoaderIndicator(
                    radius = if (compact) 18f else 22f,
                    penThickness = if (compact) 6f else 8f,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(if (compact) 12.dp else 18.dp))

                SplashLoadingStatus(
                    message = message,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(0.86f)
                )

                Spacer(modifier = Modifier.weight(if (compact) 0.16f else 0.22f))
            }
        }
    }
}

@Composable
private fun SplashLoadingStatus(
    message: String,
    modifier: Modifier = Modifier,
) {
    val structuredMessage = remember(message) { message.toStructuredLoadingMessage() }
    val fallback = message.isBlank() ||
        message.equals("loading", ignoreCase = true) ||
        message.equals("loading...", ignoreCase = true)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (structuredMessage != null) {
                StructuredLoadingContent(
                    message = structuredMessage,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    if (fallback) readI18n("mod.starting") else message,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )
            }

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.12f)
            )

            ProtectedModLoadNotice(
                loadingText = message,
                darkSplash = true,
            )
        }
    }
}
