package com.example.waferai.ui.dashboard

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.example.waferai.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioIcon

private val tabIcons = listOf(StudioIcon.WAFER, StudioIcon.DATA, StudioIcon.AI, StudioIcon.TOOLS, StudioIcon.PERSON)

@Composable
fun TabIcon(index: Int, color: Color) {
    val icon = tabIcons[index]
    val size = Modifier.size(22.dp)
    if (icon == StudioIcon.AI) {
        Icon(painterResource(R.drawable.ic_nav_ai), contentDescription = null, modifier = size, tint = color)
    } else {
        StudioGlyph(icon, size, color)
    }
}
