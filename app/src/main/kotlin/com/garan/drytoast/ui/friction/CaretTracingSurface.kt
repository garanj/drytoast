package com.garan.drytoast.ui.friction

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.garan.drytoast.R
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.NoOpHapticEngine
import com.garan.drytoast.ui.motion.DryToastMotion
import com.garan.drytoast.ui.theme.OutlineGray
import com.garan.drytoast.ui.theme.PureWhite
import com.garan.drytoast.ui.theme.SubtleGray
import com.garan.drytoast.ui.theme.SurfaceContainerLow
import com.garan.drytoast.ui.theme.ToasterBodyFront
import com.garan.drytoast.ui.theme.ToasterBodySide
import com.garan.drytoast.ui.theme.ToasterBodyTop
import com.garan.drytoast.ui.theme.ToasterBreadIdleCrust
import com.garan.drytoast.ui.theme.ToasterBreadIdleCrustFill
import com.garan.drytoast.ui.theme.ToasterBreadIdleOutline
import com.garan.drytoast.ui.theme.ToasterBreadLoweredCrust
import com.garan.drytoast.ui.theme.ToasterBreadLoweredCrustFill
import com.garan.drytoast.ui.theme.ToasterBreadNewestCrust
import com.garan.drytoast.ui.theme.ToasterBreadNewestCrustFill
import com.garan.drytoast.ui.theme.ToasterBreadNewestFace
import com.garan.drytoast.ui.theme.ToasterBreadPoppedCrust
import com.garan.drytoast.ui.theme.ToasterBreadPoppedCrustFill
import com.garan.drytoast.ui.theme.ToasterBreadPoppedOutline
import com.garan.drytoast.ui.theme.ToasterFloorShadow
import com.garan.drytoast.ui.theme.ToasterNameplateBorder
import com.garan.drytoast.ui.theme.ToasterNameplateRule
import com.garan.drytoast.ui.theme.ToasterSlotBorder
import com.garan.drytoast.ui.theme.ToasterSlotRecess
import com.garan.drytoast.ui.theme.ToasterTrimGray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val SLICE_COUNT = 8
private const val TICKS_PER_SLICE = 4 // 500ms escapement tick at 2s/slice
private const val VIEWBOX_SIZE = 250f
private const val ISO_ORIGIN_X = 94f
private const val ISO_ORIGIN_Y = 136f
private const val ISO_COS = 0.8660254f
private const val ISO_SIN = 0.5f

private const val TOASTER_LENGTH = 132f
private const val TOASTER_WIDTH = 64f
private const val TOASTER_HEIGHT = 62f

private const val DIAL_CENTER_X_WORLD = TOASTER_LENGTH - 31f
private const val DIAL_CENTER_Z_WORLD = 34f
private const val DIAL_KNOB_SEGMENTS = 28
private const val DIAL_KNOB_RADIUS = 11.5f
private const val DIAL_NEEDLE_RADIUS = 9f

private const val LEVER_Y_WORLD = TOASTER_WIDTH * 0.5f
private const val LEVER_Z_UP = 44f
private const val LEVER_Z_DOWN = 22f
private const val LEVER_ARM_LENGTH = 12f
private const val LEVER_KNOB_OFFSET = 13f

private const val SLICE_FIRST_X = 10.5f
private const val SLICE_STEP_X = 15.8f
private const val SLICE_THICKNESS = 5.4f
private const val SLICE_SLOT_HALF_WIDTH = 3.5f
private const val SLICE_FULL_RISE = 31f
private const val SLICE_LOWERED_Z_OFFSET = -23f

private const val SLICE_DROP_DURATION_MS = 120
private const val SLICE_POP_DURATION_MS = 110
private const val SLICE_RESET_DURATION_MS = 100

/**
 * Enforces a 1.0x time scale for intentional friction delays so that system-level
 * `animator_duration_scale = 0.0` (e.g. Battery Saver or Accessibility "Remove animations")
 * cannot bypass or fast-forward the checkpoint gate.
 */
private object UnscaledMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/**
 * Pre-sampled unit tin-loaf bread slice profile in world `(y, zRelative)` coordinates.
 * Computed once at class initialization so frame rendering incurs zero Bezier or array allocations.
 */
private object DualitLoafProfile {
    val yCoords: FloatArray
    val zRelCoords: FloatArray
    val pointCount: Int

