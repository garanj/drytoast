package com.garan.threshold.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.garan.threshold.core.model.GeneralSettings
import com.garan.threshold.ui.theme.ThresholdTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThresholdSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requireRebootToggle_showsConfirmationDialogWhenEnablingFromOff() {
        var updatedValue: Boolean? = null

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(
                        general = GeneralSettings(requireRebootForSettingsChange = false),
                        isRequireRebootActiveOnLive = false,
                        hasPendingRebootChanges = false,
                        isLoading = false,
                    ),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onUpdateRequireReboot = { updatedValue = it },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.TOGGLE_REQUIRE_REBOOT))
        composeRule.onNodeWithTag(SettingsTestTags.TOGGLE_REQUIRE_REBOOT)
            .performClick()

        // Should show confirmation dialog before invoking callback
        assertThat(updatedValue).isNull()
        composeRule.onNodeWithTag(SettingsTestTags.CONFIRM_REQUIRE_REBOOT_DIALOG)
            .assertIsDisplayed()

        // Confirming enables the setting
        composeRule.onNodeWithTag(SettingsTestTags.CONFIRM_REQUIRE_REBOOT_BUTTON)
            .performClick()
        assertThat(updatedValue).isTrue()
    }

    @Test
    fun pendingRebootBanner_displaysWhenPendingChangesExistAndInvokesDiscard() {
        var discardClicked = false

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(
                        general = GeneralSettings(requireRebootForSettingsChange = false),
                        isRequireRebootActiveOnLive = true,
                        hasPendingRebootChanges = true,
                        isLoading = false,
                    ),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onDiscardPendingChanges = { discardClicked = true },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.PENDING_REBOOT_BANNER)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.DISCARD_PENDING_CHANGES_BUTTON)
            .performClick()

        assertThat(discardClicked).isTrue()
    }
}
