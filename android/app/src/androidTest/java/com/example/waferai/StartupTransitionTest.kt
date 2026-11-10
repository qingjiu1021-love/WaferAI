package com.example.waferai

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.waferai.ui.theme.StartupTransition
import com.example.waferai.ui.theme.WaferSplashConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coldStartCompletesBadgeBeforeRevealingHome() {
        var initializations = 0
        var completions = 0
        val completionTimes = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupTransition(true, { completions++; completionTimes += compose.mainClock.currentTime }) {
                remember { initializations++ }
                Text("Existing home")
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("WaferAI").assertIsDisplayed()
        compose.onNodeWithText("Existing home").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, initializations)
            assertEquals(0, completions)
        }
        compose.mainClock.advanceTimeBy(WaferSplashConfig.totalMillis.toLong() - 200)
        compose.runOnIdle {
            assertEquals("Finished before the drawing ended at $completionTimes", 0, completions)
            assertEquals(1, initializations)
        }
        compose.onNodeWithContentDescription("WaferAI").assertExists()
        compose.onNodeWithText("Existing home").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, completions) }
        compose.mainClock.advanceTimeBy(220)
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
            StartupTransition(true, { completions++ }) { Text("Existing home") }
        }
        compose.onNodeWithContentDescription("WaferAI").assertHasClickAction().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, completions) }
        compose.mainClock.advanceTimeBy(WaferSplashConfig.totalMillis.toLong())
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun reducedMotionCompletesAfterThreeHundredMilliseconds() {
        var completions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupTransition(true, { completions++ }, reducedMotion = true) { Text("Existing home") }
        }
        compose.mainClock.advanceTimeBy(230)
        compose.onNodeWithContentDescription("WaferAI").assertIsDisplayed()
        compose.onNodeWithText("Existing home").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithContentDescription("WaferAI").assertDoesNotExist()
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun disabledLaunchShowsHomeImmediately() {
        compose.setContent {
            StartupTransition(false, {}) { Text("Existing home") }
        }
        compose.onNodeWithText("Existing home").assertIsDisplayed()
        compose.onNodeWithContentDescription("WaferAI").assertDoesNotExist()
    }
}

