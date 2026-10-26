package com.copenhagen.wallet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.R
import kotlin.math.PI
import kotlin.math.sin

private const val ArtworkSide = 1254f

/** Draws original bitmap material inside the shared vector silhouettes; no regenerated logo. */
@Composable
internal fun HarborSplash(elapsedMillis: Long) {
    val frame = HarborSplashTimeline.frame(elapsedMillis, LocalReducedMotion.current)
    val description = stringResource(R.string.harbor_splash_description)
    val image = ImageBitmap.imageResource(R.drawable.copenhagen_splash)
    val dockData = stringResource(R.string.brand_dock_path)
    val boatData = stringResource(R.string.brand_boat_path)
    val dock = remember(dockData) { PathParser().parsePathString(dockData).toPath() }
    val boat = remember(boatData) {
        PathParser().parsePathString(boatData).toPath().apply { fillType = PathFillType.EvenOdd }
    }
    val wake = remember {
        Path().apply {
            moveTo(1032f, 578f); cubicTo(1080f, 572f, 1128f, 543f, 1178f, 546f)
            moveTo(1032f, 668f); cubicTo(1080f, 674f, 1128f, 703f, 1178f, 700f)
        }
    }
    val sea = remember {
        Brush.linearGradient(listOf(Color(0xFF1069FC), Color(0xFF0751FD)),
            start = Offset.Zero, end = Offset(ArtworkSide, ArtworkSide))
    }
    val daylight = remember {
        Brush.radialGradient(listOf(Color(0x663887F7), Color.Transparent),
            center = Offset.Zero, radius = ArtworkSide)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Carbon.Canvas).safeDrawingPadding()
        .clearAndSetSemantics { contentDescription = description }) {
        val artworkSize = minOf(232.dp, maxWidth * .6f, maxHeight * .48f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            Canvas(Modifier.size(artworkSize).graphicsLayer { alpha = frame.artworkAlpha }
                .clip(RoundedCornerShape(percent = 28))) {
                withTransform({ scale(size.width / ArtworkSide, size.height / ArtworkSide, Offset.Zero) }) {
                    if (frame.originalLogoAlpha == 1f) {
                        original(image)
                    } else {
                        drawRect(sea, size = Size(ArtworkSide, ArtworkSide))
                        drawRect(daylight, size = Size(ArtworkSide, ArtworkSide))
                        val arrivalAlpha = .2f * sin(PI.toFloat() * frame.arrival).coerceAtLeast(0f)
                        if (arrivalAlpha > 0f) {
                            drawCircle(Color.White, 42f + 88f * frame.arrival, Offset(500f, 625f),
                                alpha = arrivalAlpha, style = Stroke(5f))
                        }
                        shadow(dock)
                        clipPath(dock) { original(image) }
                        translate(frame.boatX, frame.boatY) {
                            rotate(frame.bankDegrees, Offset(750f, 623f)) {
                                if (frame.wakeAlpha > 0f) {
                                    drawPath(wake, Color.White, alpha = frame.wakeAlpha,
                                        style = Stroke(7f, cap = StrokeCap.Round))
                                    drawLine(Color.White, Offset(1060f, 624f), Offset(1175f, 624f),
                                        strokeWidth = 5f, cap = StrokeCap.Round, alpha = frame.wakeAlpha * .55f)
                                }
                                shadow(boat)
                                clipPath(boat) { original(image) }
                            }
                        }
                        if (frame.originalLogoAlpha > 0f) original(image, frame.originalLogoAlpha)
                    }
                }
            }
            Text(stringResource(R.string.app_name), color = Carbon.Ink,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.graphicsLayer { alpha = frame.wordmarkAlpha })
        }
    }
}

private fun DrawScope.original(image: ImageBitmap, alpha: Float = 1f) {
    drawImage(image, dstSize = IntSize(1254, 1254), alpha = alpha, filterQuality = FilterQuality.Medium)
}

private fun DrawScope.shadow(path: Path) {
    // Soft, inexpensive cast-shadow bands; source artwork supplies the white ceramic bevels.
    translate(top = 18f) {
        drawPath(path, Color(0xFF0039CF), alpha = .07f, style = Stroke(46f, join = StrokeJoin.Round))
        drawPath(path, Color(0xFF0039CF), alpha = .13f, style = Stroke(22f, join = StrokeJoin.Round))
        drawPath(path, Color(0xFF0039CF), alpha = .18f)
    }
}
