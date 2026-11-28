package com.garan.drytoast.ui.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.garan.drytoast.R
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.ui.settings.DryToastIcons
import com.garan.drytoast.ui.theme.profileBaseColor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.emptyFlow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val HOME_DIALER_BUTTON_TEST_TAG = "home_dialer_button"
const val HOME_HEADER_STANDARD_TEST_TAG = "home_header_standard"
const val HOME_HEADER_COMPACT_TEST_TAG = "home_header_compact"
const val HOME_CANVAS_CONTENT_TEST_TAG = "home_canvas_content"

internal val CEREMONIAL_CANVAS_MAX_WIDTH = 600.dp
internal val COMPACT_CANVAS_HEIGHT_THRESHOLD = 440.dp
private val ULTRA_COMPACT_CANVAS_HEIGHT_THRESHOLD = 250.dp
private val MIN_TABLETOP_PANE_HEIGHT = 140.dp

internal val LocalHomeCanvasCompact = compositionLocalOf { false }

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val SizerReferenceProfile = CustomProfile(id = "__sizer__", name = "M", colorIndex = 0)

/**
 * Clean typographic home display for Dry Toast.
 * Features monospaced tabular numerals (`tnum, zero`), non-drifting baseline alignment,
 * edge-to-edge safe drawing insets (including IME), compact single-row instrument mode on
 * short viewports, and crease-aware split zones in horizontal half-opened Tabletop posture.
 */
