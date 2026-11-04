package com.example.waferai

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.ui.importdata.WaferExplorer
import com.example.waferai.ui.theme.WaferAITheme
import org.junit.Rule
import org.junit.Test

class WaferExplorerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinchingKeepsCenterDieSelectableAndCanReset() {
        val map = List(5) { row -> List(5) { column -> if (row == 2 && column == 2) 2 else 1 } }
        val wafer = WaferDetail(1, "L", "W", "test.json", 5, 5, 24, 1, 25, 0.96, map)
        compose.setContent {
            WaferAITheme {
                Column { WaferExplorer(wafer, WaferMapFilter.ALL) {} }
            }
        }

        val canvas = compose.onNodeWithContentDescription(
            "晶圆图，5 行 5 列。可双指缩放，放大后拖动，点击查看芯片状态。"
        )
        canvas.performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.10f, 0f),
                end0 = center - Offset(width * 0.30f, 0f),
                start1 = center + Offset(width * 0.10f, 0f),
                end1 = center + Offset(width * 0.30f, 0f)
            )
        }
        compose.onNodeWithText("100%").assertDoesNotExist()
        canvas.performTouchInput { click(center) }
        compose.onNodeWithText("第 3 行 · 第 3 列 · 不合格").assertIsDisplayed()
        compose.onNodeWithText("还原视图").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
    }
}
