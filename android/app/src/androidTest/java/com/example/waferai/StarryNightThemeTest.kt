package com.example.waferai

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.ui.theme.Appearance
import com.example.waferai.ui.theme.AppearancePreferences
import com.example.waferai.ui.theme.StarryNightBase
import com.example.waferai.ui.theme.StarryNightCard
import com.example.waferai.ui.theme.ThemeAccent
import com.example.waferai.ui.theme.ThemeMode
import com.example.waferai.ui.theme.WaferAITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StarryNightThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultsRenderMidnightBlueAndExistingThemeSurvivesUntilReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "starry-night-qa-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            val defaults = AppearancePreferences(isolated).load()
            assertEquals(ThemeMode.DARK, defaults.mode)
            assertEquals(ThemeAccent.STARRY_NIGHT, defaults.accent)
            val previous = defaults.copy(mode = ThemeMode.LIGHT, accent = ThemeAccent.BLUE)
            AppearancePreferences(isolated).save(previous)
            assertEquals(previous, AppearancePreferences(isolated).load())
            AppearancePreferences(isolated).save(Appearance())
            assertEquals(defaults, AppearancePreferences(isolated).load())

            var background = Color.Unspecified
            var surface = Color.Unspecified
            compose.setContent {
                WaferAITheme(defaults) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        background = scheme.background
                        surface = scheme.surfaceContainer
                    }
                }
            }
            compose.runOnIdle {
                assertEquals(StarryNightBase, background)
                assertEquals(StarryNightCard, surface)
            }
        } finally {
            context.deleteSharedPreferences("$prefix-appearance")
        }
    }
}
