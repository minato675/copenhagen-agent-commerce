package com.copenhagen.wallet.ui

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun rememberLoadingPhase(hasContent: Boolean, busy: Boolean): LoadingTimeline.Phase {
    val reduced = LocalReducedMotion.current
    val timeline = remember { LoadingTimeline() }
    var frame by remember { mutableStateOf(timeline.advance(hasContent, busy, reduced, SystemClock.uptimeMillis())) }
    LaunchedEffect(hasContent, busy, reduced) {
        while (true) {
            frame = timeline.advance(hasContent, busy, reduced, SystemClock.uptimeMillis())
            delay(frame.nextUpdateMillis ?: break)
        }
    }
    // Errors and accessibility preferences take effect in this composition, without a held frame.
    return when {
        !hasContent && !busy -> LoadingTimeline.Phase.Error
        reduced -> if (hasContent) LoadingTimeline.Phase.Content else LoadingTimeline.Phase.Loading
        frame.phase == LoadingTimeline.Phase.Error -> if (hasContent) LoadingTimeline.Phase.Content else LoadingTimeline.Phase.Waiting
        frame.phase == LoadingTimeline.Phase.Content && !hasContent -> LoadingTimeline.Phase.Waiting
        else -> frame.phase
    }
}

/** A finishing motion may remain after a read, but only a real read announces a busy state. */
@Composable internal fun ReadActivity(active: Boolean, color: Color = Carbon.Blue, modifier: Modifier = Modifier,
    activeDescription: String = "불러오는 중") {
    val phase = rememberLoadingPhase(hasContent = !active, busy = active)
    val reduced = LocalReducedMotion.current
    Box(modifier.size(width = 24.dp, height = 16.dp).semantics {
        if (active && phase == LoadingTimeline.Phase.Loading) stateDescription = activeDescription
    }) {
        AnimatedVisibility(phase == LoadingTimeline.Phase.Loading,
            enter = if (reduced) EnterTransition.None else fadeIn(tween(120)),
            exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.Content))) {
            val progress = loadingSweep()
            Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp).clearAndSetSemantics { }) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(color.copy(alpha = .18f), cornerRadius = radius)
                val width = size.width * .4f
                val start = if (reduced) (size.width - width) / 2 else progress.value * (size.width + width) - width
                val left = start.coerceAtLeast(0f)
                val right = (start + width).coerceAtMost(size.width)
                if (right > left) drawRoundRect(color, Offset(left, 0f), Size(right - left, size.height), radius)
            }
        }
    }
}

@Composable private fun loadingSweep(): State<Float> {
    if (LocalReducedMotion.current) return remember { mutableFloatStateOf(.5f) }
    return rememberInfiniteTransition(label = "read-surface").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "read-highlight")
}

@Composable private fun LoadingCard(announce: Boolean) {
    val progress = loadingSweep()
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(24.dp))
        .background(Carbon.Layer).drawWithCache {
            onDrawWithContent {
                drawContent()
                if (!reduced) {
                    val x = (progress.value * 2f - .5f) * size.width
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .45f), Color.Transparent),
                        start = Offset(x - size.width * .3f, 0f), end = Offset(x + size.width * .3f, size.height)))
                }
            }
        }.semantics { if (announce) contentDescription = "불러오는 중" }
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth(.35f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Carbon.Line))
        Box(Modifier.fillMaxWidth(.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Carbon.Line))
        Box(Modifier.fillMaxWidth(.45f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Carbon.Line))
    }
}

@Composable fun <T> Data(value: T?, busy: Boolean, empty: String, retry: () -> Unit, content: @Composable (T) -> Unit) {
    val phase = rememberLoadingPhase(hasContent = value != null, busy = busy)
    val reduced = LocalReducedMotion.current
    AnimatedContent(phase, modifier = Modifier.fillMaxWidth(), transitionSpec = {
        if (reduced || targetState == LoadingTimeline.Phase.Error) {
            (EnterTransition.None togetherWith ExitTransition.None).using(null)
        } else {
            (fadeIn(tween(Motion.Content)) togetherWith fadeOut(tween(Motion.Content)))
                .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(Motion.Surface, easing = Motion.Reveal) }))
        }
    }, label = "read-surface-to-content") { displayed ->
        Column(Modifier.fillMaxWidth().then(if (displayed != phase) Modifier.clearAndSetSemantics { } else Modifier),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (displayed) {
                LoadingTimeline.Phase.Waiting -> Spacer(Modifier.fillMaxWidth().height(112.dp))
                LoadingTimeline.Phase.Loading -> LoadingCard(announce = value == null && busy)
                LoadingTimeline.Phase.Content -> value?.let { content(it) }
                LoadingTimeline.Phase.Error -> { Notice(empty); Secondary("다시 불러오기", onClick = retry) }
            }
        }
    }
}
