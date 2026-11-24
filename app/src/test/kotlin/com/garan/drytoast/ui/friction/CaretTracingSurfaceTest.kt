package com.garan.drytoast.ui.friction

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.ui.motion.DryToastMotion
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
class CaretTracingSurfaceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun initialState_showsIdleToaster_doesNotAutoComplete_andDismissesOnGoBack() {
        var dismissed = false
        var confirmed = false
        val haptic = TestHapticEngine()

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            DryToastTheme {
                CaretTracingSurface(
                    onConfirmOpen = { confirmed = true },
                    onDismiss = { dismissed = true },
                    hapticEngine = haptic,
                    totalDurationMs = 1_600L,
                )
            }
        }

        composeTestRule.onNodeWithTag("caret_tracing_surface").assertIsDisplayed()
        composeTestRule.onNodeWithTag("caret_tracing_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("caret_tracing_lever").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hold lever down to toast").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()

        // Passive waiting without holding the lever must never unlock
        composeTestRule.mainClock.advanceTimeBy(5_000L)
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
        assertThat(haptic.relaySnapCount).isEqualTo(0)
        assertThat(haptic.targetSnapCount).isEqualTo(0)

        composeTestRule.onNodeWithText("Go back").performClick()
        assertThat(dismissed).isTrue()
        assertThat(confirmed).isFalse()
    }

    @Test
    fun holdingLeverToCompletion_triggersLatchTicksPopsAndRelaySnap_thenRevealsOpen() {
        var confirmed = false
        var dismissed = false
        val haptic = TestHapticEngine()

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            DryToastTheme {
                CaretTracingSurface(
                    onConfirmOpen = { confirmed = true },
                    onDismiss = { dismissed = true },
                    hapticEngine = haptic,
                    totalDurationMs = 1_600L, // 200ms per slice across 8 slices
                )
            }
        }

        composeTestRule.waitForIdle()

        // Press and hold the carriage lever down
        composeTestRule.onNodeWithTag("caret_tracing_lever").performTouchInput {
            down(center)
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        assertThat(haptic.resistanceDetentCount).isEqualTo(1)

        // Advance smoothly in 16ms frames through the full 1,600ms hold
        repeat(110) {
            composeTestRule.mainClock.advanceTimeBy(16L)
        }

        // Expect: 1 latch, clockwork ticks (> 0), 7 slice pops (slices 1..7), 1 final relay snap (slice 8)
        assertThat(haptic.clockworkTickCount).isGreaterThan(0)
        assertThat(haptic.targetSnapCount).isEqualTo(7)
        assertThat(haptic.relaySnapCount).isEqualTo(1)

        // Release finger after completion — must NOT reset completed state
        composeTestRule.onNodeWithTag("caret_tracing_lever").performTouchInput {
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(DryToastMotion.SHUTTER_OPEN_DURATION_MS.toLong() + 50L)

        composeTestRule.onNodeWithText("All 8 slices toasted").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").performClick()

        assertThat(confirmed).isTrue()
        assertThat(dismissed).isFalse()
    }

    @Test
    fun releasingLeverEarly_resetsTimerAndRequiresFreshHold() {
        val haptic = TestHapticEngine()
        var interactionActive = false

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            DryToastTheme {
                CaretTracingSurface(
                    onConfirmOpen = {},
                    onDismiss = {},
                    hapticEngine = haptic,
                    totalDurationMs = 1_600L,
                    onInteractionActiveChanged = { interactionActive = it },
                )
            }
        }

        composeTestRule.waitForIdle()

        // Hold for ~650ms (3 slices popped out of 8)
        composeTestRule.onNodeWithTag("caret_tracing_lever").performTouchInput {
            down(center)
        }
        assertThat(interactionActive).isTrue()
        composeTestRule.waitForIdle()
        repeat(42) {
            composeTestRule.mainClock.advanceTimeBy(16L)
        }
        assertThat(haptic.targetSnapCount).isGreaterThan(0)
        assertThat(haptic.relaySnapCount).isEqualTo(0)

        // Release lever early -> must spring back, reset to idle, and unlock parent interaction
        composeTestRule.onNodeWithTag("caret_tracing_lever").performTouchInput {
            up()
        }
        assertThat(interactionActive).isFalse()
        composeTestRule.mainClock.advanceTimeBy(200L)

        assertThat(haptic.rotaryDetentCount).isEqualTo(1)
        assertThat(haptic.relaySnapCount).isEqualTo(0)
        composeTestRule.onNodeWithText("Hold lever down to toast").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun systemBack_invokesDismiss() {
        var dismissed = false

        composeTestRule.setContent {
            DryToastTheme {
                CaretTracingSurface(
                    onConfirmOpen = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        assertThat(dismissed).isTrue()
    }

    @Test
    fun systemAnimatorDurationScaleZero_doesNotBypassFrictionDelay() {
        val haptic = TestHapticEngine()
        composeTestRule.mainClock.autoAdvance = false

        val contentResolver = composeTestRule.activity.contentResolver
        try {
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

            composeTestRule.setContent {
                DryToastTheme {
                    CaretTracingSurface(
                        onConfirmOpen = {},
                        onDismiss = {},
                        hapticEngine = haptic,
                        totalDurationMs = 800L,
                    )
                }
            }

            composeTestRule.onNodeWithTag("caret_tracing_lever").performTouchInput {
                down(center)
            }

            // Advance a single 32ms step: with UnscaledMotionDurationScale, it must NOT jump to Open
            composeTestRule.mainClock.advanceTimeBy(32L)
            composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
            assertThat(haptic.relaySnapCount).isEqualTo(0)

            // Only after holding for the full duration does it complete
            repeat(60) {
                composeTestRule.mainClock.advanceTimeBy(16L)
            }
            assertThat(haptic.relaySnapCount).isEqualTo(1)
            composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        } finally {
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    private class TestHapticEngine : HapticEngine {
        var rotaryDetentCount = 0
        var relaySnapCount = 0
        var resistanceDetentCount = 0
        var targetSnapCount = 0
        var clockworkTickCount = 0

        override fun rotaryDetent() {
            rotaryDetentCount++
        }

        override fun relaySnap() {
            relaySnapCount++
        }

        override fun resistanceDetent() {
            resistanceDetentCount++
        }

        override fun targetSnap() {
            targetSnapCount++
        }

        override fun clockworkTick() {
            clockworkTickCount++
        }
    }
}
