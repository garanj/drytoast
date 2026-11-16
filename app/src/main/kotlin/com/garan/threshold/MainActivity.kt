package com.garan.threshold

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.CustomProfile
import com.garan.threshold.core.model.DayOfWeek
import com.garan.threshold.core.model.LocationFix
import com.garan.threshold.core.model.ProfileCapability
import com.garan.threshold.core.model.SavedTrigger
import com.garan.threshold.core.model.TimeOfDay
import com.garan.threshold.core.port.ClockSource
import com.garan.threshold.core.port.LocalDayTime
import com.garan.threshold.core.search.ActionCandidate
import com.garan.threshold.data.antimetrics.AndroidAntiMetricsRepository
import com.garan.threshold.data.location.AndroidLocationSource
import com.garan.threshold.schedule.ScheduleTransitionNotifier
import com.garan.threshold.ui.home.ThresholdHomeScreen
import com.garan.threshold.ui.launcher.AndroidAppLauncher
import com.garan.threshold.ui.motion.ThresholdMotion
import com.garan.threshold.ui.onboarding.OnboardingScreen
import com.garan.threshold.ui.search.SearchViewModel
import com.garan.threshold.ui.settings.ConfigureAppsScreen
import com.garan.threshold.ui.settings.DisableHardenedModeScreen
import com.garan.threshold.ui.settings.FeedbackEmailIntentFactory
import com.garan.threshold.ui.settings.HardenedModeDeepLink
import com.garan.threshold.ui.settings.HardenedModeScreen
import com.garan.threshold.ui.settings.ProfilesScreen
import com.garan.threshold.ui.settings.SettingsViewModel
import com.garan.threshold.ui.settings.ThresholdSettingsScreen
import com.garan.threshold.ui.theme.ThresholdTheme
import com.garan.threshold.ui.util.ImeUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
sealed interface ThresholdRoute : NavKey {
    @Serializable
    data object Onboarding : ThresholdRoute

    @Serializable
    data object Home : ThresholdRoute

    @Serializable
    data object Settings : ThresholdRoute

    @Serializable
    data object HardenedMode : ThresholdRoute

    @Serializable
    data object DisableHardenedMode : ThresholdRoute

    @Serializable
    data object Profiles : ThresholdRoute

