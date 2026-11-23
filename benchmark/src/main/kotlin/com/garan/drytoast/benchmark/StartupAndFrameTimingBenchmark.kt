package com.garan.drytoast.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark suite measuring Dry Toast cold/warm startup times and 120Hz frame timing
 * across the home canvas and search surface.
 */
@RunWith(AndroidJUnit4::class)
class StartupAndFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() {
        benchmarkRule.measureRepeated(
            packageName = BaselineProfileGenerator.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun startupWarm() {
        benchmarkRule.measureRepeated(
            packageName = BaselineProfileGenerator.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun searchActivationAndFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = BaselineProfileGenerator.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
        ) {
            startActivityAndWait()
            val searchPrompt = device.wait(Until.findObject(By.res("search_prompt_bar")), 2_000L)
            if (searchPrompt != null) {
                searchPrompt.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }
        }
    }
}
