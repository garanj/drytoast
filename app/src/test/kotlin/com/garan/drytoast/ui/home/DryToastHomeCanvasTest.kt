package com.garan.drytoast.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DryToastHomeCanvasTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun defaultCanvas_registersReceiverAndUpdatesDisplayedTimeOnTick() {
        var simulatedNow = ZonedDateTime.of(2026, 11, 2, 10, 9, 59, 0, java.time.ZoneId.of("UTC"))

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeCanvas(
                    clockProvider = { simulatedNow },
                )
            }
        }

        composeTestRule.onNodeWithText("10:09").assertIsDisplayed()
        composeTestRule.onNodeWithText("Monday, November 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("DRY TOAST").assertIsDisplayed()

        // Advance clock across minute boundary and send ACTION_TIME_TICK broadcast
        simulatedNow = simulatedNow.plusSeconds(1)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("10:10").assertIsDisplayed()
        composeTestRule.onNodeWithText("DRY TOAST").assertIsDisplayed()
    }

    @Test
    fun defaultCanvas_unregistersTimeTickReceiverOnLifecycleStopAndReregistersOnStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.STARTED
            }
            override val lifecycle: Lifecycle get() = registry
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                DryToastTheme {
                    DryToastHomeCanvas()
                }
            }
        }
        composeTestRule.waitForIdle()

        val hasTimeTickReceiverWhenStarted = shadowApp.registeredReceivers.any {
            it.intentFilter.hasAction(Intent.ACTION_TIME_TICK)
        }
        assertThat(hasTimeTickReceiverWhenStarted).isTrue()

        // Transition to CREATED (ON_STOP) -> receiver must be unregistered
        composeTestRule.runOnIdle {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeTestRule.waitForIdle()

        val hasTimeTickReceiverWhenStopped = shadowApp.registeredReceivers.any {
            it.intentFilter.hasAction(Intent.ACTION_TIME_TICK)
        }
        assertThat(hasTimeTickReceiverWhenStopped).isFalse()

        // Transition back to STARTED (ON_START) -> receiver must re-register
        composeTestRule.runOnIdle {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        composeTestRule.waitForIdle()

        val hasTimeTickReceiverWhenRestarted = shadowApp.registeredReceivers.any {
            it.intentFilter.hasAction(Intent.ACTION_TIME_TICK)
        }
        assertThat(hasTimeTickReceiverWhenRestarted).isTrue()
    }

    @Test
    fun canvas_displaysCapabilityFaultBadgeAndActiveProfileBadges_andInvokesCallbackOnClick() {
        var faultClicked = false
        val workProfile = CustomProfile(id = "work", name = "Work Focus", colorIndex = 0)

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeCanvas(
                    timeText = "09:41",
                    dateText = "Monday, November 2",
                    statusText = "REST 8H 12M  •  4 UNLOCKS",
                    activeProfiles = persistentListOf(workProfile),
                    hasCapabilityIssues = true,
                    onCapabilityFaultClick = { faultClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Active profile: Work Focus").assertIsDisplayed()
        composeTestRule.onNodeWithText("W").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Missing profile permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("!").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Missing profile permissions").performClick()
        assertThat(faultClicked).isTrue()
    }

    @Test
    fun canvas_statusRowBadgeAppearance_doesNotShiftBelowContentVerticallyAtDefaultOrLargeFontScale() {
        val workProfile = CustomProfile(id = "work", name = "Work Focus", colorIndex = 0)
        var profiles by mutableStateOf(persistentListOf<CustomProfile>())
        var faultIssues by mutableStateOf(false)
        var fontScale by mutableStateOf(1.0f)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 3.0f, fontScale = fontScale),
            ) {
                DryToastTheme {
                    DryToastHomeCanvas(
                        timeText = "22:24",
                        dateText = "Friday, November 13",
                        statusText = "REST 3M  •  80 UNLOCKS",
                        activeProfiles = profiles,
                        hasCapabilityIssues = faultIssues,
                    ) {
                        Text(
                            text = "Type to launch...",
                            modifier = Modifier.testTag("below_status_content"),
                        )
                    }
                }
            }
        }

        // 1. At 1.0x font scale: measure top coordinate before and after badges appear
        val topWithoutBadges1x = composeTestRule
            .onNodeWithTag("below_status_content")
            .getUnclippedBoundsInRoot()
            .top

        composeTestRule.runOnIdle {
            profiles = persistentListOf(workProfile)
            faultIssues = true
        }
        composeTestRule.waitForIdle()

        val topWithBadges1x = composeTestRule
            .onNodeWithTag("below_status_content")
            .getUnclippedBoundsInRoot()
            .top

        assertThat(topWithBadges1x).isEqualTo(topWithoutBadges1x)

        // 2. At 2.0x accessibility font scale: measure top coordinate before and after badges appear
        composeTestRule.runOnIdle {
            fontScale = 2.0f
            profiles = persistentListOf()
            faultIssues = false
        }
        composeTestRule.waitForIdle()

        val topWithoutBadges2x = composeTestRule
            .onNodeWithTag("below_status_content")
            .getUnclippedBoundsInRoot()
            .top

        composeTestRule.runOnIdle {
            profiles = persistentListOf(workProfile)
            faultIssues = true
        }
        composeTestRule.waitForIdle()

        val topWithBadges2x = composeTestRule
            .onNodeWithTag("below_status_content")
            .getUnclippedBoundsInRoot()
            .top

        assertThat(topWithBadges2x).isEqualTo(topWithoutBadges2x)
    }

    @Test
    fun canvas_displaysCenteredDialerIconNearBottomWhenEnabled_andHidesWhenDisabled() {
        var dialerClicked = false
        var showDialer by mutableStateOf(true)

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeCanvas(
                    timeText = "10:09",
                    dateText = "Monday, November 2",
                    statusText = "Dry Toast",
                    showDialerIcon = showDialer,
                    onDialerClick = { dialerClicked = true },
                )
            }
        }

        val dialerNode = composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG)
        dialerNode.assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open dialer").assertIsDisplayed()

        dialerNode.performClick()
        assertThat(dialerClicked).isTrue()

        composeTestRule.runOnIdle {
            showDialer = false
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertDoesNotExist()
    }
}
