package com.example.waferai.ui.theme

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun BackButton(onClick: () -> Unit, label: String = "返回", enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled) {
        StudioGlyph(StudioIcon.ARROW, Modifier.size(16.dp).rotate(
            if (LocalLayoutDirection.current == LayoutDirection.Ltr) 180f else 0f), LocalContentColor.current)
        Spacer(Modifier.width(4.dp))
        UiText(label)
    }
}