    @Serializable
    data class ConfigureApps(val profileId: String? = null) : ThresholdRoute
}

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { ThresholdAppContainer.from(this) }
    private val appLauncher by lazy { AndroidAppLauncher(this) }

    internal val locationSource: AndroidLocationSource
        get() = appContainer.locationSource

    internal val antiMetricsRepository: AndroidAntiMetricsRepository
        get() = appContainer.antiMetricsRepository

    @Volatile
    private var pendingLocationResolvedCallback: ((LocationFix) -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            lifecycleScope.launch {
                val fix = locationSource.refreshLocation()
                if (fix != null) {
                    pendingLocationResolvedCallback?.invoke(fix)
                }
                pendingLocationResolvedCallback = null
                ScheduleTransitionNotifier.notifyTransition()
            }
        } else {
            pendingLocationResolvedCallback = null
            ScheduleTransitionNotifier.notifyTransition()
        }
    }

    internal lateinit var searchViewModel: SearchViewModel
        private set
    internal lateinit var settingsViewModel: SettingsViewModel
        private set

    private val homeIntentResetCounter = mutableIntStateOf(0)
    private val disableHardenedModeDeepLinkCounter = mutableIntStateOf(0)

    private val defaultLauncherRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        handleDefaultLauncherRoleResult()
    }

    internal fun isLaunchedAsStandardAppTask(): Boolean = intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true &&
        intent?.hasCategory(Intent.CATEGORY_HOME) != true

    internal fun handleDefaultLauncherRoleResult() {
        val granted = appContainer.launcherRoleController.refresh()
        if (granted) {
            appContainer.pendingOnboardingRoleRequest = false
            appContainer.hapticEngine.targetSnap()
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.setShowRoleRequiredWarning(false)
                settingsViewModel.completeOnboarding()
            } else {
                lifecycleScope.launch {
                    appContainer.settingsRepository.setHasCompletedOnboarding(true)
                }
            }
        } else {
            appContainer.pendingOnboardingRoleRequest = false
            appContainer.hapticEngine.resistanceDetent()
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.setShowRoleRequiredWarning(true)
                settingsViewModel.resetOnboarding()
            } else {
                lifecycleScope.launch {
                    appContainer.settingsRepository.setHasCompletedOnboarding(false)
                }
            }
        }
    }

    internal fun requestDefaultLauncherRole() {
        appContainer.pendingOnboardingRoleRequest = true
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.completeOnboarding()
        }
        val intent = appContainer.launcherRoleController.createRequestRoleIntent()
        val launched = runCatching { defaultLauncherRoleLauncher.launch(intent) }.isSuccess
        if (!launched) {
            handleDefaultLauncherRoleResult()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // As a root home launcher, never delegate to super.onBackPressed() which finishes the activity
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::searchViewModel.isInitialized) {
            searchViewModel.dismissSearch()
        }
        ImeUtils.dismissIme(this)
        if (HardenedModeDeepLink.isValidDisableIntent(intent)) {
            HardenedModeDeepLink.markConsumed(intent)
            disableHardenedModeDeepLinkCounter.intValue++
        } else {
            // Pressing Home while already on Threshold collapses transient search/friction/settings state and hides IME
            homeIntentResetCounter.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.nfcReaderSource.enableForegroundReader(this)
        appContainer.appAccessController.isDeviceOwner()
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.refreshHardenedModeStatus()
        }
        val isDefault = appContainer.launcherRoleController.refresh()
        if (isDefault) {
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.setShowRoleRequiredWarning(false)
            }
            if (appContainer.pendingOnboardingRoleRequest) {
                appContainer.pendingOnboardingRoleRequest = false
                appContainer.hapticEngine.targetSnap()
                if (::settingsViewModel.isInitialized) {
                    settingsViewModel.completeOnboarding()
                } else {
                    lifecycleScope.launch {
                        appContainer.settingsRepository.setHasCompletedOnboarding(true)
                    }
                }
            }
        } else {
            if (appContainer.pendingOnboardingRoleRequest) {
                appContainer.pendingOnboardingRoleRequest = false
                if (::settingsViewModel.isInitialized) {
                    settingsViewModel.setShowRoleRequiredWarning(true)
                }
                appContainer.hapticEngine.resistanceDetent()
            }
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.resetOnboarding()
            } else {
                lifecycleScope.launch {
                    appContainer.settingsRepository.setHasCompletedOnboarding(false)
                }
            }
        }
    }

    override fun onPause() {
        appContainer.nfcReaderSource.disableForegroundReader(this)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (appContainer.launcherRoleController.isDefaultLauncher() && isLaunchedAsStandardAppTask()) {
            finishAndRemoveTask()
        }
    }

    @androidx.annotation.VisibleForTesting
    suspend fun configureScheduleProfilesForTesting(
        baseProfile: BaseProfileSettings = BaseProfileSettings(),
        customProfiles: List<CustomProfile>,
        savedTriggers: List<SavedTrigger> = emptyList(),
    ) {
        val repository = appContainer.settingsRepository
        val keepTriggerIds = savedTriggers.map { it.id }.toSet()
        repository.getSettings().savedTriggers
            .map { it.id }
            .filter { it !in keepTriggerIds }
            .forEach { repository.deleteTrigger(it) }
        repository.updateBaseProfile(baseProfile)
        savedTriggers.forEach { repository.saveTrigger(it) }
        repository.updateCustomProfiles(customProfiles)
        if (::searchViewModel.isInitialized) {
            searchViewModel.reconcileForTesting(repository.getSettings())
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setClockAndTriggerAlarmForTesting(dayOfWeek: DayOfWeek, timeOfDay: TimeOfDay) {
        appContainer.clockOverrideForTesting = ClockSource { LocalDayTime(dayOfWeek, timeOfDay) }
        ScheduleTransitionNotifier.notifyTransition()
        if (::searchViewModel.isInitialized) {
            kotlinx.coroutines.runBlocking {
                searchViewModel.reconcileForTesting()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setExactAlarmPermissionForTesting(granted: Boolean?) {
        appContainer.exactAlarmOverrideForTesting = granted
        ScheduleTransitionNotifier.notifyTransition()
        if (::searchViewModel.isInitialized) {
            kotlinx.coroutines.runBlocking {
                searchViewModel.reconcileForTesting()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setNfcCapabilityForTesting(enabled: Boolean?) {
        appContainer.nfcCapabilityOverrideForTesting = enabled
        ScheduleTransitionNotifier.notifyTransition()
        if (::searchViewModel.isInitialized) {
            kotlinx.coroutines.runBlocking {
                searchViewModel.reconcileForTesting()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setNowEpochMillisForTesting(millis: Long?) {
        appContainer.nowEpochMillisOverrideForTesting = millis
        ScheduleTransitionNotifier.notifyTransition()
        if (::searchViewModel.isInitialized) {
            kotlinx.coroutines.runBlocking {
                searchViewModel.reconcileForTesting()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setDefaultLauncherForTesting(isDefault: Boolean?) {
        appContainer.defaultLauncherOverrideForTesting = isDefault
    }

    @androidx.annotation.VisibleForTesting
    fun setDeviceOwnerForTesting(isDeviceOwner: Boolean?) {
        appContainer.deviceOwnerOverrideForTesting = isDeviceOwner
    }

    @androidx.annotation.VisibleForTesting
    fun simulateNfcTapForTesting(rawUid: String) {
        val registrationListener = appContainer.nfcReaderSource.activeRegistrationListener
        if (registrationListener != null) {
            appContainer.nfcReaderSource.dispatchScannedUid(rawUid)
        } else if (::searchViewModel.isInitialized) {
            kotlinx.coroutines.runBlocking {
                searchViewModel.processScannedNfcTag(rawUid)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun refreshLocationAndReconcileForTesting() {
        kotlinx.coroutines.runBlocking {
            locationSource.refreshLocation()
            if (::searchViewModel.isInitialized) {
                searchViewModel.reconcileForTesting()
            }
        }
    }

    private fun requestLocationOrRefresh(onLocationResolved: ((LocationFix) -> Unit)? = null) {
        if (locationSource.hasLocationPermission()) {
            lifecycleScope.launch {
                val fix = locationSource.refreshLocation()
                if (fix != null) {
                    onLocationResolved?.invoke(fix)
                }
            }
        } else {
            pendingLocationResolvedCallback = onLocationResolved
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun openExternalMapApp(center: LocationFix?) {
        val uriString = if (center != null) {
            "geo:${center.latitude},${center.longitude}?q=${center.latitude},${center.longitude}"
        } else {
            "geo:0,0"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun showSettingsLockedToast() {
        Toast.makeText(this, getString(R.string.toast_settings_locked), Toast.LENGTH_SHORT).show()
    }

    private fun applyPureBlackWallpaper() {
        lifecycleScope.launch {
            val applied = appContainer.wallpaperController.applyPureBlackWallpaper()
            if (applied) {
                appContainer.hapticEngine.targetSnap()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_black_wallpaper_applied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun launchFeedbackEmail() {
        appContainer.hapticEngine.rotaryDetent()
        val intent = FeedbackEmailIntentFactory.createFeedbackEmailIntent(this)
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            Toast.makeText(this, getString(R.string.toast_no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = appContainer
        val hapticEngine = container.hapticEngine
        val scheduleAlarmScheduler = container.scheduleAlarmScheduler

        val settingsAction = ActionCandidate(
            id = "settings",
            label = getString(R.string.action_threshold_settings),
            shortcutKeyword = ":settings",
        )
        val wallpaperAction = ActionCandidate(
            id = "wallpaper",
            label = getString(R.string.action_set_black_wallpaper_command),
            shortcutKeyword = ":wallpaper",
        )
        val feedbackAction = ActionCandidate(
            id = "feedback",
            label = getString(R.string.action_send_feedback),
            shortcutKeyword = ":feedback",
        )

        searchViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
                    packageIndexSource = container.packageIndexSource,
                    hapticEngine = container.hapticEngine,
                    dialerPackageResolver = container.dialerPackageResolver,
                    actions = listOf(settingsAction, wallpaperAction, feedbackAction),
                    settingsRepository = container.settingsRepository,
                    clockSource = container.activeClockSource,
                    scheduleAlarmScheduler = container.scheduleAlarmScheduler,
                    capabilityChecker = container.capabilityChecker,
                    locationSource = container.locationSource,
                    nfcReaderSource = container.nfcReaderSource,
                    appAccessController = container.appAccessController,
                    nowEpochMillisProvider = container.activeNowEpochMillisProvider,
                ) as T
            },
        )[SearchViewModel::class.java]

        settingsViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    packageIndexSource = container.packageIndexSource,
                    settingsRepository = container.settingsRepository,
                    dialerPackageResolver = container.dialerPackageResolver,
                    appAccessController = container.appAccessController,
                ) as T
            },
        )[SettingsViewModel::class.java]

        if (container.launcherRoleController.isDefaultLauncher() && container.pendingOnboardingRoleRequest) {
            container.pendingOnboardingRoleRequest = false
            hapticEngine.targetSnap()
            settingsViewModel.completeOnboarding()
        }

        if (HardenedModeDeepLink.isValidDisableIntent(intent)) {
            HardenedModeDeepLink.markConsumed(intent)
            disableHardenedModeDeepLinkCounter.intValue++
        }

        // IMPORTANT MEMORY INVARIANT (< 25 MB Typographic Mode / < 35 MB CI PSS Ceiling):
        // Never call searchViewModel.ensureIndexLoaded() eagerly in onCreate().
        // Resolving LauncherActivityInfo.getLabel() across all installed apps causes Android's
        // ResourcesManager to mmap foreign APK resources.arsc tables (+25 MB .apk mmap PSS).
        // Index loading must remain strictly lazy upon user search activation (activateSearch / onQueryChanged).

        setContent {
            ThresholdTheme {
                val initialRoute = if (container.launcherRoleController.isDefaultLauncher()) {
                    ThresholdRoute.Home
                } else {
                    ThresholdRoute.Onboarding
                }
                val backStack = rememberNavBackStack(initialRoute)
                val resetTrigger by homeIntentResetCounter
                val disableHardenedModeTrigger by disableHardenedModeDeepLinkCounter
                val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                val isDefaultLauncher by container.launcherRoleController.isDefaultLauncherFlow
                    .collectAsStateWithLifecycle()
                val currentLocation by locationSource.currentLocation.collectAsStateWithLifecycle()
                val locationTelemetry by locationSource.telemetry.collectAsStateWithLifecycle()
                val requiresOnboarding =
                    (!settingsUiState.general.hasCompletedOnboarding && !container.pendingOnboardingRoleRequest) ||
                        !isDefaultLauncher

                LaunchedEffect(settingsUiState.isSettingsLoaded, requiresOnboarding) {
                    if (!settingsUiState.isSettingsLoaded) return@LaunchedEffect
                    if (requiresOnboarding) {
                        if (backStack.firstOrNull() != ThresholdRoute.Onboarding) {
                            backStack.clear()
                            backStack.add(ThresholdRoute.Onboarding)
                        } else {
                            while (backStack.size > 1) {
                                backStack.removeLastOrNull()
                            }
                        }
                    } else if (backStack.firstOrNull() == ThresholdRoute.Onboarding) {
                        backStack.clear()
                        backStack.add(ThresholdRoute.Home)
                    }
                }

                LaunchedEffect(resetTrigger) {
                    if (resetTrigger > 0) {
                        settingsViewModel.setShowRoleRequiredWarning(false)
                        while (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                    }
                }

                LaunchedEffect(disableHardenedModeTrigger, requiresOnboarding) {
                    if (disableHardenedModeTrigger > 0 && !requiresOnboarding) {
                        settingsViewModel.refreshHardenedModeStatus()
                        while (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                        if (backStack.lastOrNull() != ThresholdRoute.DisableHardenedMode) {
                            backStack.add(ThresholdRoute.DisableHardenedMode)
                        }
                    }
                }

                val topRoute = backStack.lastOrNull()
                LaunchedEffect(topRoute) {
                    if (topRoute == ThresholdRoute.Home) {
                        settingsViewModel.releaseLoadedApps()
                    }
                }

                LaunchedEffect(settingsUiState.isSettingsLocked, topRoute) {
                    if (settingsUiState.isSettingsLocked &&
                        backStack.size > 1 &&
                        topRoute != ThresholdRoute.DisableHardenedMode
                    ) {
                        while (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                        showSettingsLockedToast()
                    }
                }

                @OptIn(ExperimentalComposeUiApi::class)
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                    },
                    transitionSpec = {
                        fadeIn(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_OPEN_DURATION_MS),
                        ) togetherWith fadeOut(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_CLOSE_DURATION_MS),
                        )
                    },
                    popTransitionSpec = {
                        fadeIn(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_OPEN_DURATION_MS),
                        ) togetherWith fadeOut(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_CLOSE_DURATION_MS),
                        )
                    },
                    predictivePopTransitionSpec = {
                        fadeIn(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_OPEN_DURATION_MS),
                        ) togetherWith fadeOut(
                            animationSpec = ThresholdMotion.opticalTween(ThresholdMotion.SHUTTER_CLOSE_DURATION_MS),
                        )
                    },
                    entryProvider = entryProvider {
                        entry<ThresholdRoute.Onboarding> {
                            OnboardingScreen(
                                isDefaultLauncher = isDefaultLauncher,
                                onRequestDefaultLauncher = ::requestDefaultLauncherRole,
                                onCompleteOnboarding = settingsViewModel::completeOnboarding,
                                showRoleRequiredWarning = settingsUiState.showRoleRequiredWarning,
                                hapticEngine = hapticEngine,
                            )
                        }

                        entry<ThresholdRoute.Home> {
                            ThresholdHomeScreen(
                                viewModel = searchViewModel,
                                appLauncher = appLauncher,
                                hapticEngine = hapticEngine,
                                onDismissIme = { ImeUtils.dismissIme(this@MainActivity) },
                                antiMetricsRepository = antiMetricsRepository,
                                onOpenSettings = {
                                    if (settingsUiState.isSettingsLocked) {
                                        showSettingsLockedToast()
                                    } else if (backStack.lastOrNull() != ThresholdRoute.Settings) {
                                        backStack.add(ThresholdRoute.Settings)
                                    }
                                },
                                onResolveCapabilityIssue = { capability ->
                                    when (capability) {
                                        ProfileCapability.EXACT_ALARM -> {
                                            scheduleAlarmScheduler.requestExactAlarmPermission()
                                        }
                                        ProfileCapability.BACKGROUND_LOCATION -> {
                                            if (!locationSource.hasLocationPermission()) {
                                                requestLocationOrRefresh()
                                            } else if (!locationSource.isLocationServicesEnabled()) {
                                                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { startActivity(intent) }
                                            }
                                        }
                                        ProfileCapability.NFC_HARDWARE_ENABLED -> {
                                            val nfcIntent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            val opened = runCatching { startActivity(nfcIntent) }.isSuccess
                                            if (!opened) {
                                                if (settingsUiState.isSettingsLocked) {
                                                    showSettingsLockedToast()
                                                } else if (backStack.lastOrNull() != ThresholdRoute.Profiles) {
                                                    backStack.add(ThresholdRoute.Profiles)
                                                }
                                            }
                                        }
                                    }
                                },
                                onApplyPureBlackWallpaper = ::applyPureBlackWallpaper,
                                onSendFeedback = ::launchFeedbackEmail,
                            )
                        }

                        entry<ThresholdRoute.Settings> {
                            LaunchedEffect(Unit) {
                                settingsViewModel.ensureLoaded()
                            }
                            ThresholdSettingsScreen(
                                uiState = settingsUiState,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                                onNavigateToConfigureApps = {
                                    backStack.add(ThresholdRoute.ConfigureApps(profileId = null))
                                },
                                onNavigateToProfiles = {
                                    backStack.add(ThresholdRoute.Profiles)
                                },
                                onNavigateToHardenedMode = {
                                    backStack.add(ThresholdRoute.HardenedMode)
                                },
                                onUpdateShowUnlocks = settingsViewModel::updateShowUnlocks,
                                onUpdateShowRest = settingsViewModel::updateShowRest,
                                onUpdateShowDialerIcon = settingsViewModel::updateShowDialerIcon,
                                onUpdateRequireFullTyping = settingsViewModel::updateRequireFullTyping,
                                onUpdateDefaultFriction = settingsViewModel::updateDefaultFriction,
                                onAddQuickAction = settingsViewModel::addQuickAction,
                                onRemoveQuickAction = settingsViewModel::removeQuickAction,
                                onUpdateRequireReboot = settingsViewModel::updateRequireRebootForSettingsChange,
                                onDiscardPendingChanges = settingsViewModel::discardPendingChanges,
                                onApplyPureBlackWallpaper = ::applyPureBlackWallpaper,
                                onSendFeedback = ::launchFeedbackEmail,
                            )
                        }

                        entry<ThresholdRoute.HardenedMode> {
                            LaunchedEffect(Unit) {
                                settingsViewModel.refreshHardenedModeStatus()
                            }
                            HardenedModeScreen(
                                isHardenedModeActive = settingsUiState.isHardenedModeActive,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                            )
                        }

                        entry<ThresholdRoute.DisableHardenedMode> {
                            LaunchedEffect(Unit) {
                                settingsViewModel.refreshHardenedModeStatus()
                            }
                            DisableHardenedModeScreen(
                                isHardenedModeActive = settingsUiState.isHardenedModeActive,
                                onConfirmDisable = {
                                    lifecycleScope.launch {
                                        container.appAccessController.deactivateHardenedMode()
                                        settingsViewModel.refreshHardenedModeStatus()
                                        ScheduleTransitionNotifier.notifyTransition()
                                        hapticEngine.targetSnap()
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.toast_hardened_mode_disabled),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        if (backStack.size > 1) {
                                            backStack.removeLastOrNull()
                                        }
                                    }
                                },
                                onCancel = {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                            )
                        }

                        entry<ThresholdRoute.Profiles> {
                            LaunchedEffect(Unit) {
                                settingsViewModel.ensureLoaded()
                            }
                            ProfilesScreen(
                                profiles = settingsUiState.customProfiles,
                                onSaveProfile = settingsViewModel::saveCustomProfile,
                                onDeleteProfile = settingsViewModel::deleteCustomProfile,
                                onReorderProfiles = settingsViewModel::reorderCustomProfiles,
                                onConfigureProfileApps = { profileId ->
                                    backStack.add(ThresholdRoute.ConfigureApps(profileId = profileId))
                                },
                                onBack = {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                                hapticEngine = hapticEngine,
                                canScheduleExactAlarms = scheduleAlarmScheduler::canScheduleExactAlarms,
                                onRequestExactAlarmPermission = scheduleAlarmScheduler::requestExactAlarmPermission,
                                currentLocation = currentLocation,
                                locationTelemetry = locationTelemetry,
                                onUseCurrentLocation = { onResolved ->
                                    requestLocationOrRefresh(onResolved)
                                },
                                onOpenMapApp = ::openExternalMapApp,
                                onEnsureLocationPermissionForProfile = {
                                    requestLocationOrRefresh()
                                },
                                savedTriggers = settingsUiState.savedTriggers,
                                onSaveTrigger = settingsViewModel::saveTrigger,
                                onDeleteTrigger = settingsViewModel::deleteTrigger,
                                onRegisterNfcScanListener = { listener ->
                                    container.nfcReaderSource.activeRegistrationListener = listener
                                },
                            )
                        }

                        entry<ThresholdRoute.ConfigureApps> { route ->
                            LaunchedEffect(Unit) {
                                settingsViewModel.ensureLoaded()
                            }
                            val targetProfile: CustomProfile? = if (route.profileId != null) {
                                settingsUiState.customProfiles.firstOrNull { it.id == route.profileId }
                            } else {
                                null
                            }
                            val initialAssignments =
                                targetProfile?.appAssignments ?: settingsUiState.baseProfile.appAssignments
                            val isCustomProfile = targetProfile != null

                            ConfigureAppsScreen(
                                availableApps = settingsUiState.availableApps,
                                initialAssignments = initialAssignments,
                                defaultFriction = settingsUiState.baseProfile.defaultFriction,
                                onSave = { savedAssignments ->
                                    if (targetProfile != null) {
                                        settingsViewModel.saveCustomProfileAppAssignments(
                                            targetProfile.id,
                                            savedAssignments,
                                        )
                                    } else {
                                        settingsViewModel.saveAppAssignments(savedAssignments)
                                    }
                                },
                                onBack = {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                },
                                hapticEngine = hapticEngine,
                                isCustomProfile = isCustomProfile,
                                customProfileName = targetProfile?.name,
                            )
                        }
                    },
                )
            }
        }
    }
}
