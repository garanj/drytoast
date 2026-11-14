package com.garan.threshold.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garan.threshold.R
import com.garan.threshold.core.model.FrictionChoice
import com.garan.threshold.core.port.HapticEngine
import com.garan.threshold.core.port.NoOpHapticEngine
import com.garan.threshold.ui.motion.ThresholdMotion
import com.garan.threshold.ui.settings.BaseToggleState
import com.garan.threshold.ui.settings.ThresholdIcons
import com.garan.threshold.ui.settings.ThresholdMultiStateToggle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

object OnboardingTestTags {
    const val ROOT = "onboarding_root"
    const val PAGER = "onboarding_pager"
    const val STEP_INDICATOR = "onboarding_step_indicator"
    const val BACK_BUTTON = "onboarding_back_button"
    const val NEXT_BUTTON = "onboarding_next_button"
    const val SET_DEFAULT_LAUNCHER_BUTTON = "onboarding_set_default_launcher_button"
    const val FINISH_BUTTON = "onboarding_finish_button"
    const val SET_BLACK_WALLPAPER_BUTTON = "onboarding_set_black_wallpaper_button"
    const val DEFAULT_LAUNCHER_STATUS = "onboarding_default_launcher_status"
    const val ROLE_REQUIRED_WARNING = "onboarding_role_required_warning"
    const val INTERACTIVE_TOGGLE = "onboarding_interactive_toggle"
    const val INTERACTIVE_TOGGLE_STATUS = "onboarding_interactive_toggle_status"
    const val FRICTION_CHIP_PREFIX = "onboarding_friction_chip_"
    const val FRICTION_PREVIEW_DESCRIPTION = "onboarding_friction_preview_description"
}

