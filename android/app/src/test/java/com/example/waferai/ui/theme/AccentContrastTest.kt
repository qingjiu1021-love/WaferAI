package com.example.waferai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentContrastTest {
    @Test fun customAccentsRemainReadableInBothAppearances() {
        val seeds = listOf(Color.Black, Color.White, Color.Yellow, Color.Red, Color.Green, Color.Blue,
            Color(0xFF92939B), Color(0xFF1680F5), Color(0xFFAF74EA), Color(0x00FFFFFF))
        for (dark in listOf(false, true)) for (seed in seeds) {
            val accent = readableAccent(seed, dark)
            val backgrounds = if (dark) listOf(Color.Black, Color(0xFF101012), Color(0xFF1C1C1F), Color(0xFF27272B))
                else listOf(Color.White, Color(0xFFF2F2F7))
            for (background in backgrounds) assertTrue("Unreadable accent $seed, dark=$dark", contrast(accent, background) >= 4.5f)
            val buttonText = if (accent.luminance() > 0.179f) Color(0xFF111114) else Color.White
            assertTrue("Unreadable button label", contrast(accent, buttonText) >= 4.5f)
        }
    }

    private fun contrast(a: Color, b: Color) =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
