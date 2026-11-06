package com.example.waferai.ui.theme

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

// Motion parameters from FlClash 7c9627f, lib/ui/navigation_dock.dart.
private data class SpringParameters(val stiffness: Float, val dampingRatio: Float) {
    val sampler = FloatSpringSpec(dampingRatio, stiffness)
    val damping = 2f * dampingRatio * sqrt(stiffness)
}

private fun durationSpring(seconds: Float, bounce: Float = 0f): SpringParameters {
    val frequency = 2 * PI / seconds
    return SpringParameters((frequency * frequency).toFloat(), 1f - bounce)
}

private val pressSpring = SpringParameters(311f, 0.593f)
private val followSpring = SpringParameters(1024f, 0.9f)
private val wobbleSpring = SpringParameters(100f, 0.4f)
private val liftSpring = durationSpring(0.25f)
private val travelSpring = durationSpring(0.4f, 0.15f)
private val pullSpring = durationSpring(0.12f)

private class SpringAxis(initial: Float) {
    var value = initial
        private set
    var target = initial
        private set
    var velocity = 0f
        private set
    var active = false
        private set
    private var from = initial
    private var incomingVelocity = 0f
    private var elapsed = 0L
    private var parameters = travelSpring

    val acceleration: Float
        get() = parameters.stiffness * (target - value) - parameters.damping * velocity

    fun retarget(destination: Float, spring: SpringParameters) {
        if (destination == target && (!active || spring == parameters)) return
        from = value
        incomingVelocity = velocity
        elapsed = 0L
        parameters = spring
        target = destination
        active = true
    }

    fun reset(destination: Float) {
        value = destination
        target = destination
        velocity = 0f
        active = false
    }

    fun advance(nanos: Long) {
        if (!active) return
        elapsed += nanos
        val spring = parameters.sampler
        value = spring.getValueFromNanos(elapsed, from, target, incomingVelocity)
        velocity = spring.getVelocityFromNanos(elapsed, from, target, incomingVelocity)
        // Stop when the remaining movement is below a pixel at typical dock widths.
        if (abs(value - target) < 0.003f && abs(velocity) < 0.012f) reset(target)
    }
}

internal data class DockFrame(
    val position: Float,
    val lift: Float = 0f,
    val wobble: Float = 0f,
    val swell: Float = 0f,
    val pull: Float = 0f
)

internal class DockMotion(index: Int, private val animated: Boolean) {
    private val position = SpringAxis(index.toFloat())
    private val lift = SpringAxis(0f)
    private val wobble = SpringAxis(0f)
    private val swell = SpringAxis(0f)
    private val pull = SpringAxis(0f)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pressed = false
    private var landing = false
    var frame by mutableStateOf(DockFrame(index.toFloat()))
        private set

    fun select(index: Int) {
        if (!pressed) move(index.toFloat(), travelSpring)
    }

    fun press(index: Int) {
        pressed = true
        landing = false
        if (animated) {
            lift.retarget(1f, liftSpring)
            swell.retarget(1f, pressSpring)
            move(index.toFloat(), travelSpring)
        }
    }

    fun drag(index: Float, edgePullDp: Float) {
        if (!animated) return
        pull.retarget(edgePullDp, pullSpring)
        move(index, followSpring)
    }

    fun release(index: Int) {
        pressed = false
        landing = animated
        swell.retarget(0f, pressSpring)
        pull.retarget(0f, pressSpring)
        move(index.toFloat(), travelSpring)
    }

    private fun move(index: Float, spring: SpringParameters) {
        if (animated) {
            position.retarget(index, spring)
            wake.trySend(Unit)
        } else {
            position.reset(index)
            frame = DockFrame(index)
        }
    }

    suspend fun animate() {
        while (true) {
            wake.receive()
            var previous = withFrameNanos { it }
            while (position.active || lift.active || wobble.active || swell.active || pull.active || landing) {
                val now = withFrameNanos { it }
                val dt = now - previous
                previous = now
                position.advance(dt)
                lift.advance(dt)
                swell.advance(dt)
                pull.advance(dt)
                val speed = position.velocity
                val braking = (-position.acceleration * sign(speed)).coerceAtLeast(0f)
                wobble.retarget(0.20f * tanh(abs(speed) / 3f) - 0.15f * tanh(braking / 25f), wobbleSpring)
                wobble.advance(dt)
                if (landing && lift.value >= 0.975f && abs(position.target - position.value) <= 0.04f) {
                    landing = false
                    lift.retarget(0f, liftSpring)
                }
                frame = DockFrame(position.value, lift.value, wobble.value, swell.value, pull.value)
            }
        }
    }
}

internal fun rubberBand(distance: Float, limit: Float): Float =
    if (limit <= 0f) 0f else sign(distance) * limit * (1f - 1f / (1f + abs(distance) * 0.55f / limit))

internal fun dockReleaseIndex(position: Float, velocity: Float, lastIndex: Int): Int {
    val underFinger = position.roundToInt().coerceIn(0, lastIndex)
    return (position + velocity * 0.10f).roundToInt()
        .coerceIn(underFinger - 1, underFinger + 1).coerceIn(0, lastIndex)
}
