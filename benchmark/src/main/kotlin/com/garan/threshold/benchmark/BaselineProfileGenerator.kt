package com.garan.threshold.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()

            // Exercise search surface activation and zero-allocation fuzzy search path
            val searchPrompt = device.wait(Until.findObject(By.res("search_prompt_bar")), 2_000L)
            if (searchPrompt != null) {
                searchPrompt.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.garan.threshold"
    }
}
