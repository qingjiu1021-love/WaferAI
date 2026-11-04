package com.example.waferai

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.waferai.data.remote.WaferRecord
import com.example.waferai.ui.history.CsvUiState
import com.example.waferai.ui.history.HistoryScreen
import com.example.waferai.ui.history.HistoryUiState
import com.example.waferai.ui.theme.WaferAITheme
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromDetailsKeepsTheScrolledRecordAndChangingSearchResetsTheList() {
        val history = mutableStateOf(HistoryUiState(records = (1..45).map { id ->
            WaferRecord(id, "QA", "W$id", "wafer-$id.json", 100, 0.95, "2026-11-04T00:00:00Z")
        }, hasMore = false))
        compose.setContent {
            WaferAITheme {
                var opened by remember { mutableStateOf(false) }
                val saved = rememberSaveableStateHolder()
                if (opened) {
                    TextButton({ opened = false }) { Text("返回列表") }
                } else saved.SaveableStateProvider("history") {
                    HistoryScreen(history.value, onBack = {}, onRefresh = {}, onLoadMore = {},
                        onOpen = { opened = true }, onSearch = {}, onOrder = {}, onToggleBelow = {},
                        onThreshold = {}, csv = CsvUiState(), onExportCsv = {}, onCompare = { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("history-records").performScrollToNode(hasText("W35"))
        compose.onNodeWithText("W35").performClick()
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithText("W35").assertIsDisplayed()
        compose.runOnIdle { history.value = history.value.copy(query = "new query") }
        compose.onNodeWithText("对照晶圆").assertIsDisplayed()
    }
}
