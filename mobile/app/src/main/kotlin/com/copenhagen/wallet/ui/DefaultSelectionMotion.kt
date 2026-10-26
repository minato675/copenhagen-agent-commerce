@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.copenhagen.wallet.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Keep each server-confirmed object intact as the default moves to the front. */
@Composable internal fun <T> DefaultSelectionList(
    items: List<T>,
    identity: (T) -> String,
    isDefault: (T) -> Boolean,
    highlight: Color = Carbon.Blue,
    content: @Composable (T, Modifier) -> Unit
) {
    val reduced = LocalReducedMotion.current
    LookaheadScope {
        Column(Modifier.fillMaxWidth().semantics { collectionInfo = CollectionInfo(items.size, 1) },
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items.sortedByDescending(isDefault).forEachIndexed { index, item -> key(identity(item)) {
                val selected = isDefault(item)
                val emphasis = rememberDefaultEmphasis(selected)
                // The promoted surface passes above displaced siblings. Scroll remains direct:
                // the lookahead scope lives inside Page's scroll content, not above the viewport.
                Box(Modifier.fillMaxWidth().zIndex(if (selected) 1f else 0f).semantics {
                    isTraversalGroup = true
                    collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                }
                    .then(if (reduced) Modifier else Modifier.animateBounds(this@LookaheadScope,
                        boundsTransform = { _, _ -> Motion.surface(Rect(1f, 1f, 1f, 1f)) }))) {
                    content(item, Modifier.graphicsLayer {
                        shadowElevation = 12.dp.toPx() * emphasis.value
                        shape = RoundedCornerShape(24.dp)
                        clip = false
                    }.drawWithContent {
                        drawContent()
                        if (emphasis.value > 0f) {
                            val inset = 1.dp.toPx()
                            drawRoundRect(highlight.copy(alpha = .8f * emphasis.value),
                                topLeft = Offset(inset, inset),
                                size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                                cornerRadius = CornerRadius(23.dp.toPx()), style = Stroke(2.dp.toPx()))
                        }
                    })
                }
            } }
        }
    }
}

@Composable private fun rememberDefaultEmphasis(selected: Boolean): State<Float> {
    val reduced = LocalReducedMotion.current
    var previous by remember { mutableStateOf(selected) }
    val emphasis = remember { Animatable(0f) }
    LaunchedEffect(selected, reduced) {
        val promoted = selected && !previous
        previous = selected
        if (promoted && !reduced) {
            emphasis.snapTo(1f)
            emphasis.animateTo(0f, tween(Motion.Signature, easing = LinearOutSlowInEasing))
        } else emphasis.snapTo(0f)
    }
    return emphasis.asState()
}

@Composable internal fun DefaultBadge(selected: Boolean, description: String, onDark: Boolean = false) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(selected,
        modifier = Modifier.clearAndSetSemantics {
            // Remove the old badge's semantics immediately, even while its exit finishes.
            if (selected) { contentDescription = description; liveRegion = LiveRegionMode.Polite }
        },
        enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.Content)) + expandHorizontally(Motion.control(), expandFrom = Alignment.End),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(100)) + shrinkHorizontally(Motion.control(), shrinkTowards = Alignment.End)) {
        Surface(color = if (onDark) Color.Black.copy(alpha = .2f) else Carbon.Layer, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = if (onDark) Color.White else Carbon.Blue
                Glyph(WalletIcon.Check, modifier = Modifier.size(16.dp), tint = tint)
                Text("기본", color = tint, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The request locks interaction, without washing out every action in the list. */
@Composable internal fun DefaultAction(text: String, eligible: Boolean, busy: Boolean, pending: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick, modifier.heightIn(min = 48.dp).pressMotion(source),
        enabled = eligible && !busy, interactionSource = source,
        colors = ButtonDefaults.textButtonColors(disabledContentColor = if (eligible) Carbon.Blue else Carbon.Muted)) {
        Text(text, Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        ReadActivity(pending)
    }
}
