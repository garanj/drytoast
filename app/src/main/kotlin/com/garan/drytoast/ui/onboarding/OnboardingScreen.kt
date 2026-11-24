package com.garan.drytoast.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garan.drytoast.R
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.NoOpHapticEngine
import com.garan.drytoast.ui.friction.CaretTracingSurface
import com.garan.drytoast.ui.friction.DragSortSurface
import com.garan.drytoast.ui.friction.TypedIntentionSurface
import com.garan.drytoast.ui.motion.DryToastMotion
import com.garan.drytoast.ui.settings.BaseToggleState
import com.garan.drytoast.ui.settings.DryToastIcons
import com.garan.drytoast.ui.settings.DryToastMultiStateToggle
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
    hapticEngine: HapticEngine = NoOpHapticEngine,
    initialPage: Int = 0,
    demoOverridePhrase: String? = null,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, ONBOARDING_PAGE_COUNT - 1),
        pageCount = { ONBOARDING_PAGE_COUNT },
    )
    val coroutineScope = rememberCoroutineScope()
    var isCheckpointDragActive by remember { mutableStateOf(false) }

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
                    animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
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
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                OnboardingHeaderBar(
                    currentPage = pagerState.currentPage,
                    totalPages = ONBOARDING_PAGE_COUNT,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
            }
        },
        bottomBar = {
            OnboardingBottomBar(
                currentPage = pagerState.currentPage,
                totalPages = ONBOARDING_PAGE_COUNT,
                isDefaultLauncher = isDefaultLauncher,
                onPreviousPage = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(
                            page = (pagerState.currentPage - 1).coerceAtLeast(0),
                            animationSpec = DryToastMotion.opticalTween(
                                DryToastMotion.SHUTTER_OPEN_DURATION_MS,
                            ),
                        )
                    }
                },
                onNextPage = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(
                            page = (pagerState.currentPage + 1).coerceAtMost(ONBOARDING_PAGE_COUNT - 1),
                            animationSpec = DryToastMotion.opticalTween(
                                DryToastMotion.SHUTTER_OPEN_DURATION_MS,
                            ),
                        )
                    }
                },
                onRequestDefaultLauncher = {
                    hapticEngine.rotaryDetent()
                    onRequestDefaultLauncher()
                },
                onCompleteOnboarding = {
                    hapticEngine.targetSnap()
                    onCompleteOnboarding()
                },
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCheckpointDragActive,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag(OnboardingTestTags.PAGER),
        ) { page ->
            when (page) {
                0 -> InstrumentPhilosophyPage()
                1 -> DeliberateLaunchPage()
                2 -> IntentionalFrictionPage(
                    hapticEngine = hapticEngine,
                    isDragActive = isCheckpointDragActive,
                    onDragActiveChanged = { isCheckpointDragActive = it },
                    demoOverridePhrase = demoOverridePhrase,
                )
                3 -> ContextualProfilesPage()
                4 -> TamperResistancePage()
                5 -> SetDefaultLauncherPage(
                    isDefaultLauncher = isDefaultLauncher,
                    showRoleRequiredWarning = showRoleRequiredWarning,
                )
            }
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    currentPage: Int,
    totalPages: Int,
    isDefaultLauncher: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRequestDefaultLauncher: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
            if (currentPage > 0) {
                OutlinedButton(
                    onClick = onPreviousPage,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.testTag(OnboardingTestTags.BACK_BUTTON),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_back),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (currentPage < totalPages - 1) {
                OnboardingPrimaryButton(
                    text = stringResource(R.string.onboarding_next),
                    testTag = OnboardingTestTags.NEXT_BUTTON,
                    onClick = onNextPage,
                )
            } else if (isDefaultLauncher) {
                OnboardingPrimaryButton(
                    text = stringResource(R.string.onboarding_enter_drytoast),
                    testTag = OnboardingTestTags.FINISH_BUTTON,
                    onClick = onCompleteOnboarding,
                )
            } else {
                OnboardingPrimaryButton(
                    text = stringResource(R.string.onboarding_set_default_launcher),
                    testTag = OnboardingTestTags.SET_DEFAULT_LAUNCHER_BUTTON,
                    onClick = onRequestDefaultLauncher,
                )
            }
        }
    }
}

