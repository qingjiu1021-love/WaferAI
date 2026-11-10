package com.example.waferai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.waferai.R

@Composable
internal fun Modifier.starryNightWallpaper(pureBlack: Boolean): Modifier {
    if (!LocalStarryNight.current || pureBlack) return this

    val painting = painterResource(R.drawable.starry_night_wallpaper)
    return drawWithCache {
        val intrinsic = painting.intrinsicSize
        val scale = ContentScale.Crop.computeScaleFactor(intrinsic, size)
        val artSize = Size(intrinsic.width * scale.scaleX, intrinsic.height * scale.scaleY)
        val left = (size.width - artSize.width) * 0.60f
        val top = (size.height - artSize.height) * 0.5f
        val scrim = Brush.verticalGradient(
            0f to StarryNightBase.copy(alpha = 0.72f),
            0.40f to StarryNightBase.copy(alpha = 0.62f),
            1f to StarryNightBase.copy(alpha = 0.86f)
        )
        onDrawBehind {
            clipRect { translate(left, top) { with(painting) { draw(artSize) } } }
            drawRect(scrim)
        }
    }
}