internal const val ONBOARDING_PAGE_COUNT = 6

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnboardingScreen(
    isDefaultLauncher: Boolean,
    onRequestDefaultLauncher: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    showRoleRequiredWarning: Boolean = false,
    onApplyPureBlackWallpaper: () -> Unit = {},
    hapticEngine: HapticEngine = NoOpHapticEngine,
    resetKey: Int = 0,
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(resetKey) {
        if (resetKey > 0 && pagerState.currentPage != 0) {
            pagerState.scrollToPage(0)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .drop(1)
            .collect {
                hapticEngine.rotaryDetent()
            }
    }

    BackHandler(enabled = true) {
        if (pagerState.currentPage > 0) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = pagerState.currentPage - 1,
                    animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_OPEN_DURATION_MS),
                )
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(OnboardingTestTags.ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        page = pagerState.currentPage - 1,
                                        animationSpec = ThresholdMotion.opticalTween(
                                            ThresholdMotion.SHUTTER_OPEN_DURATION_MS,
                                        ),
                                    )
                                }
                            },
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier.testTag(OnboardingTestTags.BACK_BUTTON),
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_back),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (pagerState.currentPage < ONBOARDING_PAGE_COUNT - 1) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        page = pagerState.currentPage + 1,
                                        animationSpec = ThresholdMotion.opticalTween(
                                            ThresholdMotion.SHUTTER_OPEN_DURATION_MS,
                                        ),
                                    )
                                }
                            },
                            shape = RoundedCornerShape(2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.testTag(OnboardingTestTags.NEXT_BUTTON),
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_next),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        if (isDefaultLauncher) {
                            Button(
                                onClick = {
                                    hapticEngine.targetSnap()
                                    onCompleteOnboarding()
                                },
                                shape = RoundedCornerShape(2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.testTag(OnboardingTestTags.FINISH_BUTTON),
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_enter_threshold),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    hapticEngine.rotaryDetent()
                                    onRequestDefaultLauncher()
                                },
                                shape = RoundedCornerShape(2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.testTag(OnboardingTestTags.SET_DEFAULT_LAUNCHER_BUTTON),
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_set_default_launcher),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            OnboardingHeaderBar(
                currentPage = pagerState.currentPage,
                totalPages = ONBOARDING_PAGE_COUNT,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(OnboardingTestTags.PAGER),
            ) { page ->
                when (page) {
                    0 -> InstrumentPhilosophyPage()
                    1 -> DeliberateLaunchPage()
                    2 -> IntentionalFrictionPage(hapticEngine = hapticEngine)
                    3 -> ContextualProfilesPage()
                    4 -> TamperResistancePage()
                    5 -> SetDefaultLauncherPage(
                        isDefaultLauncher = isDefaultLauncher,
                        showRoleRequiredWarning = showRoleRequiredWarning,
                        onApplyPureBlackWallpaper = onApplyPureBlackWallpaper,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHeaderBar(currentPage: Int, totalPages: Int, modifier: Modifier = Modifier) {
    val stepText = String.format("%02d / %02d", currentPage + 1, totalPages)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stepText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.testTag(OnboardingTestTags.STEP_INDICATOR),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(totalPages) { index ->
                val active = index <= currentPage
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun InstrumentPhilosophyPage() {
    OnboardingPageContainer(
        eyebrow = "01 // PHILOSOPHY",
        title = "THE INSTRUMENT",
        subtitle = "Quiet by default.",
        body = "Threshold replaces app grids, notification badges, and algorithmic feeds " +
            "with a pure AMOLED black typographic canvas—treating your smartphone like " +
            "a precision mechanical tool rather than an attention trap.",
    ) {
        OnboardingCalloutItem(
            label = "ZERO VISUAL NOISE",
            description = "No colourful app icons, unread counters, or infinite home screen pages.",
        )
        OnboardingCalloutItem(
            label = "INTENT GATEKEEPER",
            description = "Restricted apps are filtered cleanly at the launch surface " +
                "without invasive background screen-scraping.",
        )
        OnboardingCalloutItem(
            label = "OFFLINE & PRIVATE",
            description = "Zero internet permissions and zero telemetry. Every rule resides locally on your device.",
        )
    }
}

@Composable
private fun DeliberateLaunchPage() {
    OnboardingPageContainer(
        eyebrow = "02 // NAVIGATION",
        title = "DELIBERATE LAUNCH",
        subtitle = "Type what you intend to open.",
        body = "Tap the search prompt on the home canvas to filter installed apps instantly, " +
            "or pin up to 7 essential daily utilities beneath search.",
    ) {
        OnboardingCalloutItem(
            label = "INSTANT FUZZY SEARCH",
            description = "Type a few letters of any allowed app and press Go to launch with zero delay.",
        )
        OnboardingCalloutItem(
            label = "UP TO 7 QUICK ACTIONS",
            description = "Pin your most-used tools (01–07) directly below search for immediate one-tap access.",
        )
        OnboardingCalloutItem(
            label = "COLON COMMANDS",
            description = "Type :settings to open configuration or :wallpaper to apply " +
                "a solid AMOLED black wallpaper.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentionalFrictionPage(hapticEngine: HapticEngine) {
    var selectedToggleState by rememberSaveable { mutableStateOf(BaseToggleState.GUARD) }
    var selectedFrictionChoice by rememberSaveable { mutableStateOf(FrictionChoice.TYPED_INTENTION) }

    val toggleStatusDescription = when (selectedToggleState) {
        BaseToggleState.ALLOW -> "ALLOW: Launches immediately from search and quick actions with zero delay."
        BaseToggleState.GUARD -> "GUARD: Pauses launch behind a deliberate cognitive checkpoint before opening."
        BaseToggleState.BLOCK -> "BLOCK: Hidden from search results and disabled in quick actions."
    }

    val frictionPreviewTitle = when (selectedFrictionChoice) {
        FrictionChoice.TYPED_INTENTION -> "TYPED INTENTION"
        FrictionChoice.DRAG_SORT -> "DRAG-TO-MATCH"
        FrictionChoice.CARET_TRACING -> "CARET TRACING"
        FrictionChoice.RANDOM -> "RANDOM"
    }

    val frictionPreviewDescription = when (selectedFrictionChoice) {
        FrictionChoice.TYPED_INTENTION ->
            "Transcribe a randomized mindfulness phrase character-for-character " +
                "(e.g. \"I am opening Social Feed intentionally\") to unlock launch."
        FrictionChoice.DRAG_SORT ->
            "Drag geometric glyph pairs into their matching target slots with tactile snap detents."
        FrictionChoice.CARET_TRACING ->
            "Trace the Threshold caret steadily across its perimeter—releasing early resets the countdown."
        FrictionChoice.RANDOM ->
            "Dynamically selects a different cognitive checkpoint on every launch attempt."
    }

    OnboardingPageContainer(
        eyebrow = "03 // GATEKEEPING",
        title = "INTENTIONAL FRICTION",
        subtitle = "Three states for every app. Tap to test the detent.",
        body = "Assign every app to Allow, Guard, or Block. Tap the mechanical toggle below " +
            "to feel the micro-haptic detent for each tier:",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Social Feed",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selectedToggleState == BaseToggleState.BLOCK) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = "INTERACTIVE DEMO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                ThresholdMultiStateToggle(
                    options = BaseToggleState.entries,
                    selected = selectedToggleState,
                    onSelect = { selectedToggleState = it },
                    label = { it.label },
                    modifier = Modifier
                        .width(176.dp)
                        .testTag(OnboardingTestTags.INTERACTIVE_TOGGLE),
                    hapticEngine = hapticEngine,
                    isResistanceState = { it == BaseToggleState.BLOCK },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = toggleStatusDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(OnboardingTestTags.INTERACTIVE_TOGGLE_STATUS),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "COGNITIVE CHECKPOINTS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val previewChoices = remember {
            listOf(
                FrictionChoice.TYPED_INTENTION to "Typed Intention",
                FrictionChoice.DRAG_SORT to "Drag-to-Match",
                FrictionChoice.CARET_TRACING to "Caret Tracing",
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            previewChoices.forEach { (choice, label) ->
                val isSelected = selectedFrictionChoice == choice
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (!isSelected) {
                            hapticEngine.rotaryDetent()
                            selectedFrictionChoice = choice
                        }
                    },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.testTag(OnboardingTestTags.FRICTION_CHIP_PREFIX + choice.name),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            Text(
                text = frictionPreviewTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = frictionPreviewDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(OnboardingTestTags.FRICTION_PREVIEW_DESCRIPTION),
            )
        }
    }
}

@Composable
private fun ContextualProfilesPage() {
    OnboardingPageContainer(
        eyebrow = "04 // AUTOMATION",
        title = "CONTEXTUAL PROFILES",
        subtitle = "Rules that adapt to time, space, and hardware.",
        body = "Layer custom profiles such as Deep Work or Bedtime above your base configuration. " +
            "Profiles evaluate in priority order using AND / OR combinations of reusable triggers:",
    ) {
        OnboardingCalloutItem(
            label = "CIRCADIAN SCHEDULES",
            description = "Activate profiles automatically during recurring daily or overnight time windows.",
            icon = ThresholdIcons.Schedule,
        )
        OnboardingCalloutItem(
            label = "GEOFENCE ZONES",
            description = "Switch rules automatically when you are inside or outside " +
                "a saved location such as Home or Office.",
            icon = ThresholdIcons.LocationOn,
        )
        OnboardingCalloutItem(
            label = "PHYSICAL NFC TAGS",
            description = "Tap a physical NFC tag to latch a profile on/off or start " +
                "a timed session with daily tap limits.",
            icon = ThresholdIcons.Nfc,
        )
    }
}

@Composable
private fun TamperResistancePage() {
    OnboardingPageContainer(
        eyebrow = "05 // COMMITMENT",
        title = "TAMPER RESISTANCE",
        subtitle = "Press and hold to configure.",
        body = "Threshold keeps settings out of sight during daily use and provides optional " +
            "self-binding commitment locks when you want to prevent impulse edits:",
    ) {
        OnboardingCalloutItem(
            label = "3-SECOND PRESS & HOLD",
            description = "Press and hold on the home canvas for 3 seconds " +
                "(or type :settings in search) to open Settings.",
        )
        OnboardingCalloutItem(
            label = "REBOOT-STAGED CHANGES",
            description = "When enabled in Settings, any future edits to rules or profiles " +
                "are staged and only take effect after a full device restart.",
        )
        OnboardingCalloutItem(
            label = "ADB SETTINGS LOCK",
            description = "Power users can lock and unlock all configuration changes completely via ADB broadcast.",
        )
    }
}

@Composable
private fun SetDefaultLauncherPage(
    isDefaultLauncher: Boolean,
    showRoleRequiredWarning: Boolean,
    onApplyPureBlackWallpaper: () -> Unit,
) {
    OnboardingPageContainer(
        eyebrow = "06 // ACTIVATION",
        title = "SET DEFAULT LAUNCHER",
        subtitle = "Required to complete setup.",
        body = "To serve as your home screen and intent gatekeeper, Threshold must hold " +
            "the system Default Home App role. If Threshold is not set as your default launcher, " +
            "this wizard will run again next time you open the app.",
    ) {
        ListItem(
            leadingContent = if (isDefaultLauncher) {
                {
                    Icon(
                        imageVector = ThresholdIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                null
            },
            headlineContent = {
                Text(
                    text = if (isDefaultLauncher) {
                        stringResource(R.string.onboarding_default_launcher_active)
                    } else {
                        stringResource(R.string.onboarding_default_launcher_pending)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isDefaultLauncher) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.DEFAULT_LAUNCHER_STATUS),
        )

        if (showRoleRequiredWarning && !isDefaultLauncher) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_default_launcher_required_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(OnboardingTestTags.ROLE_REQUIRED_WARNING),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(16.dp))

        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.action_set_black_wallpaper),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.action_set_black_wallpaper_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = ThresholdIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.SET_BLACK_WALLPAPER_BUTTON)
                .clickable(onClick = onApplyPureBlackWallpaper),
        )
    }
}

@Composable
private fun OnboardingPageContainer(
    eyebrow: String,
    title: String,
    subtitle: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun OnboardingCalloutItem(label: String, description: String, icon: ImageVector? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 2.dp, end = 12.dp)
                        .size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