@Composable
private fun OnboardingPrimaryButton(
    text: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.testTag(testTag),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OnboardingHeaderBar(currentPage: Int, totalPages: Int, modifier: Modifier = Modifier) {
    val stepText = stringResource(R.string.onboarding_step_indicator_format, currentPage + 1, totalPages)
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
        eyebrow = stringResource(R.string.onboarding_p1_eyebrow),
        title = stringResource(R.string.onboarding_p1_title),
        subtitle = stringResource(R.string.onboarding_p1_subtitle),
        body = stringResource(R.string.onboarding_p1_body),
    ) {
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p1_item1_label),
            description = stringResource(R.string.onboarding_p1_item1_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p1_item2_label),
            description = stringResource(R.string.onboarding_p1_item2_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p1_item3_label),
            description = stringResource(R.string.onboarding_p1_item3_desc),
        )
    }
}

@Composable
private fun DeliberateLaunchPage() {
    OnboardingPageContainer(
        eyebrow = stringResource(R.string.onboarding_p2_eyebrow),
        title = stringResource(R.string.onboarding_p2_title),
        subtitle = stringResource(R.string.onboarding_p2_subtitle),
        body = stringResource(R.string.onboarding_p2_body),
    ) {
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p2_item1_label),
            description = stringResource(R.string.onboarding_p2_item1_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p2_item2_label),
            description = stringResource(R.string.onboarding_p2_item2_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p2_item3_label),
            description = stringResource(R.string.onboarding_p2_item3_desc),
        )
    }
}

