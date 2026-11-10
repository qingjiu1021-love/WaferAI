package com.example.waferai.ui.theme

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.waferai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.math.roundToInt

@Composable
internal fun PaintedBadgeSplash(preloadedArtwork: Deferred<ImageBitmap?>?, time: () -> Float) {
    val artwork by produceState<ImageBitmap?>(null, preloadedArtwork) {
        value = preloadedArtwork?.await()
    }
    val badge = remember { PaintedBadge() }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val bitmap = artwork ?: return@Canvas
            val cover = maxOf(size.width / bitmap.width, size.height / bitmap.height)
            val width = (bitmap.width * cover).roundToInt()
            val height = (bitmap.height * cover).roundToInt()
            if (width > 0 && height > 0) {
                drawImage(bitmap,
                    dstOffset = IntOffset((center.x - width / 2f).roundToInt(), (center.y - height / 2f).roundToInt()),
                    dstSize = IntSize(width, height),
                    alpha = 0.68f)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            badge.draw(this, artwork, time())
        }
    }
}

@Composable
internal fun ReducedSplashBadge(config: SplashConfig, time: () -> Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.wafer_starry_badge), contentDescription = null,
            Modifier.size(138.dp).graphicsLayer { alpha = phase(time(), 0, config.reduceMotionMillis) })
    }
}

internal fun CoroutineScope.preloadSplashArtwork(resources: Resources): Deferred<ImageBitmap?> = async(Dispatchers.IO) {
    val target = resources.displayMetrics
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeResource(resources, R.drawable.night_bg, options)
    var sample = 1
    while (options.outWidth / (2 * sample) >= target.widthPixels.coerceAtLeast(1) &&
        options.outHeight / (2 * sample) >= target.heightPixels.coerceAtLeast(1)
    ) {
        sample *= 2
    }
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    BitmapFactory.decodeResource(resources, R.drawable.night_bg, options)?.asImageBitmap()
}
