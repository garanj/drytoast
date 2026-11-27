package com.garan.drytoast.ui.friction

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.garan.drytoast.core.friction.DragSortSession
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DragSortSurfaceTest {

    private val publisherRule = WindowLayoutInfoPublisherRule()
    private val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testRule: RuleChain = RuleChain.outerRule(publisherRule).around(composeTestRule)

    @Test
    fun displaysCanvasWithGoBackButton_andDismissesOnClick() {
        var dismissed = false
        var confirmed = false

        composeTestRule.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 6,
                    seed = 42L,
                    onConfirmOpen = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("drag_sort_surface").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drag_sort_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()

        composeTestRule.onNodeWithText("Go back").performClick()
        assertThat(dismissed).isTrue()
        assertThat(confirmed).isFalse()
    }

    @Test
    fun draggingGlyphOffTarget_triggersResistanceDetentAndSnapsBack() {
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 6,
                    seed = 42L,
                    hapticEngine = haptic,
                    onConfirmOpen = {},
                    onDismiss = {},
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("drag_sort_canvas")
        val canvasSize = canvasNode.fetchSemanticsNode().size
        val itemRadiusPx = with(composeTestRule.density) { 28.dp.toPx() }
        val pairs = DragSortSession.generateLayout(
            pairCount = 6,
            containerWidth = canvasSize.width.toFloat(),
            containerHeight = canvasSize.height.toFloat(),
            itemRadius = itemRadiusPx,
            minPairSeparation = canvasSize.width.toFloat() * 0.45f,
            random = Random(42L),
        )

        val firstPair = pairs.first()
        val wrongDrop = Offset(
            x = (firstPair.startPosition.x + 20f).coerceIn(10f, canvasSize.width - 10f),
            y = (firstPair.startPosition.y + 20f).coerceIn(10f, canvasSize.height - 10f),
        )

        canvasNode.performTouchInput {
            down(Offset(firstPair.startPosition.x, firstPair.startPosition.y))
            moveTo(wrongDrop)
            up()
        }
        composeTestRule.waitForIdle()

        assertThat(haptic.rotaryDetentCount).isEqualTo(1)
        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
        assertThat(haptic.relaySnapCount).isEqualTo(0)
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun draggingAllSixGlyphsToExactTargets_matchesEveryPairAndTransitionsToOpen() {
        val haptic = TestHapticEngine()
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 6,
                    seed = 42L,
                    hapticEngine = haptic,
                    onConfirmOpen = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("drag_sort_canvas")
        val canvasSize = canvasNode.fetchSemanticsNode().size
        val itemRadiusPx = with(composeTestRule.density) { 28.dp.toPx() }
        val pairs = DragSortSession.generateLayout(
            pairCount = 6,
            containerWidth = canvasSize.width.toFloat(),
            containerHeight = canvasSize.height.toFloat(),
            itemRadius = itemRadiusPx,
            minPairSeparation = canvasSize.width.toFloat() * 0.45f,
            random = Random(42L),
        )

        for (pair in pairs) {
            canvasNode.performTouchInput {
                down(Offset(pair.startPosition.x, pair.startPosition.y))
                moveTo(Offset(pair.targetPosition.x, pair.targetPosition.y))
                up()
            }
            composeTestRule.waitForIdle()
        }

        assertThat(haptic.rotaryDetentCount).isEqualTo(6)
        assertThat(haptic.relaySnapCount).isEqualTo(6)
        assertThat(haptic.resistanceDetentCount).isEqualTo(0)
        assertThat(haptic.targetSnapCount).isEqualTo(1)

        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").performClick()

        assertThat(confirmed).isTrue()
        assertThat(dismissed).isFalse()
    }

    @Test
    fun liveResizeAndRotation_preservesMatchedPairsAndCompletedOpenState() {
        var confirmed = false
        var containerSize by mutableStateOf(DpSize(411.dp, 780.dp))

        composeTestRule.setContent {
            DryToastTheme {
                Box(modifier = Modifier.size(containerSize)) {
                    DragSortSurface(
                        pairCount = 4,
                        seed = 99L,
                        embedded = true,
                        onConfirmOpen = { confirmed = true },
                        onDismiss = {},
                    )
                }
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("drag_sort_canvas")
        val initialSize = canvasNode.fetchSemanticsNode().size
        val itemRadiusPx = with(composeTestRule.density) { 28.dp.toPx() }
        val initialPairs = DragSortSession.generateLayout(
            pairCount = 4,
            containerWidth = initialSize.width.toFloat(),
            containerHeight = initialSize.height.toFloat(),
            itemRadius = itemRadiusPx,
            minPairSeparation = initialSize.width.toFloat() * 0.45f,
            random = Random(99L),
        )

        // Match first 2 of 4 pairs in portrait
        for (pair in initialPairs.take(2)) {
            canvasNode.performTouchInput {
                down(Offset(pair.startPosition.x, pair.startPosition.y))
                moveTo(Offset(pair.targetPosition.x, pair.targetPosition.y))
                up()
            }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("Go back").assertIsDisplayed()

        // Simulate live 90-degree rotation / split-screen resize
        containerSize = DpSize(540.dp, 400.dp)
        composeTestRule.waitForIdle()

        val rotatedSize = canvasNode.fetchSemanticsNode().size
        val rotatedPairs = DragSortSession.generateLayout(
            pairCount = 4,
            containerWidth = rotatedSize.width.toFloat(),
            containerHeight = rotatedSize.height.toFloat(),
            itemRadius = itemRadiusPx,
            minPairSeparation = rotatedSize.width.toFloat() * 0.45f,
            random = Random(99L),
        )

        // Matching only the remaining 2 pairs (indices 2 & 3) must complete the session!
        for (pair in rotatedPairs.drop(2)) {
            canvasNode.performTouchInput {
                down(Offset(pair.startPosition.x, pair.startPosition.y))
                moveTo(Offset(pair.targetPosition.x, pair.targetPosition.y))
                up()
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("Open").assertIsDisplayed()

        // Resizing again AFTER completion must keep "Open" active without resetting
        containerSize = DpSize(411.dp, 700.dp)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    fun stateRestoration_preservesMatchedPairsAndCompletedStateAcrossFoldRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)

        restorationTester.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 4,
                    seed = 77L,
                    onConfirmOpen = {},
                    onDismiss = {},
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("drag_sort_canvas")
        val canvasSize = canvasNode.fetchSemanticsNode().size
        val itemRadiusPx = with(composeTestRule.density) { 28.dp.toPx() }
        val pairs = DragSortSession.generateLayout(
            pairCount = 4,
            containerWidth = canvasSize.width.toFloat(),
            containerHeight = canvasSize.height.toFloat(),
            itemRadius = itemRadiusPx,
            minPairSeparation = canvasSize.width.toFloat() * 0.45f,
            random = Random(77L),
        )

        // Match 2 of 4 pairs, then simulate foldable densityDpi recreation
        for (pair in pairs.take(2)) {
            canvasNode.performTouchInput {
                down(Offset(pair.startPosition.x, pair.startPosition.y))
                moveTo(Offset(pair.targetPosition.x, pair.targetPosition.y))
                up()
            }
            composeTestRule.waitForIdle()
        }

        restorationTester.emulateSavedInstanceStateRestore()

        // Only the remaining 2 pairs should be needed to unlock "Open"
        for (pair in pairs.drop(2)) {
            composeTestRule.onNodeWithTag("drag_sort_canvas").performTouchInput {
                down(Offset(pair.startPosition.x, pair.startPosition.y))
                moveTo(Offset(pair.targetPosition.x, pair.targetPosition.y))
                up()
            }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()

        // Restoring again while completed keeps "Open"
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
    fun expandedTablet_capsPlayfieldBoundsAndEnforces48dpActionButtonHeight() {
        composeTestRule.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 6,
                    seed = 42L,
                    onConfirmOpen = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val density = composeTestRule.density
        val canvasBounds = composeTestRule.onNodeWithTag("drag_sort_canvas").fetchSemanticsNode().boundsInRoot
        val maxPlayfieldWidthPx = with(density) { 560.dp.toPx() } + 1f
        val maxPlayfieldHeightPx = with(density) { 680.dp.toPx() } + 1f
        assertThat(canvasBounds.width).isAtMost(maxPlayfieldWidthPx)
        assertThat(canvasBounds.height).isAtMost(maxPlayfieldHeightPx)

        val actionBounds = composeTestRule.onNodeWithTag(
            "drag_sort_action_button",
        ).fetchSemanticsNode().touchBoundsInRoot
        val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
        assertThat(actionBounds.height).isAtLeast(minTouchPx)
        assertThat(actionBounds.width).isAtLeast(minTouchPx)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w680dp-h840dp-mdpi")
    fun separatingFoldableHinge_placesPlayfieldClearOfVerticalAndHorizontalCrease() {
        composeTestRule.setContent {
            DryToastTheme {
                DragSortSurface(
                    pairCount = 6,
                    seed = 42L,
                    onConfirmOpen = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Publish a half-opened Book posture vertical hinge at x = [330, 350]
        val verticalHinge = FoldingFeature(
            activity = composeTestRule.activity,
            center = 340,
            size = 20,
            state = FoldingFeature.State.HALF_OPENED,
            orientation = FoldingFeature.Orientation.VERTICAL,
        )
        publisherRule.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(verticalHinge)))
        composeTestRule.waitForIdle()

        val bookCanvasWindowBounds = composeTestRule.onNodeWithTag("drag_sort_canvas")
            .fetchSemanticsNode()
            .boundsInWindow
        val hingeRect = verticalHinge.bounds
        val overlapsVerticalHinge =
            bookCanvasWindowBounds.left < hingeRect.right && bookCanvasWindowBounds.right > hingeRect.left
        assertThat(overlapsVerticalHinge).isFalse()

        // Publish a half-opened Tabletop posture horizontal hinge at y = [410, 430]
        val horizontalHinge = FoldingFeature(
            activity = composeTestRule.activity,
            center = 420,
            size = 20,
            state = FoldingFeature.State.HALF_OPENED,
            orientation = FoldingFeature.Orientation.HORIZONTAL,
        )
        publisherRule.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(horizontalHinge)))
        composeTestRule.waitForIdle()

        val tabletopCanvasWindowBounds = composeTestRule.onNodeWithTag("drag_sort_canvas")
            .fetchSemanticsNode()
            .boundsInWindow
        val horizRect = horizontalHinge.bounds
        val overlapsHorizontalHinge =
            tabletopCanvasWindowBounds.top < horizRect.bottom && tabletopCanvasWindowBounds.bottom > horizRect.top
        assertThat(overlapsHorizontalHinge).isFalse()
    }

    private class TestHapticEngine : HapticEngine {
        var rotaryDetentCount = 0
        var relaySnapCount = 0
        var resistanceDetentCount = 0
        var targetSnapCount = 0

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
    }
}
