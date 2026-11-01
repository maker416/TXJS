/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

/** 沿用主题背景的色相，认证界面使用更安静、对比更清晰的底色。 */
@Composable
internal fun accountAccessBackground(): Color {
    val background = MaterialTheme.colorScheme.background
    return if (background.luminance() < 0.5f) lerp(background, Color.Black, 0.55f)
    else lerp(background, Color.White, 0.7f)
}

/** 未登录入口是一张完整的账号卡片；标题、关闭和访客出口均在同一层级。 */
@Composable
internal fun AccountWelcomeView(onLogin: () -> Unit, onRegister: () -> Unit, onExit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val base = accountAccessBackground()
    val card = colors.onSurface.copy(alpha = 0.035f).compositeOver(base)
    val muted = colors.onSurface.copy(alpha = 0.58f)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(base.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 600.dp
        Surface(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)
                .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(120.dp)),
            shape = RoundedCornerShape(24.dp),
            color = card,
            border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.09f)),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = RoundedCornerShape(10.dp), color = colors.primary.copy(alpha = 0.09f)) {
                        Image(painterResource(Res.drawable.logo), null, Modifier.padding(6.dp).size(24.dp))
                    }
                    Text(readI18n("account.welcome.brand", I18nType.RWPP),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    Spacer(Modifier.weight(1f))
                    IconButton(onExit, Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, readI18n("common.cancel", I18nType.RWPP),
                            tint = muted, modifier = Modifier.size(20.dp))
                    }
                }
                val hero: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(readI18n("account.welcome.eyebrow", I18nType.RWPP),
                            style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Text(readI18n("account.welcome.headline", I18nType.RWPP),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = colors.onSurface)
                        Text(readI18n("account.welcome.caption", I18nType.RWPP),
                            style = MaterialTheme.typography.bodySmall, color = muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Icons.Default.Person to "account.welcome.friends",
                                Icons.Default.Email to "account.welcome.invites",
                                Icons.Default.Star to "account.welcome.mods",
                            ).forEach { (icon, key) ->
                                Row(
                                    Modifier.background(colors.primary.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(icon, null, Modifier.size(12.dp), tint = colors.primary)
                                    Text(readI18n(key, I18nType.RWPP), style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurface.copy(alpha = 0.75f))
                                }
                            }
                        }
                    }
                }
                val actions: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(readI18n("account.welcome.actionTitle", I18nType.RWPP),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface)
                        Text(readI18n("account.welcome.actionHint", I18nType.RWPP),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                            modifier = Modifier.padding(bottom = 8.dp))
                        Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Text(readI18n("account.login", I18nType.RWPP), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                        OutlinedButton(onRegister, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.18f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                            Text(readI18n("account.welcome.create", I18nType.RWPP), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    if (wide) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1.1f)) { hero() }
                            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.onSurface.copy(alpha = 0.08f)))
                            Box(Modifier.weight(1f)) { actions() }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                            hero()
                            actions()
                        }
                    }
                }
                HorizontalDivider(color = colors.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(horizontal = 24.dp))
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 6.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(readI18n("account.welcome.optional", I18nType.RWPP),
                        style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                    TextButton(onExit) {
                        Text(readI18n("account.welcome.guest", I18nType.RWPP), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface.copy(alpha = 0.8f))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = muted)
                    }
                }
            }
        }
    }
}
