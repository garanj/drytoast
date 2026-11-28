package com.garan.drytoast.ui.home

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.garan.drytoast.MainActivity
import com.garan.drytoast.core.model.AppAccessState
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.FrictionPolicy
import com.garan.drytoast.core.model.InstalledApp
import com.garan.drytoast.core.model.Profile
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.PackageChangeEvent
import com.garan.drytoast.core.port.PackageIndexSource
import com.garan.drytoast.core.search.SearchItem
import com.garan.drytoast.ui.launcher.AppLauncher
import com.garan.drytoast.ui.search.SEARCH_INPUT_TEST_TAG
import com.garan.drytoast.ui.search.SEARCH_PLACEHOLDER_TEST_TAG
import com.garan.drytoast.ui.search.SearchViewModel
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DryToastHomeScreenTest {

    private val publisherRule = WindowLayoutInfoPublisherRule()
    private val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testRule: RuleChain = RuleChain.outerRule(publisherRule).around(composeTestRule)

    private val cameraComponent = ComponentKey("com.android.camera", "com.android.camera.CameraActivity", 0L)

    private val fakePackageIndexSource = FakePackageIndexSource(
        initialApps = listOf(
            InstalledApp(cameraComponent, "Camera"),
        ),
    )

    @Test
    fun ambientState_backPressed_consumedWithoutFinishingActivity() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var imeDismissed = false

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    onDismissIme = { imeDismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("DRY TOAST").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type to launch...").assertIsDisplayed()

        // Press back via activity back dispatcher
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Verify activity is NOT finishing (back was consumed cleanly as a no-op)
        composeTestRule.activityRule.scenario.onActivity { activity ->
            assertThat(activity.isFinishing).isFalse()
        }
        assertThat(imeDismissed).isFalse()
        composeTestRule.onNodeWithText("DRY TOAST").assertIsDisplayed()
    }

    @Test
    fun searchActive_backPressed_clearsQueryAndDismissesSearch() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var imeDismissed = false

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    onDismissIme = { imeDismissed = true },
                )
            }
        }

        // Tap search placeholder to activate search
        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        // Type query into the editable text field
        composeTestRule.onNode(hasSetTextAction()).performTextInput("cam")
        composeTestRule.waitForIdle()

        assertThat(viewModel.uiState.value.query).isEqualTo("cam")
        composeTestRule.onNodeWithText("Camera").assertIsDisplayed()

        // Press back via activity back dispatcher
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Verify query cleared, IME dismissed, and back to ambient placeholder
        assertThat(viewModel.uiState.value.query).isEmpty()
        assertThat(imeDismissed).isTrue()
        composeTestRule.onNodeWithText("Type to launch...").assertIsDisplayed()
        composeTestRule.onNodeWithText("DRY TOAST").assertIsDisplayed()
    }

    @Test
    fun appLaunched_resetsSearchState() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("cam")
        composeTestRule.waitForIdle()

        // Click on the app item
        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.waitForIdle()

        assertThat(launcher.launchedComponents).containsExactly(cameraComponent)
        assertThat(viewModel.uiState.value.query).isEmpty()
        composeTestRule.onNodeWithText("Type to launch...").assertIsDisplayed()
    }

    @Test
    fun appLongPressed_opensAppInfoAndResetsSearch() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("cam")
        composeTestRule.waitForIdle()

        // Long-press on the app item (standard 500ms press — child ViewConfiguration must not require 3s)
        composeTestRule.onNodeWithText("Camera").performTouchInput {
            longClick(durationMillis = 500L)
        }
        composeTestRule.waitForIdle()

        assertThat(launcher.appInfoComponents).containsExactly(cameraComponent)
        assertThat(viewModel.uiState.value.query).isEmpty()
        composeTestRule.onNodeWithText("Type to launch...").assertIsDisplayed()
    }

    @Test
    fun guardedApp_imeGoOnPartialPrefix_doesNotBypassGatekeeper_andTriggersResistanceDetent() {
        val socialComponent = ComponentKey("com.example.social", "com.example.social.MainActivity", 0L)
        val guardedSource = FakePackageIndexSource(
            initialApps = listOf(InstalledApp(socialComponent, "Social Feed")),
        )
        val focusProfile = Profile(
            id = "focus",
            name = "Focus",
            defaultAccess = AppAccessState.Direct,
            appRules = mapOf(
                socialComponent to AppAccessState.Guarded(FrictionPolicy.TimedDelay(10)),
            ),
        )
        val viewModel = SearchViewModel(
            packageIndexSource = guardedSource,
            initialProfile = focusProfile,
        )
        val launcher = FakeAppLauncher()
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    hapticEngine = haptic,
                )
            }
        }

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        // Type partial prefix "soc" and press IME Go
        composeTestRule.onNode(hasSetTextAction()).performTextInput("soc")
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()

        // Guarded app must NOT launch without passing friction gatekeeper, and resistance detent fires
        assertThat(launcher.launchedComponents).isEmpty()
        assertThat(viewModel.uiState.value.query).isEqualTo("soc")
        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
    }

    @Test
    fun guardedAppClicked_triggersResistanceDetentHaptic() {
        val socialComponent = ComponentKey("com.example.social", "com.example.social.MainActivity", 0L)
        val guardedSource = FakePackageIndexSource(
            initialApps = listOf(InstalledApp(socialComponent, "Social Feed")),
        )
        val focusProfile = Profile(
            id = "focus",
            name = "Focus",
            defaultAccess = AppAccessState.Direct,
            appRules = mapOf(
                socialComponent to AppAccessState.Guarded(FrictionPolicy.TimedDelay(10)),
            ),
        )
        val viewModel = SearchViewModel(
            packageIndexSource = guardedSource,
            initialProfile = focusProfile,
        )
        val launcher = FakeAppLauncher()
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    hapticEngine = haptic,
                )
            }
        }

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("soc")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Social Feed").performClick()
        composeTestRule.waitForIdle()

        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
        assertThat(launcher.launchedComponents).isEmpty()
    }

    @Test
    fun appLongPressed_triggersResistanceDetentHaptic() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    hapticEngine = haptic,
                )
            }
        }

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("cam")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Camera").performTouchInput {
            longClick()
        }
        composeTestRule.waitForIdle()

        assertThat(haptic.resistanceDetentCount).isEqualTo(1)
        assertThat(launcher.appInfoComponents).containsExactly(cameraComponent)
    }

    @Test
    fun mainActivity_onNewIntentHome_collapsesActiveSearchAndClearsQuery() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()

            // Simulate user typing a search query on MainActivity
            activity.searchViewModel.activateSearch()
            activity.searchViewModel.onQueryChanged("cam")
            assertThat(activity.searchViewModel.uiState.value.isSearching).isTrue()
            assertThat(activity.searchViewModel.uiState.value.query).isEqualTo("cam")

            // Simulate pressing system Home button while already on MainActivity (singleTask)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            controller.newIntent(homeIntent)

            // Search must be collapsed and query cleared
            assertThat(activity.searchViewModel.uiState.value.query).isEmpty()
            assertThat(activity.searchViewModel.uiState.value.isSearching).isFalse()
        }
    }

    @Test
    fun mainActivity_onCreate_doesNotEagerlyLoadPackageIndexInAmbientMode() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()

            // Memory ceiling invariant: package index must remain unloaded in ambient home mode
            // to prevent ResourcesManager from mmapping foreign APK resource tables (+25 MB PSS)
            assertThat(activity.searchViewModel.isIndexLoaded).isFalse()
        }
    }

    @Test
    fun mainActivity_onNewIntentHome_whileInFrictionCheckpoint_collapsesFrictionAndReturnsHome() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            val youtubeApp = SearchItem.App(
                component = ComponentKey("com.google.android.youtube", "HomeActivity", 0L),
                label = "YouTube",
                accessState = AppAccessState.Guarded(FrictionPolicy.DragSort(6)),
            )

            activity.searchViewModel.openFriction(youtubeApp, FrictionPolicy.DragSort(6))
            assertThat(activity.searchViewModel.uiState.value.activeFriction).isNotNull()

            // Press Home button while on friction checkpoint
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            controller.newIntent(homeIntent)

            // Active friction checkpoint must be cleared
            assertThat(activity.searchViewModel.uiState.value.activeFriction).isNull()
        }
    }

    @Test
    fun frictionCheckpoint_onActivityStop_dismissesActiveFrictionGate() {
        val youtubeComponent = ComponentKey("com.google.android.youtube", "com.google.android.youtube.HomeActivity", 0L)
        val guardedSource = FakePackageIndexSource(
            initialApps = listOf(InstalledApp(youtubeComponent, "YouTube")),
        )
        val profile = Profile(
            id = "ambient",
            name = "Ambient",
            defaultAccess = AppAccessState.Direct,
            appRules = mapOf(
                youtubeComponent to AppAccessState.Guarded(FrictionPolicy.DragSort(6)),
            ),
        )
        val viewModel = SearchViewModel(
            packageIndexSource = guardedSource,
            initialProfile = profile,
        )
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        // Open friction screen
        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("You")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("YouTube").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drag_sort_surface").assertIsDisplayed()

        // Simulate locking screen / backgrounding activity (CREATED state triggers ON_STOP)
        composeTestRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeTestRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        // Friction gate must have been dismissed so it cannot linger across lock sessions
        assertThat(viewModel.uiState.value.activeFriction).isNull()
    }

    @Test
    fun guardedApp_dismissingFrictionScreen_placesSearchCursorAtEndOfQuery() {
        val youtubeComponent = ComponentKey("com.google.android.youtube", "com.google.android.youtube.HomeActivity", 0L)
        val guardedSource = FakePackageIndexSource(
            initialApps = listOf(InstalledApp(youtubeComponent, "YouTube")),
        )
        val profile = Profile(
            id = "ambient",
            name = "Ambient",
            defaultAccess = AppAccessState.Direct,
            appRules = mapOf(
                youtubeComponent to AppAccessState.Guarded(FrictionPolicy.DragSort(6)),
            ),
        )
        val viewModel = SearchViewModel(
            packageIndexSource = guardedSource,
            initialProfile = profile,
        )
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        // 1. Activate search and type "You"
        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("You")
        composeTestRule.waitForIdle()

        // 2. Tap YouTube -> navigates to DragSortSurface
        composeTestRule.onNodeWithText("YouTube").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drag_sort_surface").assertIsDisplayed()

        // 3. Swipe back (system back) to return to search
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // 4. Verify search field is restored with cursor at the END of "You" (index 3), not index 0
        val textFieldNode = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        val selection = textFieldNode.config[SemanticsProperties.TextSelectionRange]
        assertThat(selection).isEqualTo(TextRange(3, 3))

        // Typing "Tube" appends to "You" -> "YouTube" (instead of prepending "TubeYou")
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Tube")
        composeTestRule.waitForIdle()
        assertThat(viewModel.uiState.value.query).isEqualTo("YouTube")
    }

    @Test
    fun antiMetrics_whenRepositoryProvided_displaysFormattedStatus() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        val metricsFlow = kotlinx.coroutines.flow.MutableStateFlow(
            com.garan.drytoast.core.model.AntiMetrics(
                restDurationMs = 48 * 60_000L,
                unlockCount = 6,
            ),
        )
        val fakeRepo = object : com.garan.drytoast.core.port.AntiMetricsRepository {
            override val metrics: kotlinx.coroutines.flow.StateFlow<com.garan.drytoast.core.model.AntiMetrics> =
                metricsFlow
            override fun onScreenOff(timestampMs: Long) {}
            override fun onUnlock(timestampMs: Long) {}
        }

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    antiMetricsRepository = fakeRepo,
                )
            }
        }

        composeTestRule.onNodeWithText("REST 48M  ·  6 UNLOCKS").assertIsDisplayed()
    }

    @Test
    fun homeCanvas_pressUnderThreeSeconds_doesNotOpenSettings() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var settingsOpenedCount = 0

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    onOpenSettings = { settingsOpenedCount++ },
                )
            }
        }

        // Hold for 2,500ms (well above Android's default 400ms long-press, but below 3,000ms)
        composeTestRule.onNodeWithText("DRY TOAST").performTouchInput {
            down(center)
            advanceEventTime(2_500L)
            up()
        }
        composeTestRule.waitForIdle()

        assertThat(settingsOpenedCount).isEqualTo(0)
    }

    @Test
    fun homeCanvas_longPressThreeSeconds_opensSettingsAndMarksFirstRunHintSeen() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var settingsOpenedCount = 0

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    onOpenSettings = { settingsOpenedCount++ },
                )
            }
        }

        // Hold for >= 3,000ms
        composeTestRule.onNodeWithText("DRY TOAST").performTouchInput {
            longClick(durationMillis = 3_100L)
        }
        composeTestRule.waitForIdle()

        assertThat(settingsOpenedCount).isEqualTo(1)
    }

    @Test
    fun homeScreen_dialerIconDisplayedByDefault_andLaunchesDialerOnClick() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        val haptic = TestHapticEngine()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    hapticEngine = haptic,
                )
            }
        }

        val dialerButton = composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG)
        dialerButton.assertIsDisplayed()
        dialerButton.performClick()
        composeTestRule.waitForIdle()

        assertThat(launcher.dialerLaunchedCount).isEqualTo(1)
        assertThat(haptic.rotaryDetentCount).isEqualTo(1)
    }

    @Test
    fun homeScreen_dialerIconHiddenWhenShowDialerIconSettingDisabled_orWhenSearching() {
        val fakeSettingsRepo = com.garan.drytoast.ui.settings.FakeSettingsRepository()
        val viewModel = SearchViewModel(
            packageIndexSource = fakePackageIndexSource,
            settingsRepository = fakeSettingsRepo,
        )
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertIsDisplayed()

        // Activate search -> dialer icon hides while searching
        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertDoesNotExist()

        // Dismiss search -> dialer icon reappears
        viewModel.dismissSearch()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertIsDisplayed()

        // Disable setting "Show dialer icon on launcher" -> dialer icon hides on launcher screen
        kotlinx.coroutines.runBlocking {
            val currentGeneral = fakeSettingsRepo.getSettings().general
            fakeSettingsRepo.updateGeneralSettings(currentGeneral.copy(showDialerIcon = false))
            viewModel.reconcileForTesting(fakeSettingsRepo.getSettings())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun mainActivity_recreate_doesNotRetainActivityInViewModels() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val firstActivity = controller.get()

            controller.recreate()
            val secondActivity = controller.get()

            assertThat(secondActivity).isNotSameInstanceAs(firstActivity)
            assertThat(findReachableActivities(secondActivity.searchViewModel)).isEmpty()
            assertThat(findReachableActivities(secondActivity.settingsViewModel)).isEmpty()
        }
    }

    @Test
    fun mainActivity_recreate_sharesSameLocationSourceInstance() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val firstLocationSource = controller.get().locationSource

            controller.recreate()
            val secondLocationSource = controller.get().locationSource

            assertThat(secondLocationSource).isSameInstanceAs(firstLocationSource)
        }
    }

    @Test
    fun mainActivity_recreate_testHooksStillDriveSurvivingViewModel() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val firstActivity = controller.get()
            val workProfile = com.garan.drytoast.core.model.CustomProfile(
                id = "work",
                name = "Work",
                priority = 0,
                enabled = true,
                colorIndex = 0,
                trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(
                    com.garan.drytoast.core.model.ScheduleTrigger(
                        dailyWindows = mapOf(
                            com.garan.drytoast.core.model.DayOfWeek.MONDAY to
                                com.garan.drytoast.core.model.TimeWindow(
                                    com.garan.drytoast.core.model.TimeOfDay(9, 0),
                                    com.garan.drytoast.core.model.TimeOfDay(17, 0),
                                ),
                        ),
                        sameScheduleEveryDay = false,
                    ),
                ),
            )
            firstActivity.setClockAndTriggerAlarmForTesting(
                com.garan.drytoast.core.model.DayOfWeek.MONDAY,
                com.garan.drytoast.core.model.TimeOfDay(8, 0),
            )
            firstActivity.setExactAlarmPermissionForTesting(true)
            kotlinx.coroutines.runBlocking {
                firstActivity.configureScheduleProfilesForTesting(customProfiles = listOf(workProfile))
            }
            assertThat(firstActivity.searchViewModel.uiState.value.activeProfiles).isEmpty()
            assertThat(firstActivity.searchViewModel.uiState.value.capabilityIssues).isEmpty()

            // Recreate the Activity (simulating fontScale / density / locale change)
            controller.recreate()
            val secondActivity = controller.get()

            // Invoking test hooks on the NEW Activity must drive the surviving SearchViewModel
            secondActivity.setClockAndTriggerAlarmForTesting(
                com.garan.drytoast.core.model.DayOfWeek.MONDAY,
                com.garan.drytoast.core.model.TimeOfDay(10, 0),
            )
            secondActivity.setExactAlarmPermissionForTesting(false)

            assertThat(secondActivity.searchViewModel.uiState.value.activeProfiles.map { it.id })
                .containsExactly("work")
            assertThat(secondActivity.searchViewModel.uiState.value.capabilityIssues)
                .isNotEmpty()
        }
    }

    private fun findReachableActivities(root: Any): List<MainActivity> {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<MainActivity>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        visited.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is MainActivity) {
                found.add(current)
                continue
            }
            var clazz: Class<*>? = current.javaClass
            while (clazz != null && clazz != Any::class.java) {
                val pkg = clazz.packageName
                if (pkg.startsWith("java.") || pkg.startsWith("jdk.") || pkg.startsWith("android.")) {
                    break
                }
                for (field in clazz.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    if (field.type.isPrimitive) continue
                    runCatching {
                        field.isAccessible = true
                        field.get(current)
                    }.getOrNull()?.let { value ->
                        if (visited.add(value)) {
                            queue.add(value)
                        }
                    }
                }
                clazz = clazz.superclass
            }
        }
        return found
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

    private class FakeAppLauncher : AppLauncher {
        val launchedComponents = mutableListOf<ComponentKey>()
        val appInfoComponents = mutableListOf<ComponentKey>()
        var dialerLaunchedCount = 0
            private set

        override fun launchApp(component: ComponentKey): Boolean {
            launchedComponents.add(component)
            return true
        }

        override fun openAppInfo(component: ComponentKey): Boolean {
            appInfoComponents.add(component)
            return true
        }

        override fun launchDialer(): Boolean {
            dialerLaunchedCount++
            return true
        }
    }

    @Test
    fun swipeUpOnHomeCanvas_activatesSearch_focusesInput_andShowsKeyboard() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        val haptics = TestHapticEngine()
        val fakeKeyboard = FakeSoftwareKeyboardController()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides fakeKeyboard) {
                DryToastTheme {
                    DryToastHomeScreen(
                        viewModel = viewModel,
                        appLauncher = launcher,
                        hapticEngine = haptics,
                    )
                }
            }
        }

        assertThat(viewModel.uiState.value.isSearching).isFalse()

        composeTestRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()

        assertThat(viewModel.uiState.value.isSearching).isTrue()
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
        assertThat(fakeKeyboard.showCount).isGreaterThan(0)
        assertThat(haptics.rotaryDetentCount).isEqualTo(1)
    }

    @Test
    fun swipeDownOnHomeCanvas_doesNotActivateSearch() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        composeTestRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).performTouchInput {
            swipeDown()
        }
        composeTestRule.waitForIdle()

        assertThat(viewModel.uiState.value.isSearching).isFalse()
    }

    @Test
    fun horizontalSwipeOnHomeCanvas_doesNotActivateSearch() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }

        composeTestRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()

        assertThat(viewModel.uiState.value.isSearching).isFalse()
    }

    @Test
    fun searchActive_imeDismissedWithEmptyQuery_exitsSearchAndRestoresDialerIcon() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var imeVisible by mutableStateOf(false)

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    isImeVisible = imeVisible,
                )
            }
        }

        // Swipe up to open search; IME then appears
        composeTestRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        assertThat(viewModel.uiState.value.isSearching).isTrue()

        imeVisible = true
        composeTestRule.waitForIdle()
        assertThat(viewModel.uiState.value.isSearching).isTrue()

        // User dismisses IME without typing a query -> search exits automatically
        imeVisible = false
        composeTestRule.waitForIdle()
        assertThat(viewModel.uiState.value.isSearching).isFalse()
    }

    @Test
    fun searchActive_imeDismissedWithNonEmptyQuery_keepsSearchResultsVisible() {
        val viewModel = SearchViewModel(packageIndexSource = fakePackageIndexSource)
        val launcher = FakeAppLauncher()
        var imeVisible by mutableStateOf(false)

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                    isImeVisible = imeVisible,
                )
            }
        }

        composeTestRule.onNodeWithTag(HOME_CANVAS_TEST_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        imeVisible = true
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("cam")
        composeTestRule.waitForIdle()

        // Dismissing IME while results are displayed keeps search active
        imeVisible = false
        composeTestRule.waitForIdle()
        assertThat(viewModel.uiState.value.query).isEqualTo("cam")
        composeTestRule.onNodeWithText("Camera").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h390dp-land-mdpi")
    fun compactHeightLandscape_ambientCanvasUsesCompactHeader_andScrollsAllSevenQuickActions() {
        val sevenApps = (1..7).map { idx ->
            InstalledApp(
                component = ComponentKey("com.example.app$idx", "MainActivity", 0L),
                label = "Quick App $idx",
            )
        }
        val fakeSettingsRepo = com.garan.drytoast.ui.settings.FakeSettingsRepository()
        kotlinx.coroutines.runBlocking {
            val base = fakeSettingsRepo.getSettings().baseProfile
            fakeSettingsRepo.updateBaseProfile(base.copy(quickActions = sevenApps))
        }
        val viewModel = SearchViewModel(
            packageIndexSource = FakePackageIndexSource(sevenApps),
            settingsRepository = fakeSettingsRepo,
        )
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HOME_HEADER_COMPACT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HOME_HEADER_STANDARD_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertIsDisplayed()

        // Scroll to the 7th Quick Action on a 390dp-tall landscape viewport and launch it
        composeTestRule.onNodeWithText("07   Quick App 7")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertThat(launcher.launchedComponents).containsExactly(sevenApps.last().component)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h390dp-land-mdpi")
    fun compactHeightLandscape_activeSearchAboveIme_hidesDialerAndRetainsMultipleVisibleResults() {
        val apps = listOf(
            InstalledApp(ComponentKey("com.android.camera", "CameraActivity", 0L), "Camera"),
            InstalledApp(ComponentKey("com.android.calculator", "CalcActivity", 0L), "Calculator"),
            InstalledApp(ComponentKey("com.android.calendar", "CalendarActivity", 0L), "Calendar"),
        )
        val viewModel = SearchViewModel(packageIndexSource = FakePackageIndexSource(apps))
        val launcher = FakeAppLauncher()

        // Simulate 390dp phone landscape with ~188dp soft keyboard + 24dp status bar
        // (~178dp inner height after Robolectric's ~72dp system-bar safeDrawingPadding)
        composeTestRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(891.dp, 250.dp)),
            ) {
                DryToastTheme {
                    DryToastHomeScreen(
                        viewModel = viewModel,
                        appLauncher = launcher,
                        isImeVisible = true,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Type to launch...").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SEARCH_INPUT_TEST_TAG).performTextInput("ca")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HOME_HEADER_COMPACT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HOME_DIALER_BUTTON_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(SEARCH_INPUT_TEST_TAG).assertIsDisplayed()

        val firstRowBounds = composeTestRule.onNodeWithText("Camera").fetchSemanticsNode().boundsInRoot
        val secondRowBounds = composeTestRule.onNodeWithText("Calculator").fetchSemanticsNode().boundsInRoot
        assertThat(firstRowBounds.height).isGreaterThan(30f)
        assertThat(secondRowBounds.height).isGreaterThan(30f)

        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.waitForIdle()
        assertThat(launcher.launchedComponents).containsExactly(apps.first().component)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w680dp-h840dp-mdpi")
    fun tabletopHorizontalHalfOpenedFold_placesHeaderAboveCreaseAndSearchBelowCrease() {
        val apps = listOf(
            InstalledApp(ComponentKey("com.android.camera", "CameraActivity", 0L), "Camera"),
            InstalledApp(ComponentKey("com.android.calculator", "CalcActivity", 0L), "Calculator"),
        )
        val viewModel = SearchViewModel(packageIndexSource = FakePackageIndexSource(apps))
        val launcher = FakeAppLauncher()

        composeTestRule.setContent {
            DryToastTheme {
                DryToastHomeScreen(
                    viewModel = viewModel,
                    appLauncher = launcher,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Publish horizontal HALF_OPENED tabletop hinge spanning y = [410, 430]
        val horizontalHinge = FoldingFeature(
            activity = composeTestRule.activity,
            center = 420,
            size = 20,
            state = FoldingFeature.State.HALF_OPENED,
            orientation = FoldingFeature.Orientation.HORIZONTAL,
        )
        publisherRule.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(horizontalHinge)))
        composeTestRule.waitForIdle()

        val placeholderWindowBounds = composeTestRule.onNodeWithTag(SEARCH_PLACEHOLDER_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInWindow
        assertThat(placeholderWindowBounds.top).isAtLeast(430f)

        // Activate search and verify input + result rows remain below the horizontal crease
        composeTestRule.onNodeWithTag(SEARCH_PLACEHOLDER_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SEARCH_INPUT_TEST_TAG).performTextInput("ca")
        composeTestRule.waitForIdle()

        val inputWindowBounds = composeTestRule.onNodeWithTag(SEARCH_INPUT_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInWindow
        val cameraWindowBounds = composeTestRule.onNodeWithText("Camera")
            .fetchSemanticsNode()
            .boundsInWindow
        assertThat(inputWindowBounds.top).isAtLeast(430f)
        assertThat(cameraWindowBounds.top).isAtLeast(430f)
    }

    private class FakeSoftwareKeyboardController : SoftwareKeyboardController {
        var showCount = 0
            private set
        var hideCount = 0
            private set

        override fun show() {
            showCount++
        }

        override fun hide() {
            hideCount++
        }
    }

    private class FakePackageIndexSource(private val initialApps: List<InstalledApp> = emptyList()) :
        PackageIndexSource {
        override suspend fun getInstalledApps(): List<InstalledApp> = initialApps
        override fun observePackageChanges(): Flow<PackageChangeEvent> = MutableSharedFlow()
    }
}