    init {
        val yCenter = TOASTER_WIDTH * 0.5f
        val rise = SLICE_FULL_RISE
        val wBody = 17.0f
        val wPinch = 15.2f
        val wShoulder = 20.5f

        val zPinch = rise * 0.44f
        val zShoulder = rise * 0.72f
        val zCrown = rise

        val yLBody = yCenter - wBody
        val yLPinch = yCenter - wPinch
        val yLSh = yCenter - wShoulder
        val yRBody = yCenter + wBody
        val yRPinch = yCenter + wPinch
        val yRSh = yCenter + wShoulder

        // 2 + 5 + 9 + 10 + 9 + 5 + 1 = 41 points
        val ys = ArrayList<Float>(42)
        val zs = ArrayList<Float>(42)

        fun addPoint(y: Float, z: Float) {
            ys.add(y)
            zs.add(z)
        }

        fun addCubic(
            y0: Float,
            z0: Float,
            y1: Float,
            z1: Float,
            y2: Float,
            z2: Float,
            y3: Float,
            z3: Float,
            steps: Int,
        ) {
            for (s in 1..steps) {
                val t = s.toFloat() / steps.toFloat()
                val u = 1f - t
                val uu = u * u
                val tt = t * t
                val uuu = uu * u
                val ttt = tt * t
                val y = uuu * y0 + 3f * uu * t * y1 + 3f * u * tt * y2 + ttt * y3
                val z = uuu * z0 + 3f * uu * t * z1 + 3f * u * tt * z2 + ttt * z3
                addPoint(y, z)
            }
        }

        addPoint(yLBody, 0f)
        addPoint(yLBody, rise * 0.34f)
        // Left tin-line indent
        addCubic(
            yLBody,
            rise * 0.34f,
            yLBody,
            rise * 0.40f,
            yLPinch,
            zPinch - 1.2f,
            yLPinch,
            zPinch,
            steps = 5,
        )
        // Left rounded mushroom shoulder
        addCubic(
            yLPinch,
            zPinch,
            yLSh - 1.2f,
            zPinch + 2.0f,
            yLSh - 0.8f,
            zShoulder + 3.5f,
            yLSh + 3.2f,
            zCrown - 2.4f,
            steps = 9,
        )
        // Domed top crust
        addCubic(
            yLSh + 3.2f,
            zCrown - 2.4f,
            yCenter - 8.5f,
            zCrown + 1.2f,
            yCenter + 8.5f,
            zCrown + 1.2f,
            yRSh - 3.2f,
            zCrown - 2.4f,
            steps = 10,
        )
        // Right rounded mushroom shoulder
        addCubic(
            yRSh - 3.2f,
            zCrown - 2.4f,
            yRSh + 0.8f,
            zShoulder + 3.5f,
            yRSh + 1.2f,
            zPinch + 2.0f,
            yRPinch,
            zPinch,
            steps = 9,
        )
        // Right tin-line indent back to straight lower side
        addCubic(
            yRPinch,
            zPinch,
            yRPinch,
            zPinch - 1.2f,
            yRBody,
            rise * 0.40f,
            yRBody,
            rise * 0.34f,
            steps = 5,
        )
        addPoint(yRBody, 0f)

        yCoords = ys.toFloatArray()
        zRelCoords = zs.toFloatArray()
        pointCount = yCoords.size
    }
}

private enum class SliceVisualMode {
    IDLE_UP,
    LOWERED,
    POPPED,
    POPPED_NEWEST,
}

/**
 * Size-dependent geometry, cached [Path]s, [Stroke] styles, and reusable scratch buffers for
 * zero-allocation 120Hz isometric toaster rendering inside [Modifier.drawWithCache].
 */
private class CachedToasterScene(width: Float, height: Float) {
    val scale: Float = minOf(width, height) / VIEWBOX_SIZE
    val offsetX: Float = (width - VIEWBOX_SIZE * scale) * 0.5f
    val offsetY: Float = (height - VIEWBOX_SIZE * scale) * 0.5f

    // Static body & slot paths
    val floorShadowPath = Path()
    val topDeckPath = Path()
    val frontFacePath = Path()
    val sideCheekPath = Path()
    val nameplatePath = Path()
    val dialKnobPath = Path()
    val slotPaths = Array(SLICE_COUNT) { Path() }

    // Pre-projected static line segments: [x1, y1, x2, y2, ...]
    val feetCoords = FloatArray(3 * 4)
    val trimCoords = FloatArray(2 * 4)
    val nameplateBarCoords = FloatArray(4)
    val dialTickCoords = FloatArray(SLICE_COUNT * 4)
    val leverTrackCoords = FloatArray(4)

    val dialCenterX: Float = isoX(DIAL_CENTER_X_WORLD, TOASTER_WIDTH)
    val dialCenterY: Float = isoY(DIAL_CENTER_X_WORLD, TOASTER_WIDTH, DIAL_CENTER_Z_WORLD)

