package com.example.waferai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bottomTabKeepsItsPositionAndSelectionWhenOpeningAndLeavingSettings() {
        val profileLabel = hasText("我的") or hasText("Me")
        val tab = compose.onNode(profileLabel and hasClickAction())
        compose.waitUntil(12_000) { compose.onAllNodes(profileLabel and hasClickAction()).fetchSemanticsNodes().size == 1 }
        tab.performClick()
        val selected = compose.onNode(profileLabel and isSelected())
        val initialBounds = selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        compose.onNode((hasText("主题") or hasText("Theme")) and hasClickAction()).performClick()
        assertEquals(initialBounds, selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(initialBounds, selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)

        compose.onNode((hasText("语言选择") or hasText("Language")) and hasClickAction()).performClick()
        assertEquals(initialBounds, selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNode(hasText("返回") or hasText("Back")).performClick()
        assertEquals(initialBounds, selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }
}
