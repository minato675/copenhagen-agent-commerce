@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.copenhagen.wallet.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.Route
import com.copenhagen.wallet.Screen

object Motion {
    const val Press = 90; const val Release = 180; const val Content = 220
    const val Navigation = 300; const val Surface = 380; const val Signature = 560
    val Reveal = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val Morph = CubicBezierEasing(.2f, .8f, .2f, 1f)
    // Apple HIG / WWDC fluid-interface principles, expressed in Compose's own physical model.
    // These are original project constants, not Apple framework parameters or copied presets.
    fun <T> control(threshold: T? = null) = spring<T>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = threshold)
    fun <T> navigation(threshold: T? = null) = spring<T>(dampingRatio = 1f, stiffness = 620f, visibilityThreshold = threshold)
    fun <T> surface(threshold: T? = null) = spring<T>(dampingRatio = 1f, stiffness = 440f, visibilityThreshold = threshold)
}
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
val LocalDisplayedRoute = staticCompositionLocalOf { Route(Screen.A01) }

@Composable fun MotionPreferences(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    fun reduced() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember { mutableStateOf(reduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = reduced() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduce, content = content)
}
fun Modifier.pressMotion(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale = animateFloatAsState(if (pressed && !reduced) .982f else 1f,
        if (reduced) snap() else Motion.control(.001f), label = "press")
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
fun Modifier.sharedObject(identity: String): Modifier = composed {
    val shared = LocalSharedScope.current
    val visible = LocalVisibilityScope.current
    if (shared == null || visible == null || LocalReducedMotion.current) this else with(shared) {
        this@composed.sharedBounds(rememberSharedContentState(identity), visible,
            boundsTransform = { _, _ -> Motion.surface(androidx.compose.ui.geometry.Rect(1f, 1f, 1f, 1f)) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            enter = fadeIn(tween(Motion.Content)), exit = fadeOut(tween(100)),
            renderInOverlayDuringTransition = true)
    }
}
@Composable fun MotionReveal(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(visible,
        enter = if (reduced) EnterTransition.None else expandVertically(Motion.surface()) + fadeIn(tween(Motion.Content)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(Motion.navigation()) + fadeOut(tween(100))) { content() }
}
/** Exact strings are exchanged, never numerically interpolated. No input events await this animation. */
@Composable fun ValueText(text: String, style: TextStyle = MaterialTheme.typography.titleLarge, color: Color = Carbon.Ink) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(text, transitionSpec = {
        val transform = if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 8 }) togetherWith fadeOut(tween(80))
        transform.using(if (reduced) null else SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.control() }))
    }, label = "exact-value", modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text }) { value ->
        Text(value, style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
    }
}
