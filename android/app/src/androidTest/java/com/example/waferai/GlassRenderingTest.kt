package com.example.waferai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.glassSurface
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clearGlassHasRoundedCornersAndNoResidualDarkLayer() {
        val opacity = mutableFloatStateOf(.28f)
        compose.setContent {
            Box(Modifier.testTag("scene").background(Color.White).padding(16.dp)) {
                Box(Modifier.size(120.dp).testTag("glass").glassSurface(
                    Color(0xFF1B2B50), Color(0xFFF0C36C), RoundedCornerShape(24.dp),
                    GlassLevel.INTENSE, true, opacity.floatValue))
            }
        }
        val image = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val inset = image.width / 152f * 16f
        val corner = image[(inset + 1).toInt(), (inset + 1).toInt()]
        assertTrue("The round corner must reveal the unmodified background", corner.red > .98f && corner.green > .98f && corner.blue > .98f)
        val center = image[image.width / 2, image.height / 2]
        assertTrue("Tint must stay translucent", center.red > .65f && center.blue > .7f)
        compose.runOnIdle { opacity.floatValue = 0f }
        val clear = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        for (y in 0 until clear.height step 5) for (x in 0 until clear.width step 5) {
            val pixel = clear[x, y]
            assertTrue("Fully transparent glass must leave no dark frame", pixel.red > .98f && pixel.green > .98f && pixel.blue > .98f)
        }
    }
}
