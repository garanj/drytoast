package com.garan.drytoast.ui.friction

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.garan.drytoast.R
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.NoOpHapticEngine
import com.garan.drytoast.ui.motion.DryToastMotion
import com.garan.drytoast.ui.theme.AmoledBlack
import com.garan.drytoast.ui.theme.CaretPass1Gray
import com.garan.drytoast.ui.theme.CaretPass2Gray
import com.garan.drytoast.ui.theme.CaretPass3Gray
import com.garan.drytoast.ui.theme.CaretPass4Gray
import com.garan.drytoast.ui.theme.CaretPass5White
import com.garan.drytoast.ui.theme.PureWhite
import com.garan.drytoast.ui.theme.SubtleGray
import kotlinx.coroutines.withContext

private const val TOAST_VIEWPORT_SIZE = 108f
private const val CARET_PASS_COUNT = 5f

/**
 * Populates [target] with the Dry Toast "Hollow Crust" outline scaled to fit [canvasSize].
 * Starts at the bottom-left corner, traces up the left side, across the domed top crust
 * (midpoint at the top apex), down the right side, and back along the bottom edge.
 */
private fun buildToastOutlinePath(target: Path, canvasSize: Size) {
    val side = minOf(canvasSize.width, canvasSize.height)
    val scale = side / TOAST_VIEWPORT_SIZE
    val offsetX = (canvasSize.width - side) / 2f
    val offsetY = (canvasSize.height - side) / 2f
    fun x(vx: Float): Float = offsetX + vx * scale
    fun y(vy: Float): Float = offsetY + vy * scale

    target.reset()
    target.moveTo(x(35f), y(77f))
    target.cubicTo(x(33.3f), y(77f), x(32f), y(75.7f), x(32f), y(74f))
    target.lineTo(x(32f), y(48f))
    target.cubicTo(x(27.5f), y(46.8f), x(25.5f), y(43f), x(26.5f), y(38.5f))
    target.cubicTo(x(27.8f), y(32.5f), x(36.5f), y(29f), x(54f), y(29f))
    target.cubicTo(x(71.5f), y(29f), x(80.2f), y(32.5f), x(81.5f), y(38.5f))
    target.cubicTo(x(82.5f), y(43f), x(80.5f), y(46.8f), x(76f), y(48f))
    target.lineTo(x(76f), y(74f))
    target.cubicTo(x(76f), y(75.7f), x(74.7f), y(77f), x(73f), y(77f))
    target.close()
}

/**
 * Enforces a 1.0x time scale for intentional friction delays so that system-level
 * `animator_duration_scale = 0.0` (e.g. Battery Saver or Accessibility "Remove animations")
 * cannot bypass or fast-forward the checkpoint gate.
 */
private object UnscaledMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/**
 * Full-screen meditative Caret Tracing friction checkpoint.
 *
 * Imposes an intentional 5-pass (~17-second) mechanical delay gate before launching guarded applications:
 * - Pass 1 (0 to 1): Forward trace (left to right) in darkest grey ([CaretPass1Gray]).
 * - Pass 2 (1 to 2): Reverse trace (right to left) in deep grey ([CaretPass2Gray]).
 * - Pass 3 (2 to 3): Forward trace (left to right) in mid grey ([CaretPass3Gray]).
 * - Pass 4 (3 to 4): Reverse trace (right to left) in light grey ([CaretPass4Gray]).
 * - Pass 5 (4 to 5): Forward trace (left to right) in pure white ([CaretPass5White]).
 *
 * Tactile micro-haptics fire at each apex notch ([HapticEngine.rotaryDetent]),
 * pass completion ([HapticEngine.targetSnap]), and full completion ([HapticEngine.relaySnap]).
 *
 * Back-swipe or tapping "Go back" immediately aborts the gate and resets progress.
 *
 * @param onConfirmOpen Dispatched when all 5 passes finish and user taps "Open".
 * @param onDismiss Dispatched when the user abandons the checkpoint.
 * @param hapticEngine Tactile feedback provider.
 * @param totalDurationMs Total time for the full 5-pass sequence (default 17,000ms).
 */
