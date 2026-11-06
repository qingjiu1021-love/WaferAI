package com.example.waferai.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

internal val ResponsiveEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

internal fun tabTransition(direction: Int, motion: TabMotion, enabled: Boolean): ContentTransform {
    if (!enabled || direction == 0) return EnterTransition.None togetherWith ExitTransition.None
    if (motion == TabMotion.FADE) {
        return fadeIn(tween(190, easing = ResponsiveEasing)) togetherWith
            fadeOut(tween(190, easing = ResponsiveEasing))
    }
    val easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    return slideInHorizontally(tween(300, easing = easing)) { direction * it } togetherWith
        slideOutHorizontally(tween(300, easing = easing)) { -direction * it }
}
