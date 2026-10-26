package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

internal const val PaymentCardRatio = 85.60f / 53.98f
internal val PaymentCardMaxWidth = 440.dp

internal data class CardPalette(val base: Color, val tone: Color, val highlight: Color)

// The order and seed namespace are part of the visual identity of already registered cards.
// Keep v1 stable: neither a default change nor a new app process should redeal the artwork.
internal val CardPalettes = listOf(
    CardPalette(Color(0xFF192330), Color(0xFF35465D), Color(0xFF8A9EB5)), // Graphite
    CardPalette(Color(0xFF10265D), Color(0xFF244F9C), Color(0xFF739AE8)), // Cobalt
    CardPalette(Color(0xFF103B40), Color(0xFF216D72), Color(0xFF81BCB6)), // Petrol
    CardPalette(Color(0xFF1D382C), Color(0xFF416952), Color(0xFF9AB798)), // Forest
    CardPalette(Color(0xFF522A25), Color(0xFF985B43), Color(0xFFD6A17F)), // Clay
    CardPalette(Color(0xFF3D263E), Color(0xFF754A6D), Color(0xFFC197B2)), // Plum
    CardPalette(Color(0xFF403921), Color(0xFF7C7142), Color(0xFFCABC84)), // Brass
    CardPalette(Color(0xFF293448), Color(0xFF566784), Color(0xFFA7B9D0))  // Slate
)

internal data class CardAppearance(val palette: Int, val motif: Int, val variation: Int, val mirrored: Boolean) {
    companion object {
        fun forId(id: String): CardAppearance {
            // Only the opaque payment-method ID is used. No PAN, expiry, brand, list index,
            // preference, random runtime seed, storage, or network request participates.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("copenhagen-card-v1:$id".toByteArray(Charsets.UTF_8))
            fun byte(index: Int) = digest[index].toInt() and 255
            return CardAppearance(byte(0) % 8, byte(1) % 6, byte(2) % 8, byte(3) % 2 == 1)
        }
    }
}

/** Six original native compositions; cached geometry stays completely still at rest. */
@Composable internal fun CardArtwork(appearance: CardAppearance, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics { }.drawWithCache {
        val palette = CardPalettes[appearance.palette]
        val w = size.width
        val h = size.height
        val shift = (appearance.variation - 3.5f) * .025f
        fun point(x: Float, y: Float) = Offset(x * w, y * h)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        val shapes = when (appearance.motif) {
            0 -> listOf(path {
                moveTo(w * (.48f + shift), -h * .1f)
                cubicTo(w * .98f, h * .18f, w * .32f, h * .64f, w * (.84f + shift), h * 1.1f)
                lineTo(w * 1.1f, h * 1.1f); lineTo(w * 1.1f, -h * .1f); close()
            }, path {
                moveTo(w * (.7f + shift), -h * .1f)
                cubicTo(w * 1.1f, h * .25f, w * .62f, h * .7f, w, h * 1.1f)
            })
            2 -> listOf(path {
                moveTo(w * (.46f + shift), -h * .1f); lineTo(w * .9f, h * 1.1f)
                lineTo(w * 1.1f, h * 1.1f); lineTo(w * 1.1f, -h * .1f); close()
            }, path {
                moveTo(w * (.74f + shift), -h * .1f); lineTo(w * .49f, h * 1.1f)
                lineTo(w * .9f, h * 1.1f); close()
            })
            3 -> (0..5).map { index -> path {
                val x = .35f + index * .105f + shift
                moveTo(w * x, -h * .1f)
                cubicTo(w * (x - .3f), h * .55f, w * (x + .5f), h * .3f, w * (x + .22f), h * 1.1f)
            } }
            4 -> (0..2).map { index -> path {
                val y = .23f + index * .23f + shift
                moveTo(-w * .1f, h * (y + .3f))
                cubicTo(w * .3f, -h * .25f, w * .65f, h * 1.1f, w * 1.1f, h * y)
                lineTo(w * 1.1f, h * 1.1f); lineTo(-w * .1f, h * 1.1f); close()
            } }
            5 -> (0..3).map { index -> path {
                val x = .15f + index * .24f + shift
                moveTo(w * x, -h * .1f); lineTo(w * (x + .22f), -h * .1f)
                lineTo(w * (x + .75f), h * 1.1f); lineTo(w * (x + .53f), h * 1.1f); close()
            } }
            else -> emptyList()
        }
        val ground = Brush.linearGradient(listOf(palette.base, palette.tone), Offset.Zero, point(1f, 1f))
        val material = Brush.linearGradient(listOf(palette.tone, palette.highlight, palette.tone), point(.3f, -.1f), point(1.1f, 1.1f))
        val shade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = .16f), Color.Transparent, Color.Black.copy(alpha = .22f)))
        onDrawBehind {
            drawRect(ground)
            withTransform({ if (appearance.mirrored) scale(-1f, 1f, center) }) {
                when (appearance.motif) {
                    0 -> {
                        drawPath(shapes[0], material)
                        drawPath(shapes[1], palette.highlight.copy(alpha = .7f), style = Stroke(w * .003f))
                    }
                    1 -> {
                        val orbit = point(.86f + shift, .12f)
                        drawCircle(material, w * .48f, orbit, style = Stroke(w * .16f))
                        drawCircle(palette.highlight.copy(alpha = .65f), w * .65f, orbit, style = Stroke(w * .003f))
                        drawCircle(palette.highlight.copy(alpha = .35f), w * .31f, orbit, style = Stroke(w * .025f))
                    }
                    2 -> {
                        drawPath(shapes[0], material)
                        drawPath(shapes[1], palette.highlight.copy(alpha = .3f))
                    }
                    3 -> shapes.forEachIndexed { index, shape ->
                        drawPath(shape, material, style = Stroke(w * if (index % 2 == 0) .048f else .008f))
                    }
                    4 -> shapes.forEachIndexed { index, shape ->
                        drawPath(shape, material, alpha = .8f - index * .18f)
                        drawPath(shape, palette.highlight.copy(alpha = .35f), style = Stroke(w * .002f))
                    }
                    5 -> shapes.forEachIndexed { index, shape -> drawPath(shape, material, alpha = if (index % 2 == 0) .95f else .5f) }
                }
            }
            // All native text is white. This bound protects even the brightest material
            // beneath text and the translucent default badge, across every composition.
            drawRect(Color.Black.copy(alpha = .40f))
            drawRect(shade)
        }
    })
}

@Composable internal fun CardThumbnail(id: String, modifier: Modifier = Modifier) {
    val appearance = remember(id) { CardAppearance.forId(id) }
    CardArtwork(appearance, modifier.width(48.dp).aspectRatio(PaymentCardRatio).clip(RoundedCornerShape(5.dp)))
}
