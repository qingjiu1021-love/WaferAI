package com.example.waferai.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class StartupAndTabsBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val target = "com.example.waferai"

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = target,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Full(),
        setupBlock = { startActivityAndWait(); waitForTabs(); pressHome() }
    ) {
        startActivityAndWait()
        waitForTabs()
    }

    @Test fun primaryTabSwitches() = benchmark.measureRepeated(
        packageName = target,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForTabs()
            // Let the app finish warming its off-screen tabs before measured navigation.
            Thread.sleep(2000)
        }
    ) {
        repeat(2) {
            listOf("AI", "数据|Data", "工具|Tools", "晶圆|Wafer").forEach { label ->
                val selector = By.text(Pattern.compile("^(?:$label)$"))
                val node = device.wait(Until.findObject(selector), 5000) ?: error("Missing tab: $label")
                node.click()
                Thread.sleep(700)
            }
        }
    }

    private fun MacrobenchmarkScope.waitForTabs() {
        check(device.wait(Until.hasObject(By.text(Pattern.compile("^(工具|Tools)$"))), 15000)) {
            "Main navigation did not appear"
        }
    }
}


