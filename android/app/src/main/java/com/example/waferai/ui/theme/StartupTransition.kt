package com.example.waferai.ui.theme

import android.graphics.drawable.Animatable as DrawableAnimation
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.waferai.R
import kotlinx.coroutines.delay

internal const val StartupDurationMillis = 2500
private const val ExitDurationMillis = 240
private const val RevealDurationMillis = 240
private const val SweepDurationMillis = StartupDurationMillis - ExitDurationMillis

/** Draws the intro first and mounts the home screen during the native sweep. */
@Composable
internal fun StartupTransition(
    enabled: Boolean,
    onFinished: () -> Unit,
    content: @Composable () -> Unit
) {
    var show by remember { mutableStateOf(enabled) }
    var showHome by remember { mutableStateOf(!enabled) }
    val finished by rememberUpdatedState(onFinished)
    val finish = {
        if (show) {
            showHome = true
            show = false
            finished()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            // Don't resume a half-finished brand animation after leaving the app.
            if (event == Lifecycle.Event.ON_STOP) finish()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize()) {
        // Keep controls behind the overlay out of accessibility traversal until revealed.
        if (showHome) {
            Box(if (show) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        }
        if (show) {
            val reveal = remember { Animatable(0f) }
            val fade = remember { Animatable(0f) }
            val navy = colorResource(R.color.wafer_splash_navy)
            val cyan = colorResource(R.color.wafer_splash_cyan)
            val lift = with(LocalDensity.current) { 12.dp.toPx() }
            LaunchedEffect(Unit) {
                val endOfSweep = withFrameNanos { it } + SweepDurationMillis * 1_000_000L
                reveal.animateTo(1f, tween(RevealDurationMillis, easing = ResponsiveEasing))
                showHome = true
                // Rendering the vector sweep is independent of composing the home screen.
                val remaining = (endOfSweep - withFrameNanos { it }) / 1_000_000L
                delay(remaining.coerceAtLeast(0L))
                fade.animateTo(1f, tween(ExitDurationMillis, easing = ResponsiveEasing))
                finish()
            }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - fade.value }
                    .background(navy)
                    .semantics(mergeDescendants = true) { contentDescription = "WaferAI" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "跳过启动动画 / Skip intro",
                        onClick = finish
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(192.dp).graphicsLayer {
                    translationY = -lift * reveal.value
                    scaleX = 0.96f + 0.04f * reveal.value
                    scaleY = scaleX
                }) {
                    Image(painterResource(R.drawable.wafer_launcher_art), null, Modifier.fillMaxSize())
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                setImageResource(R.drawable.wafer_open_scan)
                                (drawable as DrawableAnimation).start()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onReset = null,
                        onRelease = { (it.drawable as? DrawableAnimation)?.stop() },
                        update = {}
                    )
                }
                Column(
                    Modifier.offset(y = 136.dp).graphicsLayer {
                        alpha = reveal.value
                        translationY = lift * (1f - reveal.value)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("WaferAI", color = cyan, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif, letterSpacing = 2.sp)
                    Text("WAFER INTELLIGENCE", color = cyan.copy(alpha = .55f), fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif, letterSpacing = 2.sp)
                }
            }
        }
    }
}
