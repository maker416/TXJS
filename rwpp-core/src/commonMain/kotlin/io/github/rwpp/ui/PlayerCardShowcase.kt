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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.game.Player
import io.github.rwpp.game.teamAlias
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.player_card_aurora
import io.github.rwpp.rwpp_core.generated.resources.player_card_outpost
import io.github.rwpp.rwpp_core.generated.resources.player_frame_prism
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.PersonalizedAppearanceController
import io.github.rwpp.theme.PersonalizedVariantSpec
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val Brass = Color(0xFFEAC58C)
private val Ink = Color(0xFF122329)
private val Silver = Color(0xFFB8C7C8)

private data class CardCosmetic(
    val label: String,
    val art: DrawableResource? = null,
    val variant: PersonalizedVariantSpec? = null,
)

@Composable
internal fun PlayerCardShowcase(
    player: Player,
    account: PublicUser?,
    avatar: AccountAvatar?,
    online: Boolean?,
    ownCard: Boolean,
    dismiss: () -> Unit,
) {
    val canPreview = ownCard && account != null
    val equippedCover = PersonalizedAppearanceController.variantFor(player, "room.player.background")
    val equippedFrame = PersonalizedAppearanceController.variantFor(player, "room.player.frame")
    val standardCover = CardCosmetic(readI18n("playerCard.outpost"), Res.drawable.player_card_outpost)
    val standardFrame = CardCosmetic(readI18n("playerCard.alloy"))
    var preview by remember { mutableStateOf(false) }
    var coverChoice by remember { mutableStateOf<CardCosmetic?>(null) }
    var frameChoice by remember { mutableStateOf<CardCosmetic?>(null) }
    val currentCover = equippedCover?.let { CardCosmetic("", variant = it) } ?: standardCover
    val currentFrame = equippedFrame?.let { CardCosmetic("", variant = it) } ?: standardFrame
    val cover = if (preview) coverChoice ?: currentCover else currentCover
    val frame = if (preview) frameChoice ?: currentFrame else currentFrame

    BoxWithConstraints(Modifier.fillMaxWidth().background(Ink)) {
        val compact = maxWidth < 420.dp
        val inset = if (compact) 16.dp else 24.dp
        Image(
            painterResource(cover.art ?: Res.drawable.player_card_outpost), null,
            Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
        )
        cover.variant?.let { RoomThemeImage(it.image, Modifier.matchParentSize()) }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Ink.copy(alpha = .72f), .58f to Ink.copy(alpha = .52f), 1f to Ink.copy(alpha = .04f),
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .18f), .45f to Color.Transparent, 1f to Ink.copy(alpha = .86f),
        )))
        Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 168.dp else 198.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().padding(start = inset, end = 6.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(3.dp, 13.dp).background(Brass))
                val title = when {
                    preview -> "playerCard.previewTitle"
                    ownCard -> "playerCard.myCard"
                    else -> "playerCard.title"
                }
                Text(readI18n(title), Modifier.weight(1f).padding(start = 8.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
                if (canPreview) {
                    OutlinedButton(
                        onClick = {
                            preview = !preview
                            coverChoice = null
                            frameChoice = null
                        },
                        border = BorderStroke(1.dp, Brass.copy(alpha = .65f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Ink.copy(alpha = .90f), contentColor = Brass),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(readI18n(if (preview) "playerCard.endPreview" else "playerCard.preview"),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = dismiss) {
                    Box(Modifier.size(30.dp).background(Ink.copy(alpha = .78f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, readI18n("common.close"), Modifier.size(18.dp), tint = Color.White)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 8.dp, bottom = inset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
                CardPortrait(player.name, avatar, frame, compact)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (player.isRoomHost || online != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (player.isRoomHost) CardBadge(readI18n("playerCard.hostBadge"), Brass)
                            if (online != null) CardBadge(readI18n(if (online) "friends.online" else "friends.offline"),
                                if (online) Color(0xFFAFDFC6) else Silver)
                        }
                    }
                    Text(player.name, color = Color.White, fontWeight = FontWeight.Bold,
                        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                        fontSize = if (compact) 24.sp else 32.sp, lineHeight = if (compact) 28.sp else 36.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (account != null) {
                        val nickname = account.nickname.takeIf { it.isNotBlank() && it != player.name && it != account.username }
                        Text(listOfNotNull(nickname, "@${account.username}").joinToString("  ·  "),
                            color = Color(0xFFDEE7E6), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CardStat(readI18n("multiplayer.room.colTeam"), player.teamAlias())
        VerticalDivider(Modifier.height(26.dp), color = Silver.copy(alpha = .22f))
        CardStat(readI18n("multiplayer.room.colSpawn"), if (player.isSpectator) "S" else (player.spawnPoint + 1).toString())
        VerticalDivider(Modifier.height(26.dp), color = Silver.copy(alpha = .22f))
        val ping = player.ping.removeSuffix(" (HOST)").trim()
        CardStat(readI18n("multiplayer.room.colPing"), if (ping.toIntOrNull() != null) "$ping ms" else ping)
    }
    if (canPreview && preview) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF1A2D33)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val theme = ArtThemeController.activeTheme
            val slots = remember(theme) {
                theme?.takeIf { it.personalizedCatalog != null }?.spec?.personalization?.sanitized()?.slots.orEmpty()
            }
            fun options(slot: String, standard: CardCosmetic, sample: CardCosmetic): List<CardCosmetic> {
                val packChoices = slots[slot]?.variants.orEmpty().map { (id, variant) ->
                    CardCosmetic(variant.displayName.ifBlank { id }, variant = variant)
                }
                return listOf(standard) + packChoices.ifEmpty { listOf(sample) }
            }
            CosmeticChoices(readI18n("playerCard.cover"), options("room.player.background", standardCover,
                CardCosmetic(readI18n("playerCard.aurora"), Res.drawable.player_card_aurora)), cover) { coverChoice = it }
            CosmeticChoices(readI18n("playerCard.frame"), options("room.player.frame", standardFrame,
                CardCosmetic(readI18n("playerCard.prism"), Res.drawable.player_frame_prism)), frame) { frameChoice = it }
        }
    }
}

@Composable
private fun CardBadge(label: String, color: Color) {
    Text(label, Modifier.background(Ink.copy(alpha = .90f), RoundedCornerShape(4.dp))
        .border(1.dp, color.copy(alpha = .55f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
}

@Composable
private fun RowScope.CardStat(label: String, value: String) {
    Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Silver, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardPortrait(name: String, avatar: AccountAvatar?, frame: CardCosmetic, compact: Boolean) {
    val variant = frame.variant
    val tint = roomThemeColor(variant?.borderColor.orEmpty(), Brass)
    val size = if (compact) 80.dp else 100.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(.87f).background(Ink, CircleShape)
            .roomThemeBorder(variant?.borderWidth ?: 2, tint, CircleShape)
            .padding(5.dp).clip(CircleShape).background(Color(0xFF344F54)),
            contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.uppercase().orEmpty(), color = Color.White,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (avatar != null) {
                key(avatar) { AsyncImage(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        if (variant == null || variant.borderWidth > 0) {
            Spacer(Modifier.fillMaxSize(.95f).border(1.dp, tint.copy(alpha = .6f), CircleShape))
        }
        frame.art?.let { Image(painterResource(it), null, Modifier.matchParentSize()) }
        if (variant != null) RoomThemeImage(variant.avatarFrameImage, Modifier.matchParentSize(), scale = ContentScale.Fit)
    }
}

@Composable
private fun CosmeticChoices(title: String, choices: List<CardCosmetic>, current: CardCosmetic, choose: (CardCosmetic) -> Unit) {
    Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
        color = Silver)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices) { item ->
            FilterChip(
                selected = item.art == current.art && item.variant == current.variant,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Ink, labelColor = Color.White, selectedContainerColor = Brass, selectedLabelColor = Ink,
                ),
                onClick = { choose(item) },
                label = { Text(item.label, maxLines = 1) },
                leadingIcon = {
                    val thumb = Modifier.size(30.dp, 22.dp).clip(RoundedCornerShape(3.dp))
                    val asset = item.variant?.let { it.avatarFrameImage.ifEmpty { it.image } }.orEmpty()
                    when {
                        item.art != null -> Image(painterResource(item.art), null, thumb,
                            contentScale = if (item.art == Res.drawable.player_frame_prism) ContentScale.Fit else ContentScale.Crop)
                        asset.isNotEmpty() -> RoomThemeImage(asset, thumb, scale = ContentScale.Fit)
                        else -> Spacer(Modifier.size(18.dp).border(2.dp,
                            roomThemeColor(item.variant?.borderColor.orEmpty(), Brass), CircleShape))
                    }
                },
            )
        }
    }
}
