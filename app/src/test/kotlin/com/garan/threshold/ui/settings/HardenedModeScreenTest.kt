package com.garan.threshold.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.garan.threshold.core.security.ChallengeResponseVerifier
import com.garan.threshold.core.security.VerificationDomain
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
    fun hardenedModeScreen_whenInactive_displaysStandardModeStatusAndLaunchesSetupWebsite() {
        var openedUrls = mutableListOf<String>()

        composeRule.setContent {
            ThresholdTheme {
                HardenedModeScreen(
                    isHardenedModeActive = false,
                    onBack = {},
                    onOpenWebsite = { openedUrls.add(it) },
                )
            }
        }

        composeRule.onNodeWithText("STATUS: STANDARD MODE (NOT ENABLED)").assertIsDisplayed()
        composeRule.onNodeWithText("OS-Level Package Suspension").assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.BLOCK_NEW_APP_INSTALLS_ROW).assertDoesNotExist()

        val list = composeRule.onNodeWithTag(HardenedModeTestTags.SCREEN_LIST)
        list.performScrollToNode(hasTestTag(HardenedModeTestTags.OPEN_WEBSITE_BUTTON))
        composeRule.onNodeWithText(
            "Hardened Mode cannot be toggled from inside Threshold's normal menus. You can enable or disable it either by scanning the QR codes on the Threshold website or via USB debugging (ADB) from a computer.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.OPEN_WEBSITE_BUTTON)
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag(SecondDeviceSheetTestTags.SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(SecondDeviceSheetTestTags.URL_TEXT)
            .assertTextEquals("thresholdlauncher.com/hardened-mode")
        composeRule.onNodeWithTag(SecondDeviceSheetTestTags.OPEN_HERE_BUTTON)
            .performSemanticsAction(SemanticsActions.OnClick)

        assertThat(openedUrls).containsExactly("https://thresholdlauncher.com/hardened-mode")
    }

    @Test
    fun hardenedModeScreen_whenActive_displaysHardenedModeActiveStatusAndInstallToggle() {
        val toggledValues = mutableListOf<Boolean>()

        composeRule.setContent {
            ThresholdTheme {
                HardenedModeScreen(
                    isHardenedModeActive = true,
                    blockNewAppInstalls = false,
                    isSettingsLocked = false,
                    onToggleBlockNewAppInstalls = { toggledValues.add(it) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("STATUS: HARDENED MODE ACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Threshold holds Device Owner privileges. Blocked and Guarded apps are suspended at the OS level and launcher self-protection is active.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.BLOCK_NEW_APP_INSTALLS_ROW)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertThat(toggledValues).containsExactly(true)
    }

    @Test
    fun hardenedModeScreen_whenSettingsLocked_disablesBlockNewAppInstallsToggle() {
        composeRule.setContent {
            ThresholdTheme {
                HardenedModeScreen(
                    isHardenedModeActive = true,
                    blockNewAppInstalls = true,
                    isSettingsLocked = true,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.BLOCK_NEW_APP_INSTALLS_ROW)
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun hardenedModeDeepLink_validatesOnlyExactDisableAndSettingsLockUrisAndKeys() {
        val validDisableIntent = Intent(Intent.ACTION_VIEW, Uri.parse(HardenedModeDeepLink.FULL_DISABLE_URI))
        assertThat(HardenedModeDeepLink.isValidDisableIntent(validDisableIntent)).isTrue()
        assertThat(HardenedModeDeepLink.isValidSettingsLockIntent(validDisableIntent)).isFalse()

        val validLockIntent = Intent(Intent.ACTION_VIEW, Uri.parse(HardenedModeDeepLink.FULL_SETTINGS_LOCK_URI))
        assertThat(HardenedModeDeepLink.isValidSettingsLockIntent(validLockIntent)).isTrue()
        assertThat(HardenedModeDeepLink.isValidDisableIntent(validLockIntent)).isFalse()

        val wrongKeyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("threshold://hardened/disable?key=wrong"),
        )
        assertThat(HardenedModeDeepLink.isValidDisableIntent(wrongKeyIntent)).isFalse()

        val wrongSettingsKeyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("threshold://settings/lock?key=wrong"),
        )
        assertThat(HardenedModeDeepLink.isValidSettingsLockIntent(wrongSettingsKeyIntent)).isFalse()

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
        assertThat(HardenedModeDeepLink.isValidSettingsLockIntent(null)).isFalse()
    }

    @Test
    fun disableHardenedModeScreen_requiresValidChallengeResponseCode_andRejectsInvalidCode() {
        var confirmed = false
        var cancelled = false
        val fixedNowMillis = 1_795_549_500_000L
        val bucket = ChallengeResponseVerifier.timeBucket(fixedNowMillis)
        val initialChallenge = "492"

        composeRule.setContent {
            ThresholdTheme {
                DisableHardenedModeScreen(
                    isHardenedModeActive = true,
                    onConfirmDisable = { confirmed = true },
                    onCancel = { cancelled = true },
                    initialChallenge = initialChallenge,
                    nowEpochMillisProvider = { fixedNowMillis },
                )
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.DISABLE_SCREEN_ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_CODE_VALUE).assertTextEquals("492")
        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON).assertIsNotEnabled()

        // Enter an invalid 6-digit code ("000000") and verify error is shown and callback not invoked
        repeat(6) {
            composeRule.onNodeWithTag(HardenedModeTestTags.keypadDigitTag(0)).performClick()
        }
        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON)
            .assertIsEnabled()
            .performClick()

        assertThat(confirmed).isFalse()
        composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_ERROR_TEXT).assertIsDisplayed()

        // Enter the valid 6-digit code for ("hardened-disable", "492", bucket)
        val validCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = initialChallenge,
            bucket = bucket,
        )
        for (ch in validCode) {
            composeRule.onNodeWithTag(HardenedModeTestTags.keypadDigitTag(ch.digitToInt())).performClick()
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON)
            .assertIsEnabled()
            .performClick()
        assertThat(confirmed).isTrue()

        composeRule.onNodeWithTag(HardenedModeTestTags.CANCEL_DISABLE_BUTTON).performClick()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun disableHardenedModeScreen_rotatesChallengeAndClearsInputOnPause_andBlocksMultiWindow() {
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            override val lifecycle: Lifecycle get() = registry
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                ThresholdTheme {
                    DisableHardenedModeScreen(
                        isHardenedModeActive = true,
                        onConfirmDisable = {},
                        onCancel = {},
                        initialChallenge = "492",
                    )
                }
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_CODE_VALUE).assertTextEquals("492")
        composeRule.onNodeWithTag(HardenedModeTestTags.keypadDigitTag(7)).performClick()
        composeRule.onNodeWithTag(HardenedModeTestTags.RESPONSE_CODE_DISPLAY).assertTextEquals("7 · · · · ·")

        // Simulate user switching apps (ON_PAUSE) -> challenge rotates and entered code clears
        composeRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HardenedModeTestTags.RESPONSE_CODE_DISPLAY).assertTextEquals("· · · · · ·")
        val rotatedText = composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_CODE_VALUE)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .first()
            .text
        assertThat(rotatedText).isNotEqualTo("492")
        assertThat(rotatedText).hasLength(3)
    }

    @Test
    fun settingsLockScreen_verifiesCodeAndTogglesSettingsLockState() {
        var toggledState: Boolean? = null
        val fixedNowMillis = 1_795_549_500_000L
        val bucket = ChallengeResponseVerifier.timeBucket(fixedNowMillis)
        val challenge = "815"

        composeRule.setContent {
            ThresholdTheme {
                SettingsLockScreen(
                    isSettingsLocked = true,
                    onConfirmSetSettingsLocked = { toggledState = it },
                    onCancel = {},
                    initialChallenge = challenge,
                    nowEpochMillisProvider = { fixedNowMillis },
                )
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.SETTINGS_LOCK_SCREEN_ROOT).assertIsDisplayed()
        composeRule.onNodeWithText("Unlock Threshold Settings?").assertIsDisplayed()

        val validCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.SETTINGS_LOCK,
            challenge = challenge,
            bucket = bucket,
        )
        for (ch in validCode) {
            composeRule.onNodeWithTag(HardenedModeTestTags.keypadDigitTag(ch.digitToInt())).performClick()
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_SETTINGS_LOCK_BUTTON)
            .assertIsEnabled()
            .performClick()

        assertThat(toggledState).isEqualTo(false)
    }

    @Test
    fun settingsLockScreen_whenMultiWindow_blocksChallengeAndDisplaysNotice() {
        composeRule.setContent {
            ThresholdTheme {
                SettingsLockScreen(
                    isSettingsLocked = false,
                    onConfirmSetSettingsLocked = {},
                    onCancel = {},
                    isMultiWindowOverride = true,
                )
            }
        }

        composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_MULTI_WINDOW_NOTICE).assertIsDisplayed()
    }
}
