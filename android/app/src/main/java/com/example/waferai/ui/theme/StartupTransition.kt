package com.example.waferai.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.waferai.R
import kotlinx.coroutines.launch
import kotlin.math.sin

internal const val StartupDurationMillis = 2500
private const val ExitDurationMillis = 240

/** Keeps the real home screen initialized behind the skippable launch animation. */
@Composable
internal fun StartupTransition(
    enabled: Boolean,
    ready: Boolean,
    onFinished: () -> Unit,
    content: @Composable () -> Unit
) {
    var show by remember { mutableStateOf(enabled) }
    val finished by rememberUpdatedState(onFinished)
    val finish = {
        if (show) {
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
        Box(if (show) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        if (show) {
            val reveal = remember { Animatable(0f) }
            val scan = remember { Animatable(0f) }
            val fade = remember { Animatable(0f) }
            val easeOut = remember { CubicBezierEasing(.23f, 1f, .32f, 1f) }
            val navy = colorResource(R.color.wafer_splash_navy)
            val cyan = colorResource(R.color.wafer_splash_cyan)
            val lift = with(LocalDensity.current) { 12.dp.toPx() }
            LaunchedEffect(ready) {
                if (!ready) return@LaunchedEffect
                launch { reveal.animateTo(1f, tween(240, easing = easeOut)) }
                scan.animateTo(1f, tween(StartupDurationMillis - ExitDurationMillis, easing = LinearEasing))
                fade.animateTo(1f, tween(ExitDurationMillis, easing = easeOut))
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
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension * .36f
                        val glow = sin(scan.value * Math.PI).toFloat().coerceIn(0f, 1f)
                        val origin = Offset(center.x - radius, center.y - radius)
                        val diameter = Size(radius * 2, radius * 2)
                        drawArc(cyan.copy(alpha = glow * .12f), -130f + 300f * scan.value,
                            76f, false, origin, diameter, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                        drawArc(cyan.copy(alpha = glow), -130f + 300f * scan.value,
                            76f, false, origin, diameter, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    }
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
