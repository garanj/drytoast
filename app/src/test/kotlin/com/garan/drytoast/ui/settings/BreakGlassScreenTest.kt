package com.garan.drytoast.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.garan.drytoast.core.model.NfcTagEntry
import com.garan.drytoast.core.model.NfcTagRegistrationResult
import com.garan.drytoast.core.model.NfcTrigger
import com.garan.drytoast.core.model.ProfileTrigger
import com.garan.drytoast.core.model.SavedTrigger
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BreakGlassScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun registerCard_savesNormalizedUid_andRejectsCollisionWithProfileTrigger() {
        var savedUid: String? = null
        var activeListener: ((NfcTagRegistrationResult) -> Unit)? = null

        val profileNfcTrigger = SavedTrigger(
            id = "t-profile",
            name = "Desk Tag",
            trigger = ProfileTrigger.Nfc(
                NfcTrigger(tags = listOf(NfcTagEntry(uid = "04:AA:BB:CC", label = "Desk"))),
            ),
        )

        composeTestRule.setContent {
            DryToastTheme {
                BreakGlassScreen(
                    breakGlassTagUid = null,
                    breakGlassDurationMinutes = 30,
                    breakGlassUntilEpochMillis = null,
                    savedTriggers = listOf(profileNfcTrigger),
                    isSettingsLocked = false,
                    onSaveBreakGlassTagUid = { savedUid = it },
                    onUpdateDurationMinutes = {},
                    onEndBreakGlassEarly = {},
                    onSetNfcRegistrationListener = { activeListener = it },
                    onBack = {},
                )
            }
        }

        // Start scanning -> tap colliding UID -> error shown and UID not saved
        composeTestRule.onNodeWithTag(BreakGlassTestTags.REGISTER_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.SCANNING_PROMPT).assertIsDisplayed()
        composeTestRule.runOnIdle {
            activeListener?.invoke(NfcTagRegistrationResult.Success("04aabbcc"))
        }
        composeTestRule.onNodeWithTag(BreakGlassTestTags.ERROR_MESSAGE).assertIsDisplayed()
        assertThat(savedUid).isNull()

        // Start scanning again -> tap unique Break-Glass card -> normalized UID saved
        composeTestRule.onNodeWithTag(BreakGlassTestTags.REGISTER_BUTTON).performClick()
        composeTestRule.runOnIdle {
            activeListener?.invoke(NfcTagRegistrationResult.Success("04112233"))
        }
        composeTestRule.waitForIdle()
        assertThat(savedUid).isEqualTo("04:11:22:33")
    }

    @Test
    fun settingsLocked_disablesCardMutations_butAllowsRelockNowWhenActive() {
        var relocked = false
        val now = 1_000_000L

        composeTestRule.setContent {
            DryToastTheme {
                BreakGlassScreen(
                    breakGlassTagUid = "04:11:22:33",
                    breakGlassDurationMinutes = 30,
                    breakGlassUntilEpochMillis = now + 20 * 60_000L,
                    savedTriggers = emptyList(),
                    isSettingsLocked = true,
                    onSaveBreakGlassTagUid = {},
                    onUpdateDurationMinutes = {},
                    onEndBreakGlassEarly = { relocked = true },
                    onSetNfcRegistrationListener = {},
                    onBack = {},
                    nowEpochMillisProvider = { now },
                )
            }
        }

        composeTestRule.onNodeWithTag(BreakGlassTestTags.ACTIVE_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.RELOCK_NOW_BUTTON).performClick()
        assertThat(relocked).isTrue()

        composeTestRule.onNodeWithText("UID: 04:11:22:33").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.REGISTER_BUTTON).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.REMOVE_BUTTON).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.DURATION_OPTION_PREFIX + "60")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun scanningState_preservedAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeTestRule)

        restorationTester.setContent {
            DryToastTheme {
                BreakGlassScreen(
                    breakGlassTagUid = null,
                    breakGlassDurationMinutes = 15,
                    breakGlassUntilEpochMillis = null,
                    savedTriggers = emptyList(),
                    isSettingsLocked = false,
                    onSaveBreakGlassTagUid = {},
                    onUpdateDurationMinutes = {},
                    onEndBreakGlassEarly = {},
                    onSetNfcRegistrationListener = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(BreakGlassTestTags.REGISTER_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.SCANNING_PROMPT).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(BreakGlassTestTags.SCANNING_PROMPT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BreakGlassTestTags.CANCEL_SCAN_BUTTON).assertIsDisplayed()
    }
}
