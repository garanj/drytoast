package com.garan.threshold.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.GeneralSettings
import com.garan.threshold.core.model.InstalledApp
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

    @Test
    fun setPureBlackWallpaperAction_showsConfirmationDialogAndInvokesCallbackOnConfirm() {
        var appliedWallpaper = false

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(isLoading = false),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onApplyPureBlackWallpaper = { appliedWallpaper = true },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.SET_BLACK_WALLPAPER_ACTION))
        composeRule.onNodeWithTag(SettingsTestTags.SET_BLACK_WALLPAPER_ACTION)
            .performClick()

        assertThat(appliedWallpaper).isFalse()
        composeRule.onNodeWithTag(SettingsTestTags.CONFIRM_BLACK_WALLPAPER_DIALOG)
            .assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTestTags.CONFIRM_BLACK_WALLPAPER_BUTTON)
            .performClick()
        assertThat(appliedWallpaper).isTrue()
    }

    @Test
    fun feedbackButton_displaysAtBottomOfSettingsAndInvokesCallback() {
        var feedbackClicked = false

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(isLoading = false),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onSendFeedback = { feedbackClicked = true },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.FEEDBACK_BUTTON))
        composeRule.onNodeWithTag(SettingsTestTags.FEEDBACK_BUTTON)
            .assertIsDisplayed()
            .performClick()

        assertThat(feedbackClicked).isTrue()
    }

    @Test
    fun showDialerIconToggle_displaysDefaultCheckedAndInvokesCallback() {
        var updatedValue: Boolean? = null

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(
                        general = GeneralSettings(showDialerIcon = true),
                        isLoading = false,
                    ),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateShowDialerIcon = { updatedValue = it },
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.TOGGLE_SHOW_DIALER_ICON))
        composeRule.onNodeWithTag(SettingsTestTags.TOGGLE_SHOW_DIALER_ICON)
            .assertIsDisplayed()
            .performClick()

        assertThat(updatedValue).isFalse()
    }

    @Test
    fun feedbackEmailIntentFactory_createsMailtoIntentWithExpectedRecipientSubjectAndBody() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = FeedbackEmailIntentFactory.createFeedbackEmailIntent(
            context = context,
            appVersion = "0.1.0",
            androidVersion = "17 (SDK 37)",
            deviceModel = "Google Pixel 9a",
        )

        assertThat(intent.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.dataString).isEqualTo("mailto:")
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
            .containsExactly("plop@plop.com")
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT))
            .isEqualTo("Threshold feedback")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(
            """
            App version: 0.1.0
            Android version: 17 (SDK 37)
            Device: Google Pixel 9a

            Add your feedback here.
            """.trimIndent(),
        )
    }

    @Test
    fun quickActions_renderPersistedLabelImmediatelyWithoutAvailableAppsLoaded() {
        val chromeKey = ComponentKey("com.android.chrome", "Main")
        val chromeApp = InstalledApp(chromeKey, "Chrome")

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(
                        baseProfile = BaseProfileSettings(quickActions = listOf(chromeApp)),
                        availableApps = kotlinx.collections.immutable.persistentListOf(),
                        isLoading = true,
                    ),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeRule.onNodeWithText("com.android.chrome").assertDoesNotExist()
    }

    @Test
    fun hardenedModeRow_displaysStatusSummaryAndNavigatesOnClick() {
        var navigatedToHardenedMode = false

        composeRule.setContent {
            ThresholdTheme {
                ThresholdSettingsScreen(
                    uiState = SettingsUiState(
                        isHardenedModeActive = true,
                        isLoading = false,
                    ),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onNavigateToHardenedMode = { navigatedToHardenedMode = true },
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.NAV_HARDENED_MODE))
        composeRule.onNodeWithText("Active · Kernel-level enforcement")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.NAV_HARDENED_MODE)
            .performClick()

        assertThat(navigatedToHardenedMode).isTrue()
    }
}
