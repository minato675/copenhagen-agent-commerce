package com.copenhagen.wallet.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel

// Intentionally never stored on disk. A new process gets a new session.
private val harborProcessSession = HarborSplashSession()

/** An Activity-scoped ViewModel retains the first playback through configuration changes. */
class HarborLaunchState : ViewModel() {
    private val playback = harborProcessSession.claim()
    var elapsedNanos by mutableLongStateOf(0L)
        private set
    var finished by mutableStateOf(playback == null)
        private set

    internal fun onFrame(frameNanos: Long) {
        playback?.let {
            elapsedNanos = it.onFrame(frameNanos)
            finished = it.finished
        }
    }

    internal fun pause() { playback?.pause() }
}

/** The neutral system launch window hands off to our first drawn frame without a second icon. */
fun Activity.prepareHarborLaunch() {
    if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
}

@Composable
fun HarborLaunch(state: HarborLaunchState, lifecycle: Lifecycle, windowFocused: Boolean, content: @Composable () -> Unit) {
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, resumed, windowFocused, state.finished) {
        state.pause()
        if (state.finished || !resumed || !windowFocused) return@LaunchedEffect
        try {
            // App readiness never shortens the trip, final-logo dissolve, or final hold.
            while (!state.finished) {
                withFrameNanos(state::onFrame)
            }
        } finally {
            state.pause()
        }
    }

    Crossfade(state.finished, animationSpec = tween(HarborSplashTimeline.ExitMillis), label = "harbor-to-app") { enterApp ->
        if (enterApp) content()
        else CopenhagenTheme { HarborSplash(state.elapsedNanos / 1_000_000L) }
    }
}
