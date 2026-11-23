package com.garan.drytoast.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.ui.home.HOME_CANVAS_TEST_TAG
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingScreen_stepsThroughAllSixPagesWithRotaryHaptics() {
        val haptic = RecordingHapticEngine()

        composeRule.setContent {
            DryToastTheme {
                OnboardingScreen(
                    isDefaultLauncher = false,
                    onRequestDefaultLauncher = {},
                    onCompleteOnboarding = {},
                    hapticEngine = haptic,
                )
            }
        }

        // Page 01 / 06: DRY TOAST
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("01 / 06")
        composeRule.onNodeWithText("DRY TOAST").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet by default.").assertIsDisplayed()

        // Page 02 / 06: DELIBERATE LAUNCH
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("02 / 06")
        composeRule.onNodeWithText("DELIBERATE LAUNCH").assertIsDisplayed()
        assertThat(haptic.rotaryDetentCount).isAtLeast(1)

        // Page 03 / 06: INTENTIONAL FRICTION
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("03 / 06")
        composeRule.onNodeWithText("INTENTIONAL FRICTION").assertIsDisplayed()

        // Page 04 / 06: CONTEXTUAL PROFILES
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("04 / 06")
        composeRule.onNodeWithText("CONTEXTUAL PROFILES").assertIsDisplayed()

        // Page 05 / 06: TAMPER RESISTANCE
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("05 / 06")
        composeRule.onNodeWithText("TAMPER RESISTANCE").assertIsDisplayed()
        composeRule.onNodeWithText("Press and hold to configure.").assertIsDisplayed()

        // Page 06 / 06: SET DEFAULT LAUNCHER
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.STEP_INDICATOR)
            .assertTextContains("06 / 06")
        composeRule.onNodeWithText("SET DEFAULT LAUNCHER").assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_LAUNCHER_BUTTON).assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_pageThree_interactiveToggleAndCheckpointChipsFireHapticsAndUpdatePreview() {
        val haptic = RecordingHapticEngine()

        composeRule.setContent {
            DryToastTheme {
                OnboardingScreen(
                    isDefaultLauncher = false,
                    onRequestDefaultLauncher = {},
                    onCompleteOnboarding = {},
                    hapticEngine = haptic,
                )
            }
        }

        // Advance to Page 03 / 06
        repeat(2) {
            composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithTag(OnboardingTestTags.INTERACTIVE_TOGGLE_STATUS)
            .assertTextContains("GUARD: Pauses launch behind a deliberate cognitive checkpoint", substring = true)

        // Tap ALLOW segment on interactive toggle -> rotary detent
        val rotaryBeforeAllow = haptic.rotaryDetentCount
        composeRule.onNodeWithText("ALLOW").performClick()
        composeRule.waitForIdle()
        assertThat(haptic.rotaryDetentCount).isGreaterThan(rotaryBeforeAllow)
        composeRule.onNodeWithTag(OnboardingTestTags.INTERACTIVE_TOGGLE_STATUS)
            .assertTextContains("ALLOW: Launches immediately from search and quick actions", substring = true)

        // Tap BLOCK segment on interactive toggle -> resistance detent
        val resistanceBeforeBlock = haptic.resistanceDetentCount
        composeRule.onNodeWithText("BLOCK").performClick()
        composeRule.waitForIdle()
        assertThat(haptic.resistanceDetentCount).isGreaterThan(resistanceBeforeBlock)
        composeRule.onNodeWithTag(OnboardingTestTags.INTERACTIVE_TOGGLE_STATUS)
            .assertTextContains("BLOCK: Hidden from search results and disabled in quick actions.", substring = true)

        // Tap Drag-to-Match checkpoint chip -> updates preview description and hosts embedded DragSortSurface
        composeRule.onNodeWithTag(OnboardingTestTags.FRICTION_CHIP_PREFIX + FrictionChoice.DRAG_SORT.name)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.FRICTION_PREVIEW_DESCRIPTION)
            .performScrollTo()
            .assertTextContains("Drag geometric glyph pairs", substring = true)
        composeRule.onNodeWithTag("drag_sort_surface")
            .performScrollTo()
            .assertIsDisplayed()

        // Tap Caret Tracing checkpoint chip -> updates preview description and hosts embedded CaretTracingSurface
        composeRule.onNodeWithTag(OnboardingTestTags.FRICTION_CHIP_PREFIX + FrictionChoice.CARET_TRACING.name)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OnboardingTestTags.FRICTION_PREVIEW_DESCRIPTION)
            .performScrollTo()
            .assertTextContains("Trace the Dry Toast caret steadily", substring = true)
        composeRule.onNodeWithTag("caret_tracing_surface")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_pageSix_requiresDefaultLauncherRoleBeforeEnablingCompletion() {
        var isDefault by mutableStateOf(false)
        var showWarning by mutableStateOf(false)
        var requestedDefaultCount = 0
        var completedCount = 0

        composeRule.setContent {
            DryToastTheme {
                OnboardingScreen(
                    isDefaultLauncher = isDefault,
                    onRequestDefaultLauncher = {
                        requestedDefaultCount++
                        showWarning = true
                    },
                    onCompleteOnboarding = { completedCount++ },
                    showRoleRequiredWarning = showWarning,
                )
            }
        }

        // Advance to Page 06 / 06
        repeat(5) {
            composeRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
            composeRule.waitForIdle()
        }

        // Not default launcher yet -> shows Set Dry Toast as Default Launcher CTA
        composeRule.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_LAUNCHER_BUTTON)
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertThat(requestedDefaultCount).isEqualTo(1)
        assertThat(completedCount).isEqualTo(0)
        composeRule.onNodeWithTag(OnboardingTestTags.ROLE_REQUIRED_WARNING).assertIsDisplayed()

        // Grant default launcher role -> CTA transitions to "Enter Dry Toast"
        isDefault = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(OnboardingTestTags.FINISH_BUTTON)
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertThat(completedCount).isEqualTo(1)
    }

    @Test
    fun mainActivity_whenDefaultLauncherRoleNotGranted_rerunsOnboardingOnReopenUntilRoleGranted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DryToastAppContainer.from(context)
        container.defaultLauncherOverrideForTesting = false
        runBlocking {
            container.settingsRepository.setHasCompletedOnboarding(false)
            container.settingsRepository.setHasSeenFirstRunHint(false)
        }

        try {
            Robolectric.buildActivity(MainActivity::class.java).use { controller ->
                controller.setup()
                val activity = controller.get()

                // 1. Starts in Onboarding because Dry Toast is not the default launcher
                assertThat(activity.settingsViewModel.uiState.value.general.hasCompletedOnboarding).isFalse()

                // 2. Simulate user dismissing system ROLE_HOME dialog without selecting Dry Toast
                activity.handleDefaultLauncherRoleResult()
                assertThat(activity.settingsViewModel.uiState.value.general.hasCompletedOnboarding).isFalse()

                // 3. Simulate reopening the app while still not default launcher -> still in Onboarding
                controller.newIntent(Intent(context, MainActivity::class.java))
                assertThat(activity.settingsViewModel.uiState.value.general.hasCompletedOnboarding).isFalse()

                // 4. Simulate user granting ROLE_HOME in system dialog -> completes onboarding!
                container.defaultLauncherOverrideForTesting = true
                activity.handleDefaultLauncherRoleResult()

                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    runBlocking {
                        container.settingsRepository.getSettings().general.hasCompletedOnboarding
                    }
                }
                val completed = runBlocking {
                    container.settingsRepository.getSettings().general.hasCompletedOnboarding
                }
                assertThat(completed).isTrue()
            }
        } finally {
            container.pendingOnboardingRoleRequest = false
            container.defaultLauncherOverrideForTesting = true
            runBlocking {
                container.settingsRepository.setHasCompletedOnboarding(true)
            }
        }
    }

    @Test
    fun mainActivity_whenPermissionControllerStartsNewHomeTaskUponRoleGrant_entersHomeDirectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DryToastAppContainer.from(context)
        container.pendingOnboardingRoleRequest = false
        container.defaultLauncherOverrideForTesting = false
        runBlocking {
            container.settingsRepository.setHasCompletedOnboarding(false)
            container.settingsRepository.setHasSeenFirstRunHint(false)
        }

        try {
            val launcherIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            Robolectric.buildActivity(MainActivity::class.java, launcherIntent).use { bootstrapController ->
                bootstrapController.setup()
                val bootstrapActivity = bootstrapController.get()

                // 1. User taps "Set default launcher" on Page 06 / 06 in the CATEGORY_LAUNCHER task
                bootstrapActivity.requestDefaultLauncherRole()
                assertThat(container.pendingOnboardingRoleRequest).isTrue()

                // 2. Android PermissionController grants ROLE_HOME and starts a new CATEGORY_HOME task
                // BEFORE delivering onActivityResult to the bootstrap CATEGORY_LAUNCHER task
                container.defaultLauncherOverrideForTesting = true
                val homeIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_HOME)
                }
                Robolectric.buildActivity(MainActivity::class.java, homeIntent).use { homeController ->
                    homeController.setup()
                    val homeActivity = homeController.get()

                    // The new CATEGORY_HOME activity immediately recognizes onboarding is complete
                    assertThat(homeActivity.settingsViewModel.uiState.value.general.hasCompletedOnboarding).isTrue()
                    assertThat(container.pendingOnboardingRoleRequest).isFalse()

                    // And the bootstrap CATEGORY_LAUNCHER task finishes and removes itself when stopped
                    bootstrapController.pause().stop()
                    assertThat(bootstrapActivity.isFinishing).isTrue()
                }
            }
        } finally {
            container.pendingOnboardingRoleRequest = false
            container.defaultLauncherOverrideForTesting = true
            runBlocking {
                container.settingsRepository.setHasCompletedOnboarding(true)
            }
        }
    }

    @Test
    fun homeScreen_displaysUpdatedPressAndHoldHintOnFirstRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DryToastAppContainer.from(context)
        container.defaultLauncherOverrideForTesting = true
        runBlocking {
            container.settingsRepository.setHasCompletedOnboarding(true)
            container.settingsRepository.setHasSeenFirstRunHint(false)
        }

        composeRule.setContent {
            DryToastTheme {
                com.garan.drytoast.ui.home.DryToastHomeScreen(
                    viewModel = com.garan.drytoast.ui.search.SearchViewModel(
                        packageIndexSource = container.packageIndexSource,
                        settingsRepository = container.settingsRepository,
                    ),
                    appLauncher = object : com.garan.drytoast.ui.launcher.AppLauncher {
                        override fun launchApp(component: com.garan.drytoast.core.model.ComponentKey): Boolean = true
                        override fun openAppInfo(component: com.garan.drytoast.core.model.ComponentKey): Boolean = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Press and hold to configure").assertIsDisplayed()
    }

    @Test
    fun dragSortSurface_resetsDragActiveToFalseWhenDisposed() {
        var showDragSort by mutableStateOf(true)
        var isDragActive = true

        composeRule.setContent {
            DryToastTheme {
                if (showDragSort) {
                    com.garan.drytoast.ui.friction.DragSortSurface(
                        onConfirmOpen = {},
                        onDismiss = {},
                        pairCount = 3,
                        embedded = true,
                        onDragActiveChanged = { isDragActive = it },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        showDragSort = false
        composeRule.waitForIdle()

        assertThat(isDragActive).isFalse()
    }

    private class RecordingHapticEngine : HapticEngine {
        var rotaryDetentCount = 0
            private set
        var targetSnapCount = 0
            private set
        var resistanceDetentCount = 0
            private set
        var relaySnapCount = 0
            private set

        override fun rotaryDetent() {
            rotaryDetentCount++
        }

        override fun targetSnap() {
            targetSnapCount++
        }

        override fun resistanceDetent() {
            resistanceDetentCount++
        }

        override fun relaySnap() {
            relaySnapCount++
        }
    }
}
