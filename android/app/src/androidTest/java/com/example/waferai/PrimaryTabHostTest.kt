package com.example.waferai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import com.example.waferai.ui.theme.PrimaryTabHost
import com.example.waferai.ui.theme.TabNavigation
import com.example.waferai.ui.theme.TabMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrimaryTabHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptedSwitchShowsLatestTabAndKeepsPriorPageState() {
        val route = mutableStateOf("A")
        val lifecycle = mutableListOf<String>()
        lateinit var navigation: TabNavigation<String>
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { TabNavigation(route, listOf("A", "B", "C"), scope) }
            navigation = controller
            PrimaryTabHost(controller, TabMotion.SLIDE, prepareTabs = false, Modifier.fillMaxSize()) { tab ->
                var count by remember { mutableIntStateOf(0) }
                DisposableEffect(tab) {
                    lifecycle += "+$tab"
                    onDispose { lifecycle += "-$tab" }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { count++ }) { Text("$tab $count") }
                }
            }
        }
        compose.onNodeWithText("A 0").performClick()
        compose.onNodeWithText("A 1").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { navigation.navigate("B", animate = true) }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle { navigation.navigate("C", animate = true) }
            compose.mainClock.advanceTimeBy(750)
            compose.onNodeWithText("C 0").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(navigation.moving.value)
                navigation.navigate("A", animate = true)
            }
            compose.mainClock.advanceTimeBy(750)
            compose.runOnIdle { assertFalse(navigation.moving.value) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertTrue("track=${navigation.track}, position=${navigation.position}, lifecycle=$lifecycle, " +
            compose.onRoot(useUnmergedTree = true).printToString(), compose.onNodeWithText("A 1").isDisplayed())
    }
}
