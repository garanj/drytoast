package com.garan.drytoast.ui.friction

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.garan.drytoast.core.port.HapticEngine
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
class TypedIntentionSurfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysHeaderAndTargetPhraseWithGoBackButton() {
        var dismissed = false
        var confirmed = false

        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "I need to complete a task",
                    onConfirmOpen = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("TYPED INTENTION").assertIsDisplayed()
        composeTestRule.onNodeWithText("I need to complete a task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()

        // Clicking "Go back" triggers onDismiss
        composeTestRule.onNodeWithText("Go back").performClick()
        assertThat(dismissed).isTrue()
        assertThat(confirmed).isFalse()
    }

    @Test
    fun typingWrongLetter_triggersResistanceDetentAndDoesNotAdvance() {
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "Focus now",
                    onConfirmOpen = {},
                    onDismiss = {},
                    hapticEngine = haptic,
                )
            }
        }

        // Expected first letter is 'F'. User types 'Z'.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Z")
        composeTestRule.waitForIdle()

        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
        assertThat(haptic.targetSnapCount).isEqualTo(0)
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun typingFullPhrase_morphsButtonToOpen_andClickingOpenTriggersConfirm() {
        val haptic = TestHapticEngine()
        var confirmed = false

        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "Go",
                    onConfirmOpen = { confirmed = true },
                    onDismiss = {},
                    hapticEngine = haptic,
                )
            }
        }

        // Type 'g' and 'o'
        composeTestRule.onNode(hasSetTextAction()).performTextInput("g")
        composeTestRule.waitForIdle()
        assertThat(haptic.targetSnapCount).isEqualTo(0)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("o")
        composeTestRule.waitForIdle()

        // Phrase complete -> target snap fired, button is "Open"
        assertThat(haptic.targetSnapCount).isEqualTo(1)
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()

        // Clicking "Open" confirms launch
        composeTestRule.onNodeWithText("Open").performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    fun bulkClipboardPaste_isRejectedWithResistanceDetent() {
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "Focus now",
                    onConfirmOpen = {},
                    onDismiss = {},
                    hapticEngine = haptic,
                )
            }
        }

        // Pasting > 2 characters at once is rejected as a bulk paste bypass attempt
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Focus")
        composeTestRule.waitForIdle()

        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
        assertThat(haptic.targetSnapCount).isEqualTo(0)
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun twoCharImeCommit_advancesBothCharactersCleanly() {
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "Go",
                    onConfirmOpen = {},
                    onDismiss = {},
                    hapticEngine = haptic,
                )
            }
        }

        // 2-character IME commit (e.g. fast double-tap or auto-space + letter)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Go")
        composeTestRule.waitForIdle()

        assertThat(haptic.resistanceDetentCount).isEqualTo(0)
        assertThat(haptic.targetSnapCount).isEqualTo(1)
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    fun midPhraseTyping_survivesStateRestorationWithSameRandomPhraseAndMatchedProgress() {
        var confirmed = false
        val restorationTester = StateRestorationTester(composeTestRule)

        restorationTester.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "Signal",
                    onConfirmOpen = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        // Capture the randomly selected target phrase
        val initialPhrase = composeTestRule.onNodeWithTag("typed_intention_phrase")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .text
        assertThat(initialPhrase.length).isGreaterThan(6)

        val firstHalf = initialPhrase.substring(0, 6)
        val secondHalf = initialPhrase.substring(6)

        for (ch in firstHalf) {
            composeTestRule.onNode(hasSetTextAction()).performTextInput(ch.toString())
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()

        // Simulate foldable cover <-> inner densityDpi recreation
        restorationTester.emulateSavedInstanceStateRestore()

        // Same random phrase must remain displayed
        val restoredPhrase = composeTestRule.onNodeWithTag("typed_intention_phrase")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .text
        assertThat(restoredPhrase).isEqualTo(initialPhrase)

        // Typing only the remaining characters completes the phrase and unlocks "Open"
        for (ch in secondHalf) {
            composeTestRule.onNode(hasSetTextAction()).performTextInput(ch.toString())
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("Open").assertIsDisplayed().performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
    fun expandedTablet_constrainsPhraseColumnMaxWidthAndEnforces48dpActionButtonHeight() {
        composeTestRule.setContent {
            DryToastTheme {
                TypedIntentionSurface(
                    appLabel = "YouTube",
                    overridePhrase = "I am opening YouTube intentionally",
                    onConfirmOpen = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val density = composeTestRule.density
        val phraseBounds = composeTestRule.onNodeWithTag("typed_intention_content_column")
            .fetchSemanticsNode()
            .boundsInRoot
        val maxContentWidthPx = with(density) { 600.dp.toPx() } + 1f
        assertThat(phraseBounds.width).isAtMost(maxContentWidthPx)

        val actionTouchBounds = composeTestRule.onNodeWithTag("typed_intention_action_button")
            .fetchSemanticsNode()
            .touchBoundsInRoot
        val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
        assertThat(actionTouchBounds.height).isAtLeast(minTouchPx)
        assertThat(actionTouchBounds.width).isAtLeast(minTouchPx)
    }

    private class TestHapticEngine : HapticEngine {
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
