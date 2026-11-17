package com.example.waferai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.example.waferai.ui.settings.AppearanceScreen
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.BottomBarStyle
import com.example.waferai.ui.theme.GlassLevel
import com.example.waferai.ui.theme.WaferAITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationTransparencyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sliderExposesFullRangeEvenWithFixedNavigationAndNoGlass() {
        var appearance by mutableStateOf(Appearance(bottomBar = BottomBarStyle.FIXED, glass = GlassLevel.NONE))
        compose.setContent {
            WaferAITheme(appearance) {
                AppearanceScreen(appearance, { appearance = it }, { true }, true, {})
            }
        }
        val slider = compose.onNodeWithContentDescription("选项卡透明度").performScrollTo()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.runOnIdle { assertEquals(0, appearance.navOpacity) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(100, appearance.navOpacity) }
    }

    @Test fun panelSliderIsIndependentOfNavigation() {
        var appearance by mutableStateOf(Appearance(navOpacity = 37))
        compose.setContent {
            WaferAITheme(appearance) {
                AppearanceScreen(appearance, { appearance = it }, { true }, true, {})
            }
        }
        val slider = compose.onNodeWithContentDescription("页面选项框透明度").performScrollTo()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.runOnIdle { assertEquals(0, appearance.panelOpacity); assertEquals(37, appearance.navOpacity) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(100, appearance.panelOpacity); assertEquals(37, appearance.navOpacity) }
    }
}
