package com.example.waferai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.PrimaryTabHost
import com.example.waferai.ui.theme.TabNavigation
import com.example.waferai.ui.theme.TabMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrimaryTabHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closedAiDrawerNeverDrawsOverDataDuringSlide() {
        val route = mutableStateOf("AI")
        lateinit var navigation: TabNavigation<String>
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { TabNavigation(route, listOf("Data", "AI"), scope) }
            navigation = controller
            PrimaryTabHost(controller, TabMotion.SLIDE, prepareTabs = false, Modifier.fillMaxSize().testTag("tab-host")) { tab ->
                if (tab == "AI") {
                    ModalNavigationDrawer(
                        drawerContent = { Box(Modifier.width(120.dp).fillMaxHeight().background(Color.Magenta)) },
                        gesturesEnabled = false
                    ) { Box(Modifier.fillMaxSize().background(Color.Red)) }
                } else {
                    // The data page uses the host background between its cards.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.width(24.dp).fillMaxHeight().background(Color.Blue))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { navigation.navigate("Data", animate = true) }
            compose.mainClock.advanceTimeBy(112)
            val pixels = compose.onNodeWithTag("tab-host").captureToImage().toPixelMap()
            val colors = (0 until pixels.width).map { x -> pixels[x, pixels.height / 2].toArgb() }.toSet()
            assertTrue("Both tabs must be visible during the check", Color.Red.toArgb() in colors && Color.Blue.toArgb() in colors)
            assertFalse("Offscreen drawer leaked onto the data tab", Color.Magenta.toArgb() in colors)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

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