@Composable
fun DryToastHomeCanvas(
    modifier: Modifier = Modifier,
    timeText: String? = null,
    dateText: String? = null,
    statusText: String = stringResource(R.string.app_name),
    activeProfiles: ImmutableList<CustomProfile> = persistentListOf(),
    hasCapabilityIssues: Boolean = false,
    onCapabilityFaultClick: () -> Unit = {},
    showDialerIcon: Boolean = true,
    onDialerClick: () -> Unit = {},
    contentTopSpacing: Dp = 0.dp,
    clockProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    onTimeTick: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    // Initialize synchronously on Frame 0 so the canvas never flashes "00:00" or "Dry Toast"
    var liveTime by remember {
        mutableStateOf(if (timeText == null) clockProvider().format(TIME_FORMATTER) else "")
    }
    var liveDate by remember {
        mutableStateOf(if (dateText == null) clockProvider().format(DATE_FORMATTER) else "")
    }

    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current

    if (timeText == null || dateText == null) {
        val lifecycleOwner = LocalLifecycleOwner.current

        if (!isInspection) {
            DisposableEffect(context, lifecycleOwner) {
                fun updateTime() {
                    val now = clockProvider()
                    liveTime = now.format(TIME_FORMATTER)
                    liveDate = now.format(DATE_FORMATTER)
                    onTimeTick()
                }

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        updateTime()
                    }
                }

                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }

                var isReceiverRegistered = false

                fun registerIfNeeded() {
                    if (!isReceiverRegistered) {
                        updateTime()
                        context.registerReceiver(receiver, filter)
                        isReceiverRegistered = true
                    }
                }

                fun unregisterIfNeeded() {
                    if (isReceiverRegistered) {
                        context.unregisterReceiver(receiver)
                        isReceiverRegistered = false
                    }
                }

                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> registerIfNeeded()
                        Lifecycle.Event.ON_STOP -> unregisterIfNeeded()
                        else -> {}
                    }
                }

                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    registerIfNeeded()
                }
                lifecycleOwner.lifecycle.addObserver(observer)

                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    unregisterIfNeeded()
                }
            }
        }
    }

    val activity = remember(context) { context.findActivity() }
    val windowLayoutInfoFlow = remember(context, activity, isInspection) {
        if (!isInspection && activity != null) {
            WindowInfoTracker.getOrCreate(context).windowLayoutInfo(activity)
        } else {
            emptyFlow()
        }
    }
    val windowLayoutInfo by windowLayoutInfoFlow.collectAsStateWithLifecycle(initialValue = null)
    val horizontalTabletopFold = remember(windowLayoutInfo) {
        windowLayoutInfo?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull {
                it.orientation == FoldingFeature.Orientation.HORIZONTAL &&
                    it.state == FoldingFeature.State.HALF_OPENED
            }
    }

    val displayTime = timeText ?: liveTime
    val displayDate = dateText ?: liveDate
    val upperStatusText = remember(statusText) { statusText.uppercase() }
    val baseLabelSmall = MaterialTheme.typography.labelSmall
    val statusTextStyle = remember(baseLabelSmall) {
        baseLabelSmall.copy(
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }

    val density = LocalDensity.current
    var safeAreaBoundsInWindow by remember { mutableStateOf<Rect?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .onGloballyPositioned { coords ->
                safeAreaBoundsInWindow = coords.boundsInWindow()
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val safeTopPx = safeAreaBoundsInWindow?.top ?: 0f
        val safeBottomPx = safeAreaBoundsInWindow?.bottom ?: constraints.maxHeight.toFloat()
        val minPanePx = with(density) { MIN_TABLETOP_PANE_HEIGHT.toPx() }
        val minFoldBufferPx = with(density) { 16.dp.toPx() }

        val tabletopSplitSpec = horizontalTabletopFold?.let { fold ->
            val rawFoldTop = fold.bounds.top.toFloat()
            val rawFoldBottom = fold.bounds.bottom.toFloat()
            val topPanePx = rawFoldTop - safeTopPx
            val bottomPanePx = safeBottomPx - rawFoldBottom
            if (topPanePx >= minPanePx && bottomPanePx >= minPanePx) {
                val creaseSpanPx = (rawFoldBottom - rawFoldTop).coerceAtLeast(minFoldBufferPx)
                val adjustedTopPx = (topPanePx - ((creaseSpanPx - (rawFoldBottom - rawFoldTop)) / 2f))
                    .coerceAtLeast(minPanePx)
                with(density) {
                    TabletopSplitSpec(
                        topPaneHeight = adjustedTopPx.toDp(),
                        creaseHeight = creaseSpanPx.toDp(),
                        bottomPaneHeight = bottomPanePx.toDp(),
                    )
                }
            } else {
                null
            }
        }

        if (tabletopSplitSpec != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Upper upright screen above horizontal crease: dedicated ambient instrument readout
                Column(
                    modifier = Modifier
                        .widthIn(max = CEREMONIAL_CANVAS_MAX_WIDTH)
                        .fillMaxWidth()
                        .height(tabletopSplitSpec.topPaneHeight)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    if (tabletopSplitSpec.topPaneHeight < 220.dp) {
                        CompactHomeHeader(
                            displayTime = displayTime,
                            displayDate = displayDate,
                            upperStatusText = upperStatusText,
                            statusTextStyle = statusTextStyle,
                            activeProfiles = activeProfiles,
                            hasCapabilityIssues = hasCapabilityIssues,
                            onCapabilityFaultClick = onCapabilityFaultClick,
                            isUltraCompact = false,
                        )
                    } else {
                        StandardHomeHeader(
                            displayTime = displayTime,
                            displayDate = displayDate,
                            upperStatusText = upperStatusText,
                            statusTextStyle = statusTextStyle,
                            activeProfiles = activeProfiles,
                            hasCapabilityIssues = hasCapabilityIssues,
                            onCapabilityFaultClick = onCapabilityFaultClick,
                        )
                    }
                }

                // Untouched AMOLED black gap spanning the physical horizontal hinge crease
                Spacer(modifier = Modifier.height(tabletopSplitSpec.creaseHeight))

                // Lower flat tabletop half below crease: Search bar, Quick Actions / Results, Dialer
                val compactBottomPane = tabletopSplitSpec.bottomPaneHeight < 300.dp
                Column(
                    modifier = Modifier
                        .widthIn(max = CEREMONIAL_CANVAS_MAX_WIDTH)
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(HOME_CANVAS_CONTENT_TEST_TAG)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        CompositionLocalProvider(LocalHomeCanvasCompact provides compactBottomPane) {
                            content()
                        }
                    }

                    if (showDialerIcon) {
                        HomeDialerButton(
                            onClick = onDialerClick,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        } else {
            val isCompactHeader =
                maxHeight < COMPACT_CANVAS_HEIGHT_THRESHOLD || horizontalTabletopFold != null
            val isUltraCompact = maxHeight < ULTRA_COMPACT_CANVAS_HEIGHT_THRESHOLD
            val outerVerticalPadding = when {
                isUltraCompact -> 4.dp
                isCompactHeader -> 8.dp
                else -> 16.dp
            }

            Column(
                modifier = Modifier
                    .widthIn(max = CEREMONIAL_CANVAS_MAX_WIDTH)
                    .fillMaxSize()
                    .testTag(HOME_CANVAS_CONTENT_TEST_TAG)
                    .padding(horizontal = 24.dp, vertical = outerVerticalPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Top,
                ) {
                    if (isCompactHeader) {
                        CompactHomeHeader(
                            displayTime = displayTime,
                            displayDate = displayDate,
                            upperStatusText = upperStatusText,
                            statusTextStyle = statusTextStyle,
                            activeProfiles = activeProfiles,
                            hasCapabilityIssues = hasCapabilityIssues,
                            onCapabilityFaultClick = onCapabilityFaultClick,
                            isUltraCompact = isUltraCompact,
                        )
                    } else {
                        StandardHomeHeader(
                            displayTime = displayTime,
                            displayDate = displayDate,
                            upperStatusText = upperStatusText,
                            statusTextStyle = statusTextStyle,
                            activeProfiles = activeProfiles,
                            hasCapabilityIssues = hasCapabilityIssues,
                            onCapabilityFaultClick = onCapabilityFaultClick,
                        )
                    }

                    if (contentTopSpacing > 0.dp) {
                        val effectiveTopSpacing = when {
                            isUltraCompact -> 4.dp.coerceAtMost(contentTopSpacing)
                            isCompactHeader -> 6.dp.coerceAtMost(contentTopSpacing)
                            else -> contentTopSpacing
                        }
                        Spacer(modifier = Modifier.height(effectiveTopSpacing))
                    }

                    CompositionLocalProvider(LocalHomeCanvasCompact provides isCompactHeader) {
                        content()
                    }
                }

                if (showDialerIcon) {
                    HomeDialerButton(
                        onClick = onDialerClick,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

private data class TabletopSplitSpec(val topPaneHeight: Dp, val creaseHeight: Dp, val bottomPaneHeight: Dp)

@Composable
private fun StandardHomeHeader(
    displayTime: String,
    displayDate: String,
    upperStatusText: String,
    statusTextStyle: TextStyle,
    activeProfiles: ImmutableList<CustomProfile>,
    hasCapabilityIssues: Boolean,
    onCapabilityFaultClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOME_HEADER_STANDARD_TEST_TAG),
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Digital clock with tabular figures and zero padding
        Text(
            text = displayTime,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Ambient calendar date
        Text(
            text = displayDate,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 1dp Hairline separator
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Ambient status readout + right-justified active profile & capability fault square indicators.
        // Uses Layout to measure an unplaced reference badge when no badges are active so the row's
        // vertical height never shifts when badges appear/disappear (at any font scale).
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Text(
                    text = upperStatusText,
                    style = statusTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ProfileAndFaultBadgeCluster(
                    activeProfiles = activeProfiles,
                    hasCapabilityIssues = hasCapabilityIssues,
                    onCapabilityFaultClick = onCapabilityFaultClick,
                )

                ActiveProfileSquareBadge(
                    profile = SizerReferenceProfile,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
        ) { measurables, constraints ->
            val badgesPlaceable = measurables[1].measure(constraints.copy(minWidth = 0))
            val sizerHeight = if (badgesPlaceable.width == 0) {
                measurables[2].measure(Constraints()).height
            } else {
                0
            }
            val textMaxWidth = (constraints.maxWidth - badgesPlaceable.width).coerceAtLeast(0)
            val textPlaceable = measurables[0].measure(
                constraints.copy(minWidth = 0, maxWidth = textMaxWidth),
            )

            val rowHeight = maxOf(textPlaceable.height, badgesPlaceable.height, sizerHeight)
            layout(constraints.maxWidth, rowHeight) {
                textPlaceable.placeRelative(
                    x = 0,
                    y = Alignment.CenterVertically.align(textPlaceable.height, rowHeight),
                )
                if (badgesPlaceable.width > 0) {
                    badgesPlaceable.placeRelative(
                        x = constraints.maxWidth - badgesPlaceable.width,
                        y = Alignment.CenterVertically.align(badgesPlaceable.height, rowHeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactHomeHeader(
    displayTime: String,
    displayDate: String,
    upperStatusText: String,
    statusTextStyle: TextStyle,
    activeProfiles: ImmutableList<CustomProfile>,
    hasCapabilityIssues: Boolean,
    onCapabilityFaultClick: () -> Unit,
    isUltraCompact: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOME_HEADER_COMPACT_TEST_TAG),
    ) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    text = displayDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = upperStatusText,
                    style = statusTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProfileAndFaultBadgeCluster(
                    activeProfiles = activeProfiles,
                    hasCapabilityIssues = hasCapabilityIssues,
                    onCapabilityFaultClick = onCapabilityFaultClick,
                )
                ActiveProfileSquareBadge(
                    profile = SizerReferenceProfile,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
        ) { measurables, constraints ->
            val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val badgesPlaceable = measurables[3].measure(looseConstraints)
            val sizerHeight = if (badgesPlaceable.width == 0) {
                measurables[4].measure(Constraints()).height
            } else {
                0
            }

            val timeGapPx = 10.dp.roundToPx()
            val sectionGapPx = 12.dp.roundToPx()
            val badgeGapPx = if (badgesPlaceable.width > 0) 8.dp.roundToPx() else 0

            val maxTimeWidth = (constraints.maxWidth - badgesPlaceable.width - badgeGapPx)
                .coerceAtLeast(0)
            val timePlaceable = measurables[0].measure(looseConstraints.copy(maxWidth = maxTimeWidth))

            val remainingAfterTimeAndBadges = (
                constraints.maxWidth -
                    timePlaceable.width -
                    badgesPlaceable.width -
                    badgeGapPx -
                    sectionGapPx
                ).coerceAtLeast(0)

            val statusNaturalMax = (remainingAfterTimeAndBadges - timeGapPx).coerceAtLeast(0)
            val statusCap = if (constraints.maxWidth < 520.dp.roundToPx()) {
                statusNaturalMax / 2
            } else {
                statusNaturalMax
            }
            val statusPlaceable = measurables[2].measure(looseConstraints.copy(maxWidth = statusCap))

            val dateMaxWidth = (
                remainingAfterTimeAndBadges -
                    statusPlaceable.width -
                    timeGapPx
                ).coerceAtLeast(0)
            val datePlaceable = measurables[1].measure(looseConstraints.copy(maxWidth = dateMaxWidth))

            val rowHeight = maxOf(
                timePlaceable.height,
                datePlaceable.height,
                statusPlaceable.height,
                badgesPlaceable.height,
                sizerHeight,
            )

            layout(constraints.maxWidth, rowHeight) {
                timePlaceable.placeRelative(
                    x = 0,
                    y = Alignment.CenterVertically.align(timePlaceable.height, rowHeight),
                )
                if (datePlaceable.width > 0) {
                    datePlaceable.placeRelative(
                        x = timePlaceable.width + timeGapPx,
                        y = Alignment.CenterVertically.align(datePlaceable.height, rowHeight),
                    )
                }
                val rightClusterWidth = statusPlaceable.width + badgeGapPx + badgesPlaceable.width
                val rightClusterStart = (constraints.maxWidth - rightClusterWidth).coerceAtLeast(0)
                if (statusPlaceable.width > 0) {
                    statusPlaceable.placeRelative(
                        x = rightClusterStart,
                        y = Alignment.CenterVertically.align(statusPlaceable.height, rowHeight),
                    )
                }
                if (badgesPlaceable.width > 0) {
                    badgesPlaceable.placeRelative(
                        x = constraints.maxWidth - badgesPlaceable.width,
                        y = Alignment.CenterVertically.align(badgesPlaceable.height, rowHeight),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isUltraCompact) 4.dp else 6.dp))

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ProfileAndFaultBadgeCluster(
    activeProfiles: ImmutableList<CustomProfile>,
    hasCapabilityIssues: Boolean,
    onCapabilityFaultClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasCapabilityIssues) {
            CapabilityFaultSquareBadge(onClick = onCapabilityFaultClick)
        }
        activeProfiles.forEach { profile ->
            ActiveProfileSquareBadge(profile = profile)
        }
    }
}

@Composable
private fun HomeDialerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag(HOME_DIALER_BUTTON_TEST_TAG),
    ) {
        Icon(
            imageVector = DryToastIcons.Dialpad,
            contentDescription = stringResource(R.string.cd_open_dialer),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Compact square indicator displaying the single starting letter of an active [CustomProfile]
 * with a dark-tinted translucent background derived from the profile's assigned color.
 */
@Composable
internal fun ActiveProfileSquareBadge(profile: CustomProfile, modifier: Modifier = Modifier) {
    val baseColor = profileBaseColor(profile.colorIndex)
    val badgeFill = baseColor.copy(alpha = 0.24f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    val badgeBorder = baseColor.copy(alpha = 0.65f)
        .compositeOver(MaterialTheme.colorScheme.outlineVariant)

    val cdActiveProfile = stringResource(R.string.cd_active_profile, profile.name)
    Surface(
        modifier = modifier
            .size(22.dp)
            .semantics {
                contentDescription = cdActiveProfile
            },
        shape = RoundedCornerShape(3.dp),
        color = badgeFill,
        border = BorderStroke(1.dp, badgeBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = profile.initialLetter,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Compact square fault indicator (`[!]`) shown in the status bar cluster when one or more
 * enabled profiles are missing required OS permissions or hardware capabilities.
 */
@Composable
internal fun CapabilityFaultSquareBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cdMissingPermissions = stringResource(R.string.cd_missing_profile_permissions)
    Surface(
        modifier = modifier
            .size(22.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = cdMissingPermissions
            },
        shape = RoundedCornerShape(3.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "!",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