    // Pre-allocated Stroke objects scaled to viewport
    val bodyStroke = Stroke(width = 2.2f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val trimStrokeWidth = 1.4f * scale
    val feetStrokeWidth = 5.0f * scale
    val nameplateBorderStroke = Stroke(width = 1.2f * scale, join = StrokeJoin.Round)
    val nameplateBarStrokeWidth = 1.6f * scale
    val slotStroke = Stroke(width = 1.1f * scale, join = StrokeJoin.Round)
    val dialLitStrokeWidth = 2.2f * scale
    val dialDimStrokeWidth = 1.3f * scale
    val dialKnobStroke = Stroke(width = 1.9f * scale, join = StrokeJoin.Round)
    val dialNeedleStrokeWidth = 2.4f * scale
    val dialHubRadius = 2.2f * scale
    val leverTrackStrokeWidth = 3.4f * scale
    val leverArmStrokeWidth = 3.6f * scale
    val leverKnobRadius = 4.0f * scale
    val leverIdleRingRadius = 12.0f * scale
    val leverIdleRingStroke = Stroke(
        width = 1.2f * scale,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * scale, 3f * scale), 0f),
    )
    val leverCueArrowStrokeWidth = 1.4f * scale

    val crustBackStroke = Stroke(width = 1.35f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val breadIdleStroke = Stroke(width = 1.6f * scale, join = StrokeJoin.Round)
    val breadLoweredStroke = Stroke(width = 1.6f * scale, join = StrokeJoin.Round)
    val breadPoppedStroke = Stroke(width = 1.9f * scale, join = StrokeJoin.Round)
    val breadNewestStroke = Stroke(width = 2.0f * scale, join = StrokeJoin.Round)

    // Reusable scratch buffers for per-frame slice rendering (zero heap allocation in onDrawBehind)
    val scratchCrustRibbonPath = Path()
    val scratchBackPath = Path()
    val scratchFrontPath = Path()
    val scratchClippedY = FloatArray(DualitLoafProfile.pointCount + 4)
    val scratchClippedZ = FloatArray(DualitLoafProfile.pointCount + 4)

    init {
        val l = TOASTER_LENGTH
        val w = TOASTER_WIDTH
        val h = TOASTER_HEIGHT

        // Floor shadow
        buildQuad(floorShadowPath, -4f, w + 5f, -6f, l + 4f, w + 5f, -6f, l + 6f, -2f, -6f, -2f, -2f, -6f)

        // 3 visible rubber feet
        writeSegment(feetCoords, 0, 10f, w - 2f, 0f, 10f, w - 2f, -6f)
        writeSegment(feetCoords, 4, l - 10f, w - 2f, 0f, l - 10f, w - 2f, -6f)
        writeSegment(feetCoords, 8, l - 2f, 10f, 0f, l - 2f, 10f, -6f)

        // Toaster housing faces
        buildQuad(topDeckPath, 0f, 0f, h, l, 0f, h, l, w, h, 0f, w, h)
        buildQuad(frontFacePath, 0f, w, 0f, l, w, 0f, l, w, h, 0f, w, h)
        buildQuad(sideCheekPath, l, 0f, 0f, l, w, 0f, l, w, h, l, 0f, h)

        // Lower trim band
        writeSegment(trimCoords, 0, 0f, w, 9f, l, w, 9f)
        writeSegment(trimCoords, 4, l, 0f, 9f, l, w, 9f)

        // Dualit nameplate badge
        buildQuad(nameplatePath, 14f, w, 40f, 56f, w, 40f, 56f, w, 26f, 14f, w, 26f)
        writeSegment(nameplateBarCoords, 0, 21f, w, 33f, 49f, w, 33f)

        // 8 radial dial ticks
        for (t in 0 until SLICE_COUNT) {
            val angle = (-PI / 2.0 + (t.toDouble() / SLICE_COUNT) * PI * 2.0).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)
            val tx1 = DIAL_CENTER_X_WORLD + cosA * 15f
            val tz1 = DIAL_CENTER_Z_WORLD - sinA * 15f
            val tx2 = DIAL_CENTER_X_WORLD + cosA * 18.5f
            val tz2 = DIAL_CENTER_Z_WORLD - sinA * 18.5f
            writeSegment(dialTickCoords, t * 4, tx1, w, tz1, tx2, w, tz2)
        }

        // Isometric circular dial knob on front plane (y = w)
        for (k in 0 until DIAL_KNOB_SEGMENTS) {
            val angle = ((k.toDouble() / DIAL_KNOB_SEGMENTS) * PI * 2.0).toFloat()
            val kx = DIAL_CENTER_X_WORLD + cos(angle) * DIAL_KNOB_RADIUS
            val kz = DIAL_CENTER_Z_WORLD - sin(angle) * DIAL_KNOB_RADIUS
            val sx = isoX(kx, w)
            val sy = isoY(kx, w, kz)
            if (k == 0) dialKnobPath.moveTo(sx, sy) else dialKnobPath.lineTo(sx, sy)
        }
        dialKnobPath.close()

        // Vertical carriage lever slot on right cheek (x = l, y = w / 2)
        writeSegment(leverTrackCoords, 0, l, LEVER_Y_WORLD, 15f, l, LEVER_Y_WORLD, 47f)

        // 8 recessed rectangular slot openings on top deck (z = h)
        for (i in 0 until SLICE_COUNT) {
            val sx = SLICE_FIRST_X + i * SLICE_STEP_X
            buildQuad(
                slotPaths[i],
                sx - SLICE_SLOT_HALF_WIDTH,
                10f,
                h,
                sx + SLICE_SLOT_HALF_WIDTH,
                10f,
                h,
                sx + SLICE_SLOT_HALF_WIDTH,
                w - 10f,
                h,
                sx - SLICE_SLOT_HALF_WIDTH,
                w - 10f,
                h,
            )
        }
    }

    fun isoX(x: Float, y: Float): Float = offsetX + (ISO_ORIGIN_X + (x - y) * ISO_COS) * scale

    fun isoY(x: Float, y: Float, z: Float): Float = offsetY + (ISO_ORIGIN_Y + (x + y) * ISO_SIN - z) * scale

    private fun buildQuad(
        target: Path,
        x0: Float,
        y0: Float,
        z0: Float,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        x3: Float,
        y3: Float,
        z3: Float,
    ) {
        target.reset()
        target.moveTo(isoX(x0, y0), isoY(x0, y0, z0))
        target.lineTo(isoX(x1, y1), isoY(x1, y1, z1))
        target.lineTo(isoX(x2, y2), isoY(x2, y2, z2))
        target.lineTo(isoX(x3, y3), isoY(x3, y3, z3))
        target.close()
    }

    private fun writeSegment(
        target: FloatArray,
        baseIndex: Int,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
    ) {
        target[baseIndex] = isoX(x1, y1)
        target[baseIndex + 1] = isoY(x1, y1, z1)
        target[baseIndex + 2] = isoX(x2, y2)
        target[baseIndex + 3] = isoY(x2, y2, z2)
    }

    /**
     * Clips [DualitLoafProfile] offset by [zOffset] to `z >= TOASTER_HEIGHT` directly into
     * [scratchClippedY] and [scratchClippedZ], returning the clipped vertex count.
     */
    fun clipProfileAtDeck(zOffset: Float): Int {
        val srcY = DualitLoafProfile.yCoords
        val srcZRel = DualitLoafProfile.zRelCoords
        val n = DualitLoafProfile.pointCount
        val zClip = TOASTER_HEIGHT
        val zBase = TOASTER_HEIGHT + zOffset

        var outCount = 0
        var prevY = 0f
        var prevZ = 0f
        var prevAbove = false

        for (i in 0 until n) {
            val currY = srcY[i]
            val currZ = zBase + srcZRel[i]
            val currAbove = currZ >= zClip

            if (i > 0 && prevAbove != currAbove) {
                val denom = currZ - prevZ
                val t = if (denom != 0f) (zClip - prevZ) / denom else 0f
                val yInt = prevY + t * (currY - prevY)
                if (!(currAbove && currZ == zClip)) {
                    scratchClippedY[outCount] = yInt
                    scratchClippedZ[outCount] = zClip
                    outCount++
                }
            }
            if (currAbove) {
                scratchClippedY[outCount] = currY
                scratchClippedZ[outCount] = currZ
                outCount++
            }

            prevY = currY
            prevZ = currZ
            prevAbove = currAbove
        }
        return outCount
    }

    fun drawScene(
        scope: DrawScope,
        poppedCount: Int,
        dialFraction: Float,
        leverFraction: Float,
        isHolding: Boolean,
        sliceOffsetsZ: Array<Animatable<Float, AnimationVector1D>>,
    ) {
        with(scope) {
            // Floor shadow
            drawPath(floorShadowPath, color = ToasterFloorShadow)

            // Rubber feet
            for (f in 0 until 3) {
                val idx = f * 4
                drawLine(
                    color = SubtleGray,
                    start = Offset(feetCoords[idx], feetCoords[idx + 1]),
                    end = Offset(feetCoords[idx + 2], feetCoords[idx + 3]),
                    strokeWidth = feetStrokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // 1. Top deck (z = H)
            drawPath(topDeckPath, color = ToasterBodyTop)
            drawPath(topDeckPath, color = PureWhite, style = bodyStroke)

            // 2. Front-left long face (y = W)
            drawPath(frontFacePath, color = ToasterBodyFront)
            drawPath(frontFacePath, color = PureWhite, style = bodyStroke)

            // 3. Front-right end cheek (x = L)
            drawPath(sideCheekPath, color = ToasterBodySide)
            drawPath(sideCheekPath, color = PureWhite, style = bodyStroke)

            // Dualit lower trim band
            drawLine(
                color = ToasterTrimGray,
                start = Offset(trimCoords[0], trimCoords[1]),
                end = Offset(trimCoords[2], trimCoords[3]),
                strokeWidth = trimStrokeWidth,
            )
            drawLine(
                color = ToasterTrimGray,
                start = Offset(trimCoords[4], trimCoords[5]),
                end = Offset(trimCoords[6], trimCoords[7]),
                strokeWidth = trimStrokeWidth,
            )

            // Dualit nameplate badge
            drawPath(nameplatePath, color = ToasterNameplateBorder, style = nameplateBorderStroke)
            drawLine(
                color = ToasterNameplateRule,
                start = Offset(nameplateBarCoords[0], nameplateBarCoords[1]),
                end = Offset(nameplateBarCoords[2], nameplateBarCoords[3]),
                strokeWidth = nameplateBarStrokeWidth,
            )

            // Rotary timer dial ticks
            for (t in 0 until SLICE_COUNT) {
                val idx = t * 4
                val lit = t < poppedCount
                drawLine(
                    color = if (lit) PureWhite else OutlineGray,
                    start = Offset(dialTickCoords[idx], dialTickCoords[idx + 1]),
                    end = Offset(dialTickCoords[idx + 2], dialTickCoords[idx + 3]),
                    strokeWidth = if (lit) dialLitStrokeWidth else dialDimStrokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // Rotary dial knob + sweeping needle
            drawPath(dialKnobPath, color = SurfaceContainerLow)
            drawPath(dialKnobPath, color = PureWhite, style = dialKnobStroke)

            val needleAngle = (-PI / 2.0 + dialFraction.toDouble() * PI * 2.0).toFloat()
            val needleWorldX = DIAL_CENTER_X_WORLD + cos(needleAngle) * DIAL_NEEDLE_RADIUS
            val needleWorldZ = DIAL_CENTER_Z_WORLD - sin(needleAngle) * DIAL_NEEDLE_RADIUS
            drawLine(
                color = PureWhite,
                start = Offset(dialCenterX, dialCenterY),
                end = Offset(isoX(needleWorldX, TOASTER_WIDTH), isoY(needleWorldX, TOASTER_WIDTH, needleWorldZ)),
                strokeWidth = dialNeedleStrokeWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = PureWhite,
                radius = dialHubRadius,
                center = Offset(dialCenterX, dialCenterY),
            )

            // Carriage lever track + animated lever handle
            drawLine(
                color = ToasterNameplateBorder,
                start = Offset(leverTrackCoords[0], leverTrackCoords[1]),
                end = Offset(leverTrackCoords[2], leverTrackCoords[3]),
                strokeWidth = leverTrackStrokeWidth,
                cap = StrokeCap.Round,
            )

            val leverZ = LEVER_Z_UP + (LEVER_Z_DOWN - LEVER_Z_UP) * leverFraction
            val leverBaseX = isoX(TOASTER_LENGTH, LEVER_Y_WORLD)
            val leverBaseY = isoY(TOASTER_LENGTH, LEVER_Y_WORLD, leverZ)
            val leverArmEndX = isoX(TOASTER_LENGTH + LEVER_ARM_LENGTH, LEVER_Y_WORLD)
            val leverArmEndY = isoY(TOASTER_LENGTH + LEVER_ARM_LENGTH, LEVER_Y_WORLD, leverZ)
            val knobX = isoX(TOASTER_LENGTH + LEVER_KNOB_OFFSET, LEVER_Y_WORLD)
            val knobY = isoY(TOASTER_LENGTH + LEVER_KNOB_OFFSET, LEVER_Y_WORLD, leverZ)

            if (!isHolding && poppedCount == 0) {
                drawCircle(
                    color = ToasterBreadIdleOutline,
                    radius = leverIdleRingRadius,
                    center = Offset(knobX, knobY),
                    style = leverIdleRingStroke,
                )
                // Downward mechanical push cue below knob
                val arrowTopY = knobY + 16f * scale
                val arrowBottomY = knobY + 25f * scale
                val arrowWing = 3f * scale
                drawLine(
                    color = SubtleGray,
                    start = Offset(knobX, arrowTopY),
                    end = Offset(knobX, arrowBottomY),
                    strokeWidth = leverCueArrowStrokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = SubtleGray,
                    start = Offset(knobX - arrowWing, arrowBottomY - arrowWing),
                    end = Offset(knobX, arrowBottomY),
                    strokeWidth = leverCueArrowStrokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = SubtleGray,
                    start = Offset(knobX + arrowWing, arrowBottomY - arrowWing),
                    end = Offset(knobX, arrowBottomY),
                    strokeWidth = leverCueArrowStrokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            drawLine(
                color = PureWhite,
                start = Offset(leverBaseX, leverBaseY),
                end = Offset(leverArmEndX, leverArmEndY),
                strokeWidth = leverArmStrokeWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = PureWhite,
                radius = leverKnobRadius,
                center = Offset(knobX, knobY),
            )

            // 8 recessed slots & 3D thick-cut tin-loaf slices (back-to-front painter's order)
            for (i in 0 until SLICE_COUNT) {
                drawPath(slotPaths[i], color = ToasterSlotRecess)
                drawPath(slotPaths[i], color = ToasterSlotBorder, style = slotStroke)

                val sliceX = SLICE_FIRST_X + i * SLICE_STEP_X
                val zOffset = sliceOffsetsZ[i].value
                val mode = when {
                    !isHolding && poppedCount == 0 -> SliceVisualMode.IDLE_UP
                    i < poppedCount -> {
                        if (i == poppedCount - 1) SliceVisualMode.POPPED_NEWEST else SliceVisualMode.POPPED
                    }
                    else -> SliceVisualMode.LOWERED
                }
                drawThickToastSlice(scope, sliceX, zOffset, mode)
            }
        }
    }

    private fun drawThickToastSlice(scope: DrawScope, sliceX: Float, zOffset: Float, mode: SliceVisualMode) {
        val count = clipProfileAtDeck(zOffset)
        if (count < 2) return

        val halfT = SLICE_THICKNESS * 0.5f
        val xBack = sliceX - halfT
        val xFront = sliceX + halfT

        val frontStrokeColor: Color
        val crustStrokeColor: Color
        val crustFillColor: Color
        val faceFillColor: Color
        val frontStrokeStyle: Stroke

        when (mode) {
            SliceVisualMode.POPPED_NEWEST -> {
                frontStrokeColor = PureWhite
                crustStrokeColor = ToasterBreadNewestCrust
                crustFillColor = ToasterBreadNewestCrustFill
                faceFillColor = ToasterBreadNewestFace
                frontStrokeStyle = breadNewestStroke
            }
            SliceVisualMode.POPPED -> {
                frontStrokeColor = ToasterBreadPoppedOutline
                crustStrokeColor = ToasterBreadPoppedCrust
                crustFillColor = ToasterBreadPoppedCrustFill
                faceFillColor = ToasterBodyFront
                frontStrokeStyle = breadPoppedStroke
            }
            SliceVisualMode.LOWERED -> {
                frontStrokeColor = SubtleGray
                crustStrokeColor = ToasterBreadLoweredCrust
                crustFillColor = ToasterBreadLoweredCrustFill
                faceFillColor = ToasterBodyFront
                frontStrokeStyle = breadLoweredStroke
            }
            SliceVisualMode.IDLE_UP -> {
                frontStrokeColor = ToasterBreadIdleOutline
                crustStrokeColor = ToasterBreadIdleCrust
                crustFillColor = ToasterBreadIdleCrustFill
                faceFillColor = ToasterBodyFront
                frontStrokeStyle = breadIdleStroke
            }
        }

        // Populate reusable scratch paths using rewind() to retain native Skia vertex buffers
        scratchBackPath.rewind()
        scratchFrontPath.rewind()
        scratchCrustRibbonPath.rewind()

        var prevBx = 0f
        var prevBy = 0f
        var prevFx = 0f
        var prevFy = 0f

        for (k in 0 until count) {
            val y = scratchClippedY[k]
            val z = scratchClippedZ[k]
            val bx = isoX(xBack, y)
            val by = isoY(xBack, y, z)
            val fx = isoX(xFront, y)
            val fy = isoY(xFront, y, z)
            if (k == 0) {
                scratchBackPath.moveTo(bx, by)
                scratchFrontPath.moveTo(fx, fy)
            } else {
                scratchBackPath.lineTo(bx, by)
                scratchFrontPath.lineTo(fx, fy)
                // Emit non-self-intersecting extruded crust quad for segment (k - 1) -> k
                scratchCrustRibbonPath.moveTo(prevBx, prevBy)
                scratchCrustRibbonPath.lineTo(bx, by)
                scratchCrustRibbonPath.lineTo(fx, fy)
                scratchCrustRibbonPath.lineTo(prevFx, prevFy)
                scratchCrustRibbonPath.close()
            }
            prevBx = bx
            prevBy = by
            prevFx = fx
            prevFy = fy
        }
        scratchFrontPath.close()

        with(scope) {
            // 1. Extruded 3D crust band (single drawPath over non-self-intersecting quad subpaths)
            drawPath(scratchCrustRibbonPath, color = crustFillColor)
            // 2. Back crust ridge outline
            drawPath(scratchBackPath, color = crustStrokeColor, style = crustBackStroke)
            // 3. Front slice face (occludes hidden back lines) + crisp Hollow Crust outline
            drawPath(scratchFrontPath, color = faceFillColor)
            drawPath(scratchFrontPath, color = frontStrokeColor, style = frontStrokeStyle)
        }
    }
}

/**
 * Interactive Isometric 8-Slice Dualit Toaster friction checkpoint.
 *
 * Imposes an intentional hold-to-toast mechanical delay (default 16s = 8 slices × 2s):
 * - Idle (lever up): all 8 thick-cut tin-loaf slices rest up in their slots (dim, un-toasted).
 * - Hold lever down: lowers the lever and all 8 slices together so only their curved top crusts
 *   peek above the recessed slots; the rotary dial ticks clockwise (`clockworkTick` every 500ms)
 *   and every 2s the next slice springs up (`targetSnap`).
 * - Early release (< 16s): springs the lever and lowered slices back up and resets progress to 0.
 * - Completion (8 / 8 slices): fires `relaySnap`, snaps the lever up, and reveals "Open".
 *
 * All animation reads are deferred to the Draw phase inside [Modifier.drawWithCache] with
 * zero per-frame object allocations.
 *
 * @param onConfirmOpen Dispatched when all 8 slices pop and the user taps "Open".
 * @param onDismiss Dispatched when the user abandons the checkpoint.
 * @param hapticEngine Tactile feedback provider.
 * @param totalDurationMs Total hold duration across all 8 slices (default 16,000ms).
 * @param embedded When true, adapts sizing and disables BackHandler for Onboarding Page 3 preview.
 * @param onInteractionActiveChanged Notifies parent containers (e.g. Onboarding pager) while lever is held.
 */
@Composable
fun CaretTracingSurface(
    onConfirmOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hapticEngine: HapticEngine = NoOpHapticEngine,
    totalDurationMs: Long = 16_000L,
    embedded: Boolean = false,
    onInteractionActiveChanged: (Boolean) -> Unit = {},
) {
    BackHandler(enabled = !embedded, onBack = onDismiss)

    val currentHapticEngine by rememberUpdatedState(hapticEngine)
    val currentOnInteractionActiveChanged by rememberUpdatedState(onInteractionActiveChanged)

    var isHolding by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var poppedCount by remember { mutableIntStateOf(0) }

    // Continuous mechanical animation states read strictly inside draw phase
    val toastProgress = remember { Animatable(0f) }
    val leverProgress = remember { Animatable(0f) }
    val sliceOffsetsZ = remember { Array(SLICE_COUNT) { Animatable(0f) } }

    // Ensure parent pager / scroll container is always unlocked if composable is disposed mid-hold
    DisposableEffect(Unit) {
        onDispose {
            currentOnInteractionActiveChanged(false)
        }
    }

    // Reset full state if totalDurationMs changes
    LaunchedEffect(totalDurationMs) {
        isHolding = false
        isComplete = false
        poppedCount = 0
        toastProgress.snapTo(0f)
        leverProgress.snapTo(0f)
        for (i in 0 until SLICE_COUNT) {
            sliceOffsetsZ[i].snapTo(0f)
        }
    }

    // Active toasting sequence while lever is held down
    LaunchedEffect(isHolding, totalDurationMs) {
        if (!isHolding || isComplete) return@LaunchedEffect

        withContext(UnscaledMotionDurationScale) {
            currentHapticEngine.resistanceDetent()
            poppedCount = 0
            toastProgress.snapTo(0f)

            coroutineScope {
                // Lower carriage lever and all 8 slices together in mechanical lockstep
                launch {
                    leverProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = DryToastMotion.opticalTween(SLICE_DROP_DURATION_MS),
                    )
                }
                for (i in 0 until SLICE_COUNT) {
                    launch {
                        sliceOffsetsZ[i].animateTo(
                            targetValue = SLICE_LOWERED_Z_OFFSET,
                            animationSpec = DryToastMotion.opticalTween(SLICE_DROP_DURATION_MS),
                        )
                    }
                }

                var lastPopped = 0
                var lastTick = 0

                toastProgress.animateTo(
                    targetValue = SLICE_COUNT.toFloat(),
                    animationSpec = tween(
                        durationMillis = totalDurationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
                        easing = LinearEasing,
                    ),
                ) {
                    val v = value
                    val currentTick = floor(v * TICKS_PER_SLICE).toInt()
                    if (currentTick > lastTick) {
                        var shouldTick = false
                        for (tickStep in (lastTick + 1)..currentTick) {
                            if (tickStep % TICKS_PER_SLICE != 0 && tickStep < SLICE_COUNT * TICKS_PER_SLICE) {
                                shouldTick = true
                            }
                        }
                        if (shouldTick) {
                            currentHapticEngine.clockworkTick()
                        }
                        lastTick = currentTick
                    }

                    val newlyPopped = floor(v).toInt().coerceIn(0, SLICE_COUNT)
                    if (newlyPopped > lastPopped) {
                        for (idx in lastPopped until newlyPopped) {
                            if (idx < SLICE_COUNT - 1) {
                                launch {
                                    sliceOffsetsZ[idx].animateTo(
                                        targetValue = 0f,
                                        animationSpec = DryToastMotion.opticalTween(SLICE_POP_DURATION_MS),
                                    )
                                }
                                currentHapticEngine.targetSnap()
                            }
                        }
                        lastPopped = newlyPopped
                        poppedCount = newlyPopped
                    }
                }

                // All 8 slices reached: fire final relay snap and transition to completed state.
                // Completion spring-up of lever and final slice runs in LaunchedEffect(isComplete)
                // so flipping isHolding = false never cancels the in-flight spring animations.
                poppedCount = SLICE_COUNT
                currentHapticEngine.relaySnap()
                isComplete = true
                isHolding = false
                currentOnInteractionActiveChanged(false)
            }
        }
    }

    // Completion spring-up: lever and final slice snap up without being cancelled by isHolding = false
    LaunchedEffect(isComplete) {
        if (!isComplete) return@LaunchedEffect
        coroutineScope {
            launch {
                leverProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = DryToastMotion.opticalTween(SLICE_RESET_DURATION_MS),
                )
            }
            for (i in 0 until SLICE_COUNT) {
                if (sliceOffsetsZ[i].value != 0f) {
                    launch {
                        sliceOffsetsZ[i].animateTo(
                            targetValue = 0f,
                            animationSpec = DryToastMotion.opticalTween(SLICE_POP_DURATION_MS),
                        )
                    }
                }
            }
        }
    }

    // Early release: spring lever & lowered slices back up and reset timer to 0
    LaunchedEffect(isHolding, isComplete) {
        if (isHolding || isComplete) return@LaunchedEffect
        val hadProgress = toastProgress.value > 0f || leverProgress.value > 0f || poppedCount > 0
        if (!hadProgress) return@LaunchedEffect

        currentHapticEngine.rotaryDetent()
        poppedCount = 0

        coroutineScope {
            launch {
                toastProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = DryToastMotion.opticalTween(SLICE_RESET_DURATION_MS),
                )
            }
            launch {
                leverProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = DryToastMotion.opticalTween(SLICE_RESET_DURATION_MS),
                )
            }
            for (i in 0 until SLICE_COUNT) {
                launch {
                    sliceOffsetsZ[i].animateTo(
                        targetValue = 0f,
                        animationSpec = DryToastMotion.opticalTween(SLICE_RESET_DURATION_MS),
                    )
                }
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val rootModifier = if (embedded) {
        modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .testTag("caret_tracing_surface")
    } else {
        modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .testTag("caret_tracing_surface")
    }

    val leverCd = stringResource(R.string.cd_toaster_lever)
    val idleHintText = stringResource(R.string.toaster_hint_idle)
    val stateDesc = when {
        isComplete -> stringResource(R.string.toaster_hint_ready, SLICE_COUNT)
        isHolding -> stringResource(R.string.toaster_hint_holding, poppedCount, SLICE_COUNT)
        else -> idleHintText
    }
    val showIdleHint = !isHolding && !isComplete

    val toasterBoxModifier = if (embedded) {
        Modifier.size(216.dp)
    } else {
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .aspectRatio(1f)
    }

    Column(modifier = rootModifier) {
        // Centered Isometric Dualit Toaster Stage — entire middle area acts as the hold-to-toast lever zone
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("caret_tracing_lever")
                .semantics {
                    role = Role.Button
                    contentDescription = leverCd
                    stateDescription = stateDesc
                }
                .pointerInput(isComplete) {
                    if (isComplete) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isHolding = true
                        currentOnInteractionActiveChanged(true)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    break
                                }
                                change.consume()
                            }
                        } finally {
                            isHolding = false
                            currentOnInteractionActiveChanged(false)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = toasterBoxModifier
                    .testTag("caret_tracing_canvas")
                    .drawWithCache {
                        if (size.width <= 0f || size.height <= 0f) {
                            return@drawWithCache onDrawBehind {}
                        }
                        val scene = CachedToasterScene(size.width, size.height)
                        onDrawBehind {
                            scene.drawScene(
                                scope = this,
                                poppedCount = poppedCount,
                                dialFraction = toastProgress.value / SLICE_COUNT.toFloat(),
                                leverFraction = leverProgress.value,
                                isHolding = isHolding,
                                sliceOffsetsZ = sliceOffsetsZ,
                            )
                        }
                    },
            )

            if (!embedded) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Show only the idle "Hold lever down to toast" cue; keep space reserved so the toaster never jumps
            Text(
                text = idleHintText,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .alpha(if (showIdleHint) 1f else 0f)
                    .testTag("caret_tracing_status_hint"),
            )
        }

        // Bottom Action Button: "Go back" morphs into "Open" with >= 48dp touch target
        Box(
            modifier = Modifier
                .padding(horizontal = if (embedded) 16.dp else 24.dp, vertical = if (embedded) 8.dp else 20.dp),
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
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button) {
                            if (complete) {
                                onConfirmOpen()
                            } else {
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("caret_tracing_action_button"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(if (complete) R.string.action_open else R.string.action_go_back),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (complete) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
