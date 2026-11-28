package com.garan.drytoast.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.InstalledApp
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigureAppsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val chromeKey = ComponentKey("com.android.chrome", "Main")
    private val clockKey = ComponentKey("com.google.android.deskclock", "DeskClock")
    private val socialKey = ComponentKey("com.instagram.android", "MainActivity")

    private val apps = persistentListOf(
        InstalledApp(chromeKey, "Chrome"),
        InstalledApp(clockKey, "Clock"),
        InstalledApp(socialKey, "Instagram"),
    )

    @Test
    fun initialCountsAndAppsDisplayedCorrectly() {
        val initial = mapOf(
            chromeKey to AppAssignment.Guarded(),
            socialKey to AppAssignment.Blocked,
        )

        composeTestRule.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = initial,
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = {},
                    onBack = {},
                )
            }
        }

        // Header counts
        composeTestRule.onNodeWithText("ALL (3)").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLOCKED (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("GUARDED (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("ALLOW (1)").assertIsDisplayed()

        // All 3 apps visible in ALL filter
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clock").assertIsDisplayed()
        composeTestRule.onNodeWithText("Instagram").assertIsDisplayed()
    }

    @Test
    fun customProfileModeDisplaysAllAppNamesAndOverrideControls() {
        val initial = mapOf(
            chromeKey to AppAssignment.Standard,
            socialKey to AppAssignment.Blocked,
        )

        composeTestRule.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = initial,
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = {},
                    onBack = {},
                    isCustomProfile = true,
                    customProfileName = "Deep Work Overrides",
                )
            }
        }

        composeTestRule.onNodeWithText("Deep Work Overrides").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEFAULT (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("configure_apps_list").performScrollToNode(hasText("Instagram"))
        composeTestRule.onNodeWithText("Instagram").assertIsDisplayed()
    }

    @Test
    fun customProfileMode_defaultRuleSelector_updatesRuleAndDescription() {
        var selectedRule by mutableStateOf(com.garan.drytoast.core.model.ProfileDefaultRule.FALLTHROUGH)
        composeTestRule.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = emptyMap(),
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = {},
                    onBack = {},
                    isCustomProfile = true,
                    customProfileName = "Bedtime",
                    profileDefaultRule = selectedRule,
                    onUpdateProfileDefaultRule = { selectedRule = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_default_rule_BLOCK_ALL").performClick()
        composeTestRule.waitForIdle()
        assertThat(selectedRule).isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.BLOCK_ALL)
        composeTestRule.onNodeWithText("Unspecified apps are blocked (your phone dialer is always allowed).")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("profile_default_rule_ALLOW_ALL").performClick()
        composeTestRule.waitForIdle()
        assertThat(selectedRule).isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.ALLOW_ALL)

        composeTestRule.onNodeWithTag("profile_default_rule_GUARD_ALL").performClick()
        composeTestRule.waitForIdle()
        assertThat(selectedRule).isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.GUARD_ALL)
    }

    @Test
    fun filterHeaderFiltersAppsList() {
        val initial = mapOf(
            chromeKey to AppAssignment.Guarded(),
            socialKey to AppAssignment.Blocked,
        )

        composeTestRule.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = initial,
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = {},
                    onBack = {},
                )
            }
        }

        // Click BLOCKED filter
        composeTestRule.onNodeWithText("BLOCKED (1)").performClick()
        composeTestRule.onNodeWithText("Instagram").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clock").assertDoesNotExist()

        // Click GUARDED filter
        composeTestRule.onNodeWithText("GUARDED (1)").performClick()
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Instagram").assertDoesNotExist()

        // Click ALLOW filter
        composeTestRule.onNodeWithText("ALLOW (1)").performClick()
        composeTestRule.onNodeWithText("Clock").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertDoesNotExist()
    }

    @Test
    fun changingAssignmentReactivelyInvokesOnSaveCallback() {
        var savedMap: Map<ComponentKey, AppAssignment>? = null

        val initial = mapOf(
            chromeKey to AppAssignment.Guarded(),
        )

        composeTestRule.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = initial,
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = { savedMap = it },
                    onBack = {},
                )
            }
        }

        // Toggling first row (Chrome) to BLOCK immediately persists via onSave
        composeTestRule.onAllNodesWithText("BLOCK")[0].performClick()

        assertThat(savedMap).isNotNull()
        assertThat(savedMap?.get(chromeKey)).isEqualTo(AppAssignment.Blocked)
    }

    @Test
    fun filterAndExpandedGuardedApp_preserveSelectionAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeTestRule)
        val initial = mapOf(
            chromeKey to AppAssignment.Guarded(),
            socialKey to AppAssignment.Blocked,
        )

        restorationTester.setContent {
            DryToastTheme {
                ConfigureAppsScreen(
                    availableApps = apps,
                    initialAssignments = initial,
                    defaultFriction = FrictionChoice.TYPED_INTENTION,
                    onSave = {},
                    onBack = {},
                )
            }
        }

        // Select GUARDED filter and expand Chrome's friction override drawer
        composeTestRule.onNodeWithText("GUARDED (1)").performClick()
        composeTestRule.onNodeWithTag("configure_app_row_${chromeKey.id}")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Override friction").assertIsDisplayed()
        composeTestRule.onNodeWithText("Instagram").assertDoesNotExist()

        restorationTester.emulateSavedInstanceStateRestore()

        // Both GUARDED filter and Chrome's expanded drawer remain intact
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Override friction").assertIsDisplayed()
        composeTestRule.onNodeWithText("Instagram").assertDoesNotExist()
    }
}
