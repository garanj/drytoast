package com.garan.threshold.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.garan.threshold.ui.theme.ThresholdTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardenedModeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hardenedModeScreen_whenInactive_displaysStandardModeStatusAndCopiesAllAdbCommands() {
        val copiedCommands = mutableListOf<String>()

        composeRule.setContent {
            ThresholdTheme {
                HardenedModeScreen(
                    isHardenedModeActive = false,
                    onBack = {},
                    onCopyCommand = { copiedCommands.add(it) },
                )
            }
        }

        composeRule.onNodeWithText("STATUS: STANDARD MODE (NOT ENABLED)").assertIsDisplayed()
        composeRule.onNodeWithText("OS-Level Package Suspension").assertIsDisplayed()

        // Scroll to and click each ADB command Copy button
        val list = composeRule.onNodeWithTag(HardenedModeTestTags.SCREEN_LIST)

        list.performScrollToNode(hasTestTag(HardenedModeTestTags.COPY_ENABLE_COMMAND))
        composeRule.onNodeWithTag(HardenedModeTestTags.COPY_ENABLE_COMMAND).performClick()

        list.performScrollToNode(hasTestTag(HardenedModeTestTags.COPY_DISABLE_COMMAND))
        composeRule.onNodeWithTag(HardenedModeTestTags.COPY_DISABLE_COMMAND).performClick()

        list.performScrollToNode(hasTestTag(HardenedModeTestTags.COPY_LOCK_SETTINGS_COMMAND))
        composeRule.onNodeWithTag(HardenedModeTestTags.COPY_LOCK_SETTINGS_COMMAND).performClick()

        list.performScrollToNode(hasTestTag(HardenedModeTestTags.COPY_UNLOCK_SETTINGS_COMMAND))
        composeRule.onNodeWithTag(HardenedModeTestTags.COPY_UNLOCK_SETTINGS_COMMAND).performClick()

        assertThat(copiedCommands).containsExactly(
            "adb shell dpm set-device-owner com.garan.threshold/.dpc.ThresholdDeviceAdminReceiver",
            "adb shell am broadcast -a com.garan.threshold.action.DISABLE_HARDENED_MODE -n com.garan.threshold/.settings.SettingsLockReceiver",
            "adb shell am broadcast -a com.garan.threshold.action.LOCK_SETTINGS -n com.garan.threshold/.settings.SettingsLockReceiver",
            "adb shell am broadcast -a com.garan.threshold.action.UNLOCK_SETTINGS -n com.garan.threshold/.settings.SettingsLockReceiver",
        ).inOrder()
    }

    @Test
    fun hardenedModeScreen_whenActive_displaysHardenedModeActiveStatus() {
        composeRule.setContent {
            ThresholdTheme {
                HardenedModeScreen(
                    isHardenedModeActive = true,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("STATUS: HARDENED MODE ACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Threshold holds Device Owner privileges. Blocked apps are suspended at the OS level and launcher self-protection is active.",
        ).assertIsDisplayed()
    }
}
