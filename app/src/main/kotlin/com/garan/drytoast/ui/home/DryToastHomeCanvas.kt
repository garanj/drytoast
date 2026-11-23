package com.garan.drytoast.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.garan.drytoast.R
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.ui.settings.DryToastIcons
import com.garan.drytoast.ui.theme.profileBaseColor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val HOME_DIALER_BUTTON_TEST_TAG = "home_dialer_button"

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val SizerReferenceProfile = CustomProfile(id = "__sizer__", name = "M", colorIndex = 0)

/**
 * Clean typographic home display for Dry Toast.
 * Features monospaced tabular numerals (`tnum, zero`), non-drifting baseline alignment,
 * edge-to-edge safe drawing insets, and subtle mechanical precision styling.
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

    if (timeText == null || dateText == null) {
        val context = LocalContext.current
        val isInspection = LocalInspectionMode.current
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Top,
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

            content()
        }

        if (showDialerIcon) {
            IconButton(
                onClick = onDialerClick,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag(HOME_DIALER_BUTTON_TEST_TAG),
            ) {
                Icon(
                    imageVector = DryToastIcons.Dialpad,
                    contentDescription = stringResource(R.string.cd_open_dialer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
