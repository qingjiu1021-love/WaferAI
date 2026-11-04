package com.example.waferai

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.waferai.ui.theme.StartupTransition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeInitializesBeforeSystemReleaseAndSurvivesTheTransition() {
        val ready = mutableStateOf(false)
        var initializations = 0
        var completions = 0
        val completionTimes = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupTransition(true, ready.value, { completions++; completionTimes += compose.mainClock.currentTime }) {
                remember { initializations++ }
                Text("Existing home")
            }
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("WaferAI").assertIsDisplayed()
        compose.onNodeWithText("Existing home").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, initializations)
            assertEquals(0, completions)
            ready.value = true
        }
        compose.mainClock.advanceTimeBy(2400)
        compose.runOnIdle {
            assertEquals("Finished before 2.5 seconds at $completionTimes", 0, completions)
        }
        compose.onNodeWithContentDescription("WaferAI").assertExists()
        compose.onNodeWithText("Existing home").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, completions) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("WaferAI").assertDoesNotExist()
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, initializations)
            assertEquals(1, completions)
        }
    }

    @Test fun accessibleSkipRevealsHomeImmediately() {
        var completions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupTransition(true, true, { completions++ }) { Text("Existing home") }
        }
        compose.onNodeWithContentDescription("WaferAI").assertHasClickAction().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, completions) }
        compose.mainClock.advanceTimeBy(3000)
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun disabledLaunchShowsHomeWithoutWaitingForSystemCallback() {
        compose.setContent {
            StartupTransition(false, false, {}) { Text("Existing home") }
        }
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.onNodeWithContentDescription("WaferAI").assertDoesNotExist()
    }
}