@Composable
private fun IntentionalFrictionPage(
    hapticEngine: HapticEngine,
    isDragActive: Boolean = false,
    onDragActiveChanged: (Boolean) -> Unit = {},
    demoOverridePhrase: String? = null,
) {
    var selectedToggleState by rememberSaveable { mutableStateOf(BaseToggleState.GUARD) }
    var selectedFrictionChoice by rememberSaveable { mutableStateOf(FrictionChoice.TYPED_INTENTION) }
    var demoResetNonce by rememberSaveable { mutableIntStateOf(0) }

    val toggleStatusDescription = when (selectedToggleState) {
        BaseToggleState.ALLOW -> stringResource(R.string.onboarding_p3_status_allow)
        BaseToggleState.GUARD -> stringResource(R.string.onboarding_p3_status_guard)
        BaseToggleState.BLOCK -> stringResource(R.string.onboarding_p3_status_block)
    }

    val frictionPreviewTitle = when (selectedFrictionChoice) {
        FrictionChoice.TYPED_INTENTION -> stringResource(R.string.onboarding_p3_preview_typed_title)
        FrictionChoice.DRAG_SORT -> stringResource(R.string.onboarding_p3_preview_drag_title)
        FrictionChoice.CARET_TRACING -> stringResource(R.string.onboarding_p3_preview_caret_title)
        FrictionChoice.RANDOM -> stringResource(R.string.onboarding_p3_preview_random_title)
    }

    val frictionPreviewDescription = when (selectedFrictionChoice) {
        FrictionChoice.TYPED_INTENTION -> stringResource(R.string.onboarding_p3_preview_typed_desc)
        FrictionChoice.DRAG_SORT -> stringResource(R.string.onboarding_p3_preview_drag_desc)
        FrictionChoice.CARET_TRACING -> stringResource(R.string.onboarding_p3_preview_caret_desc)
        FrictionChoice.RANDOM -> stringResource(R.string.onboarding_p3_preview_random_desc)
    }

    OnboardingPageContainer(
        eyebrow = stringResource(R.string.onboarding_p3_eyebrow),
        title = stringResource(R.string.onboarding_p3_title),
        subtitle = stringResource(R.string.onboarding_p3_subtitle),
        body = stringResource(R.string.onboarding_p3_body),
        scrollEnabled = !isDragActive,
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
                Text(
                    text = stringResource(R.string.onboarding_p3_demo_app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selectedToggleState == BaseToggleState.BLOCK) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(R.string.onboarding_p3_demo_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            DryToastMultiStateToggle(
                options = BaseToggleState.entries,
                selected = selectedToggleState,
                onSelect = { selectedToggleState = it },
                label = { stringResource(it.labelRes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.INTERACTIVE_TOGGLE),
                hapticEngine = hapticEngine,
                isResistanceState = { it == BaseToggleState.BLOCK },
            )

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
            text = stringResource(R.string.onboarding_p3_checkpoints_header),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val previewChoices = remember {
            listOf(
                FrictionChoice.TYPED_INTENTION,
                FrictionChoice.DRAG_SORT,
                FrictionChoice.CARET_TRACING,
            )
        }

        DryToastMultiStateToggle(
            options = previewChoices,
            selected = selectedFrictionChoice,
            onSelect = { selectedFrictionChoice = it },
            label = { choice ->
                when (choice) {
                    FrictionChoice.TYPED_INTENTION -> stringResource(R.string.friction_typed_intention)
                    FrictionChoice.DRAG_SORT -> stringResource(R.string.friction_drag_sort)
                    FrictionChoice.CARET_TRACING -> stringResource(R.string.friction_caret_tracing)
                    FrictionChoice.RANDOM -> stringResource(R.string.friction_random)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            hapticEngine = hapticEngine,
            maxLines = 2,
            optionTestTag = { OnboardingTestTags.FRICTION_CHIP_PREFIX + it.name },
        )

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
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            val demoAppLabel = stringResource(R.string.onboarding_p3_demo_app_name)
            key(selectedFrictionChoice, demoResetNonce) {
                when (selectedFrictionChoice) {
                    FrictionChoice.TYPED_INTENTION, FrictionChoice.RANDOM -> {
                        TypedIntentionSurface(
                            appLabel = demoAppLabel,
                            onConfirmOpen = { demoResetNonce++ },
                            onDismiss = { demoResetNonce++ },
                            hapticEngine = hapticEngine,
                            overridePhrase = demoOverridePhrase,
                            embedded = true,
                        )
                    }
                    FrictionChoice.DRAG_SORT -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        ) {
                            DragSortSurface(
                                onConfirmOpen = { demoResetNonce++ },
                                onDismiss = { demoResetNonce++ },
                                hapticEngine = hapticEngine,
                                pairCount = 3,
                                embedded = true,
                                onDragActiveChanged = onDragActiveChanged,
                            )
                        }
                    }
                    FrictionChoice.CARET_TRACING -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(292.dp),
                        ) {
                            CaretTracingSurface(
                                onConfirmOpen = { demoResetNonce++ },
                                onDismiss = { demoResetNonce++ },
                                hapticEngine = hapticEngine,
                                totalDurationMs = 8_000L,
                                embedded = true,
                                onInteractionActiveChanged = onDragActiveChanged,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextualProfilesPage() {
    OnboardingPageContainer(
        eyebrow = stringResource(R.string.onboarding_p4_eyebrow),
        title = stringResource(R.string.onboarding_p4_title),
        subtitle = stringResource(R.string.onboarding_p4_subtitle),
        body = stringResource(R.string.onboarding_p4_body),
    ) {
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p4_item1_label),
            description = stringResource(R.string.onboarding_p4_item1_desc),
            icon = DryToastIcons.Schedule,
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p4_item2_label),
            description = stringResource(R.string.onboarding_p4_item2_desc),
            icon = DryToastIcons.LocationOn,
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p4_item3_label),
            description = stringResource(R.string.onboarding_p4_item3_desc),
            icon = DryToastIcons.Nfc,
        )
    }
}

@Composable
private fun TamperResistancePage() {
    OnboardingPageContainer(
        eyebrow = stringResource(R.string.onboarding_p5_eyebrow),
        title = stringResource(R.string.onboarding_p5_title),
        subtitle = stringResource(R.string.onboarding_p5_subtitle),
        body = stringResource(R.string.onboarding_p5_body),
    ) {
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p5_item1_label),
            description = stringResource(R.string.onboarding_p5_item1_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p5_item2_label),
            description = stringResource(R.string.onboarding_p5_item2_desc),
        )
        OnboardingCalloutItem(
            label = stringResource(R.string.onboarding_p5_item3_label),
            description = stringResource(R.string.onboarding_p5_item3_desc),
        )
    }
}

@Composable
private fun SetDefaultLauncherPage(isDefaultLauncher: Boolean, showRoleRequiredWarning: Boolean) {
    OnboardingPageContainer(
        eyebrow = stringResource(R.string.onboarding_p6_eyebrow),
        title = stringResource(R.string.onboarding_p6_title),
        subtitle = stringResource(R.string.onboarding_p6_subtitle),
        body = stringResource(R.string.onboarding_p6_body),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .testTag(OnboardingTestTags.DEFAULT_LAUNCHER_STATUS),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isDefaultLauncher) {
                Icon(
                    imageVector = DryToastIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = if (isDefaultLauncher) {
                    stringResource(R.string.onboarding_default_launcher_active)
                } else {
                    stringResource(R.string.onboarding_default_launcher_pending)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (isDefaultLauncher) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (showRoleRequiredWarning && !isDefaultLauncher) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_default_launcher_required_warning),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.ROLE_REQUIRED_WARNING),
            )
        }
    }
}

@Composable
private fun OnboardingPageContainer(
    eyebrow: String,
    title: String,
    subtitle: String,
    body: String,
    scrollEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = scrollEnabled)
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
