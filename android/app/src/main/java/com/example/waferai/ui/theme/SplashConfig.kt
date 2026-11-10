package com.example.waferai.ui.theme

internal data class SplashConfig(
    val totalMillis: Int = 2250,
    val reduceMotionMillis: Int = 300,
    val revealHomeMillis: Int = 2040,
    val prepareHomeMillis: Int = 1790,
    val prepareReducedHomeMillis: Int = 80,
)

internal val WaferSplashConfig = SplashConfig()

internal fun phase(time: Float, start: Int, end: Int) =
    ((time - start) / (end - start)).coerceIn(0f, 1f)

internal fun easeOutCubic(value: Float): Float {
    val remaining = 1f - value
    return 1f - remaining * remaining * remaining
}


