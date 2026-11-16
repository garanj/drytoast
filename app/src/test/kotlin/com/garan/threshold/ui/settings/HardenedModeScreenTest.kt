package com.garan.threshold.ui.settings

import android.content.Intent
import android.net.Uri
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

        // Scroll to prerequisite warning and verify "new devices only" guidance is shown
        val list = composeRule.onNodeWithTag(HardenedModeTestTags.SCREEN_LIST)
        list.performScrollToNode(hasTestTag(HardenedModeTestTags.COPY_ENABLE_COMMAND))
        composeRule.onNodeWithText("Recommended Only for New or Freshly Reset Devices")
            .assertIsDisplayed()

        // Scroll to and click each ADB command Copy button

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

    @Test
    fun hardenedModeDeepLink_validatesOnlyExactDisableUriAndKey() {
        val validIntent = Intent(Intent.ACTION_VIEW, Uri.parse(HardenedModeDeepLink.FULL_DISABLE_URI))
        assertThat(HardenedModeDeepLink.isValidDisableIntent(validIntent)).isTrue()

        val wrongKeyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("threshold://hardened/disable?key=wrong"),
        )
        assertThat(HardenedModeDeepLink.isValidDisableIntent(wrongKeyIntent)).isFalse()

        val missingKeyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("threshold://hardened/disable"),
        )
        assertThat(HardenedModeDeepLink.isValidDisableIntent(missingKeyIntent)).isFalse()

        val wrongActionIntent = Intent(
            Intent.ACTION_MAIN,
            Uri.parse(HardenedModeDeepLink.FULL_DISABLE_URI),
        )
        assertThat(HardenedModeDeepLink.isValidDisableIntent(wrongActionIntent)).isFalse()

        assertThat(HardenedModeDeepLink.isValidDisableIntent(null)).isFalse()
    }

    @Test
    fun disableHardenedModeScreen_whenActive_invokesConfirmAndCancelCallbacks() {
        var confirmed = false
        var cancelled = false

        composeRule.setContent {
            ThresholdTheme {
                DisableHardenedModeScreen(
                    isHardenedModeActive = true,
                    onConfirmDisable = { confirmed = true },
                    onCancel = { cancelled = true },
                )
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.DISABLE_SCREEN_ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON).performClick()
        assertThat(confirmed).isTrue()

        composeRule.onNodeWithTag(HardenedModeTestTags.CANCEL_DISABLE_BUTTON).performClick()
        assertThat(cancelled).isTrue()
    }
}
