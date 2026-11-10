package com.example.waferai.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.waferai.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext

// The system setting chooses a separate 300 ms scene instead of scaling this timeline.
private object SplashMotionScale : MotionDurationScale {
    override val scaleFactor = 1f
}

@Composable
internal fun StartupTransition(
    enabled: Boolean,
    onFinished: () -> Unit,
    reducedMotion: Boolean = false,
    preloadedArtwork: Deferred<ImageBitmap?>? = null,
    content: @Composable () -> Unit,
) {
    var show by remember { mutableStateOf(enabled) }
    var showHome by remember { mutableStateOf(!enabled) }
    val config = WaferSplashConfig
    val time = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    val finish = {
        if (show) {
            showHome = true
            show = false
            finished()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (showHome) {
            val covered = Modifier.clearAndSetSemantics { }.drawWithContent {
                if (!reducedMotion && time.value >= config.revealHomeMillis) drawContent()
            }
            Box(if (show) covered else Modifier) { content() }
        }
        if (show) {
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) finish()
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            BackHandler(onBack = finish)

            val navy = colorResource(R.color.wafer_splash_navy)
            LaunchedEffect(Unit) {
                val duration = if (reducedMotion) config.reduceMotionMillis else config.totalMillis
                val prepareHome = if (reducedMotion) config.prepareReducedHomeMillis else config.prepareHomeMillis
                withContext(SplashMotionScale) {
                    time.animateTo(duration.toFloat(), tween(duration, easing = LinearEasing)) {
                        if (!showHome && value >= prepareHome) showHome = true
                    }
                }
                finish()
            }

            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = if (reducedMotion) 1f else 1f - phase(time.value, config.revealHomeMillis, config.totalMillis)
                    }
                    .background(navy)
                    .semantics(mergeDescendants = true) { contentDescription = "WaferAI" }
                    .clickable(interactionSource = null, indication = null,
                        onClickLabel = "跳过启动动画 / Skip intro", onClick = finish)
            ) {
                if (!reducedMotion) {
                    PaintedBadgeSplash(preloadedArtwork) { time.value }
                } else {
                    ReducedSplashBadge(config) { time.value }
                }
            }
        }
    }
}


