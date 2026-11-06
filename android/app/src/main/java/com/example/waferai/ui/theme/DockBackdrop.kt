package com.example.waferai.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlinx.coroutines.delay

internal class DockBackdrop(val scene: GraphicsLayer, private val tabMoving: State<Boolean>) {
    var origin by mutableStateOf(Offset.Unspecified)
    var pressed by mutableStateOf(false)
    var settled by mutableStateOf(true)
    val interacting get() = pressed || tabMoving.value
    val canSample get() = settled && !interacting
}

@Composable
internal fun rememberDockBackdrop(enabled: Boolean, tabMoving: State<Boolean>): DockBackdrop? {
    if (!enabled || Build.VERSION.SDK_INT < 31) return null
    val scene = rememberGraphicsLayer()
    val backdrop = remember(scene, tabMoving) { DockBackdrop(scene, tabMoving) }
    LaunchedEffect(backdrop, backdrop.interacting) {
        if (backdrop.interacting) {
            backdrop.settled = false
        } else if (!backdrop.settled) {
            delay(140)
            backdrop.settled = true
        }
    }
    return backdrop
}

internal fun Modifier.recordDockBackdrop(backdrop: DockBackdrop?, background: Color): Modifier {
    if (backdrop == null) return this
    return onGloballyPositioned { backdrop.origin = it.positionInRoot() }.drawWithContent {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            drawContent()
            return@drawWithContent
        }
        // Record the page once and reuse that display list for the narrow dock region.
        backdrop.scene.record {
            drawRect(background)
            this@drawWithContent.drawContent()
        }
        drawLayer(backdrop.scene)
    }
}

@Composable
internal fun Modifier.sampleDockBackdrop(backdrop: DockBackdrop?, level: GlassLevel): Modifier {
    if (backdrop == null || Build.VERSION.SDK_INT < 31 || level == GlassLevel.NONE) return this
    val sample = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawWithCache {
        val resolution = 0.5f
        val sampledSize = IntSize(ceil(size.width * resolution).toInt(), ceil(size.height * resolution).toInt())
        if (sampledSize.width == 0 || sampledSize.height == 0) return@drawWithCache onDrawBehind { }
        val strong = level == GlassLevel.INTENSE
        val blur = blurEffect((if (strong) 3.5.dp else 5.dp).toPx() * resolution)
        sample.renderEffect = (if (Build.VERSION.SDK_INT >= 33) {
            refractedBlur(blur, sampledSize.width.toFloat(), sampledSize.height.toFloat(),
                (if (strong) 8.dp else 4.dp).toPx() * resolution)
        } else blur).asComposeRenderEffect()
        sample.clip = true
        var recordedShift = Offset.Unspecified
        onDrawBehind {
            // During a slide the tinted glass can show the moving pages directly.
            // Reapplying blur and refraction to two pages would contend with the animation.
            if (!backdrop.canSample) return@onDrawBehind
            val source = backdrop.origin
            val destination = origin
            if (source != Offset.Unspecified && destination != Offset.Unspecified &&
                backdrop.scene.size != IntSize.Zero && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                val shift = source - destination
                if (recordedShift != shift || sample.size != sampledSize) {
                    sample.record(size = sampledSize) {
                        scale(resolution, resolution, pivot = Offset.Zero) {
                            translate(shift.x, shift.y) { drawLayer(backdrop.scene) }
                        }
                    }
                    recordedShift = shift
                }
                scale(1f / resolution, 1f / resolution, pivot = Offset.Zero) { drawLayer(sample) }
            }
        }
    }
}

@RequiresApi(31)
private fun blurEffect(radius: Float): RenderEffect =
    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)

@RequiresApi(33)
private fun refractedBlur(blur: RenderEffect, width: Float, height: Float, bend: Float): RenderEffect {
    val shader = RuntimeShader(DockRefraction)
    shader.setFloatUniform("bounds", width, height)
    shader.setFloatUniform("bend", bend)
    return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "backdrop"), blur)
}

private const val DockRefraction = """
uniform shader backdrop;
uniform float2 bounds;
uniform float bend;

half4 main(float2 position) {
    float2 centered = position - bounds * 0.5;
    float radius = bounds.y * 0.5;
    float middle = max(0.0, bounds.x * 0.5 - radius);
    float2 edge = centered - float2(clamp(centered.x, -middle, middle), 0.0);
    float distance = length(edge);
    float rim = 1.0 - smoothstep(0.0, bend * 2.4, radius - distance);
    float2 inward = edge / max(distance, 1.0);
    float2 samplePoint = clamp(position - inward * bend * rim, float2(0.0), bounds - float2(1.0));
    return backdrop.eval(samplePoint);
}
"""
