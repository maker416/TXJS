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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.logo
import io.github.rwpp.widget.LocalAccountAuthDialogEscape
import io.github.rwpp.widget.UiCircularProgressIndicator
import io.github.rwpp.widget.desktopEscapeToBackDispatcher
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

/** 认证专用布局：按真实视口切换横屏双栏，输入区域可在键盘/大字体下滚动。 */
@Composable
internal fun AccountAccessCard(
    title: String,
    subtitle: String,
    busy: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val desktop = koinInject<AppContext>().isDesktop()
    val viewport = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidth = with(density) { viewport.width.toDp() }
    val windowHeight = with(density) { viewport.height.toDp() }
    // 只按宽度切栏：Android adjustResize 缩短高度时不能换掉输入框所在的布局树。
    val wide = windowWidth >= 600.dp
    val colors = MaterialTheme.colorScheme
    val base = accountAccessBackground()
    CompositionLocalProvider(LocalAccountAuthDialogEscape provides desktop) {
        Surface(
            modifier = Modifier
                .widthIn(max = if (wide) 780.dp else 440.dp)
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = (windowHeight - 24.dp).coerceAtLeast(120.dp)),
            shape = RoundedCornerShape(24.dp),
            color = base,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
        ) {
            Box {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    if (wide) {
                        Column(
                            Modifier.width(if (windowWidth < 760.dp) 200.dp else 240.dp).fillMaxHeight()
                                .background(Brush.verticalGradient(listOf(
                                    colors.primary.copy(alpha = 0.07f).compositeOver(base),
                                    colors.onSurface.copy(alpha = 0.025f).compositeOver(base),
                                )))
                                .padding(24.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Image(painterResource(Res.drawable.logo), null, Modifier.size(48.dp))
                                Text(readI18n("account.access.brand", I18nType.RWPP), style = MaterialTheme.typography.labelLarge,
                                    color = colors.primary)
                                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                    color = colors.onSurface)
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            Text(readI18n("account.access.guest", I18nType.RWPP), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
                        }
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!wide) {
                            Column(Modifier.padding(end = 40.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        content()
                    }
                }
                IconButton(onClick = onClose, enabled = !busy,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp)) {
                    Icon(Icons.Default.Close, readI18n("common.cancel", I18nType.RWPP), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** 保留光标、选择与 IME composition；不在输入框父级抢焦点。 */
@Composable
internal fun AccountAccessField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val desktop = LocalAccountAuthDialogEscape.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var reveal by remember { mutableStateOf(false) }
    SideEffect { if (field.text != value) field = TextFieldValue(value, TextRange(value.length)) }
    val secret = visualTransformation is PasswordVisualTransformation
    OutlinedTextField(
        value = field,
        onValueChange = { field = it; onValueChange(it.text) },
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f).compositeOver(accountAccessBackground()),
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f).compositeOver(accountAccessBackground()),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.heightIn(min = 52.dp).semantics { contentDescription = label }
            .then(if (desktop) Modifier.desktopEscapeToBackDispatcher(scope) else Modifier),
        leadingIcon = leadingIcon,
        trailingIcon = if (secret) {{
            TextButton(onClick = { reveal = !reveal }, enabled = enabled) {
                Text(readI18n(if (reveal) "account.access.hide" else "account.access.show", I18nType.RWPP),
                    style = MaterialTheme.typography.labelMedium)
            }
        }} else null,
        visualTransformation = if (reveal) VisualTransformation.None else visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
    )
}

@Composable
internal fun AccountAccessTabs(useCode: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false to "account.access.passwordTab", true to "account.access.codeTab").forEach { (code, key) ->
            Surface(
                onClick = { onSelect(code) }, enabled = enabled,
                modifier = Modifier.weight(1f).semantics { selected = useCode == code },
                shape = RoundedCornerShape(10.dp),
                color = if (useCode == code) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    .compositeOver(accountAccessBackground()) else accountAccessBackground(),
            ) {
                Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(readI18n(key, I18nType.RWPP), style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (useCode == code) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (useCode == code) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun AccountAccessSteps(step: Int, labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(end = 40.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}  $label", style = MaterialTheme.typography.labelSmall,
                    color = if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().height(3.dp).background(
                    if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
internal fun AccountAccessSubmit(label: String, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (busy) UiCircularProgressIndicator(Modifier.size(24.dp))
        else AccountPrimaryButton(label, onClick, Modifier.fillMaxWidth())
    }
}