@Composable
fun CaretTracingSurface(
    onConfirmOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hapticEngine: HapticEngine = NoOpHapticEngine,
    totalDurationMs: Long = 17_000L,
    embedded: Boolean = false,
) {
    BackHandler(enabled = !embedded, onBack = onDismiss)

    val currentHapticEngine by rememberUpdatedState(hapticEngine)

    // Continuous progress from 0f to 5f across the 5 passes
    val totalProgress = remember { Animatable(0f) }

    var isComplete by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 10.dp.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val toastPath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }
    var measuredCanvasSize by remember { mutableStateOf(Size.Zero) }
    val pass1Path = remember { Path() }
    val pass2Path = remember { Path() }
    val pass3Path = remember { Path() }
    val pass4Path = remember { Path() }
    val pass5Path = remember { Path() }

    LaunchedEffect(totalDurationMs) {
        withContext(UnscaledMotionDurationScale) {
            isComplete = false
            totalProgress.snapTo(0f)

            var firedPass1Apex = false
            var firedPass1End = false
            var firedPass2Apex = false
            var firedPass2End = false
            var firedPass3Apex = false
            var firedPass3End = false
            var firedPass4Apex = false
            var firedPass4End = false
            var firedPass5Apex = false

            totalProgress.animateTo(
                targetValue = CARET_PASS_COUNT,
                animationSpec = tween(durationMillis = totalDurationMs.toInt(), easing = LinearEasing),
            ) {
                val v = value
                if (!firedPass1Apex && v >= 0.5f) {
                    firedPass1Apex = true
                    currentHapticEngine.rotaryDetent()
                }
                if (!firedPass1End && v >= 1.0f) {
                    firedPass1End = true
                    currentHapticEngine.targetSnap()
                }
                if (!firedPass2Apex && v >= 1.5f) {
                    firedPass2Apex = true
                    currentHapticEngine.rotaryDetent()
                }
                if (!firedPass2End && v >= 2.0f) {
                    firedPass2End = true
                    currentHapticEngine.targetSnap()
                }
                if (!firedPass3Apex && v >= 2.5f) {
                    firedPass3Apex = true
                    currentHapticEngine.rotaryDetent()
                }
                if (!firedPass3End && v >= 3.0f) {
                    firedPass3End = true
                    currentHapticEngine.targetSnap()
                }
                if (!firedPass4Apex && v >= 3.5f) {
                    firedPass4Apex = true
                    currentHapticEngine.rotaryDetent()
                }
                if (!firedPass4End && v >= 4.0f) {
                    firedPass4End = true
                    currentHapticEngine.targetSnap()
                }
                if (!firedPass5Apex && v >= 4.5f) {
                    firedPass5Apex = true
                    currentHapticEngine.rotaryDetent()
                }
            }

            currentHapticEngine.relaySnap()
            isComplete = true
        }
    }

    val rootModifier = if (embedded) {
        modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .testTag("caret_tracing_surface")
    } else {
        modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .testTag("caret_tracing_surface")
    }

    Column(modifier = rootModifier) {
        // Centered Canvas Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(180.dp)
                    .testTag("caret_tracing_canvas"),
            ) {
                val progress = totalProgress.value
                val p1 = progress.coerceIn(0f, 1f)
                val p2 = (progress - 1f).coerceIn(0f, 1f)
                val p3 = (progress - 2f).coerceIn(0f, 1f)
                val p4 = (progress - 3f).coerceIn(0f, 1f)
                val p5 = (progress - 4f).coerceIn(0f, 1f)

                if (measuredCanvasSize != size) {
                    buildToastOutlinePath(toastPath, size)
                    pathMeasure.setPath(toastPath, forceClosed = true)
                    measuredCanvasSize = size
                }
                val totalLength = pathMeasure.length

                fun drawSegment(targetPath: Path, startFrac: Float, endFrac: Float, color: Color) {
                    if (endFrac <= startFrac || totalLength <= 0f) return
                    targetPath.reset()
                    pathMeasure.getSegment(
                        startDistance = startFrac.coerceIn(0f, 1f) * totalLength,
                        stopDistance = endFrac.coerceIn(0f, 1f) * totalLength,
                        destination = targetPath,
                        startWithMoveTo = true,
                    )
                    drawPath(targetPath, color = color, style = stroke)
                }

                // Pass 1: Forward (left to right)
                if (p1 > 0f) {
                    drawSegment(pass1Path, 0f, p1, CaretPass1Gray)
                }

                // Pass 2: Reverse (right to left)
                if (p2 > 0f) {
                    drawSegment(pass2Path, 1f - p2, 1f, CaretPass2Gray)
                }

                // Pass 3: Forward (left to right)
                if (p3 > 0f) {
                    drawSegment(pass3Path, 0f, p3, CaretPass3Gray)
                }

                // Pass 4: Reverse (right to left)
                if (p4 > 0f) {
                    drawSegment(pass4Path, 1f - p4, 1f, CaretPass4Gray)
                }

                // Pass 5: Forward (left to right)
                if (p5 > 0f) {
                    drawSegment(pass5Path, 0f, p5, CaretPass5White)
                }
            }
        }

        // Bottom Action Button: "Go back" (SubtleGray) morphs into "Open" (PureWhite)
        Box(
            modifier = Modifier
                .padding(horizontal = if (embedded) 16.dp else 24.dp, vertical = if (embedded) 12.dp else 24.dp),
        ) {
            AnimatedContent(
                targetState = isComplete,
                transitionSpec = {
                    fadeIn(
                        animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
                    ) togetherWith
                        fadeOut(animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_CLOSE_DURATION_MS))
                },
                label = "CaretTracingActionButtonTransition",
            ) { complete ->
                Text(
                    text = stringResource(if (complete) R.string.action_open else R.string.action_go_back),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (complete) PureWhite else SubtleGray,
                    modifier = Modifier
                        .clickable {
                            if (complete) {
                                onConfirmOpen()
                            } else {
                                onDismiss()
                            }
                        }
                        .padding(vertical = 8.dp)
                        .testTag("caret_tracing_action_button"),
                )
            }
        }
    }
}
