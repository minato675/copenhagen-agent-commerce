package com.copenhagen.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.data.Card
import com.copenhagen.wallet.data.cardStatusLabel

@Composable fun CardTile(card: Card, modifier: Modifier = Modifier) {
    val appearance = remember(card.id) { CardAppearance.forId(card.id) }
    val brand = card.brand?.takeIf { it.isNotBlank() } ?: "결제 카드"
    val number = card.last4?.takeIf { it.isNotBlank() }?.let { "••••  $it" } ?: "카드 번호 확인 필요"
    val metadata = listOfNotNull(
        cardStatusLabel(card.status).takeUnless { card.active },
        if (card.expiryMonth != null && card.expiryYear != null)
            "${card.expiryMonth.toString().padStart(2, '0')} / ${card.expiryYear}" else null
    ).joinToString(" · ")
    val typography = MaterialTheme.typography
    val brandStyle = typography.titleMedium.copy(lineBreak = LineBreak.Heading)
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = PaymentCardMaxWidth).fillMaxWidth()) {
            val padding = if (maxWidth < 340.dp) 20.dp else 24.dp
            val innerWidth = with(density) { (maxWidth - padding * 2).roundToPx() }.coerceAtLeast(1)
            val textConstraints = Constraints(maxWidth = innerWidth)
            val badge = measure.measure("기본", typography.labelMedium)
            val badgeWidth = with(density) { 52.dp.roundToPx() } + badge.size.width
            // Reserve the same header space for every default state so promotion never
            // changes the card's layout halfway through its existing reorder motion.
            val header = measure.measure(brand, brandStyle,
                constraints = Constraints(maxWidth = (innerWidth - badgeWidth).coerceAtLeast(1)))
            val footer = measure.measure(number, typography.headlineSmall, constraints = textConstraints).size.height +
                if (metadata.isNotEmpty()) measure.measure(metadata, typography.bodyMedium, constraints = textConstraints).size.height + with(density) { 8.dp.roundToPx() } else 0
            val needed = maxOf(header.size.height, badge.size.height + with(density) { 12.dp.roundToPx() }) + footer + with(density) { 16.dp.roundToPx() }
            val available = with(density) { (maxWidth / PaymentCardRatio - padding * 2).roundToPx() }
            val detailsOutside = needed > available
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier.fillMaxWidth().aspectRatio(PaymentCardRatio).sharedObject("card-${card.id}")
                    .clip(RoundedCornerShape(24.dp)).background(Carbon.Ink)) {
                    CardArtwork(appearance, Modifier.matchParentSize())
                    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.SpaceBetween) {
                        if (detailsOutside) Glyph(WalletIcon.Card, tint = Color.White)
                        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(brand, Modifier.weight(1f), color = Color.White, style = brandStyle)
                            DefaultBadge(card.isDefault, "기본 카드", onDark = true)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(number, color = Color.White, style = typography.headlineSmall)
                            if (!detailsOutside && metadata.isNotEmpty()) Text(metadata, color = Color.White, style = typography.bodyMedium)
                        }
                    }
                }
                // Enlarge real text without stretching the physical card, clipping names,
                // or shrinking the user's font setting. The same labels simply reflow below.
                if (detailsOutside) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(brand, style = brandStyle, color = Carbon.Ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultBadge(card.isDefault, "기본 카드")
                        if (metadata.isNotEmpty()) Text(metadata, style = typography.bodyMedium, color = Carbon.Muted)
                    }
                }
            }
        }
    }
}
