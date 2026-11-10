package com.example.waferai.ui.dashboard

import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioIcon

private val tabIcons = listOf(StudioIcon.WAFER, StudioIcon.DATA, StudioIcon.AI, StudioIcon.TOOLS, StudioIcon.PERSON)

@Composable
fun TabIcon(index: Int, color: Color) {
    StudioGlyph(tabIcons[index], Modifier.size(28.dp), color, navigation = true)
}
