package com.garan.drytoast.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garan.drytoast.core.cascading.CascadingResolutionEngine
import com.garan.drytoast.core.cascading.NfcTapOutcome
import com.garan.drytoast.core.gatekeeper.IntentGatekeeper
import com.garan.drytoast.core.model.AppAccessState
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.core.model.DryToastSettings
import com.garan.drytoast.core.model.FrictionPolicy
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.NfcDailyUsage
import com.garan.drytoast.core.model.NfcLatchState
import com.garan.drytoast.core.model.Profile
import com.garan.drytoast.core.model.ProfileCapabilityIssue
import com.garan.drytoast.core.model.ProfileTrigger
import com.garan.drytoast.core.port.AppAccessController
import com.garan.drytoast.core.port.ClockSource
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.LaunchResult
import com.garan.drytoast.core.port.LocationSource
import com.garan.drytoast.core.port.NfcReaderSource
import com.garan.drytoast.core.port.NoOpAppAccessController
import com.garan.drytoast.core.port.NoOpHapticEngine
import com.garan.drytoast.core.port.NoOpNfcReaderSource
import com.garan.drytoast.core.port.NoOpScheduleAlarmScheduler
import com.garan.drytoast.core.port.PackageChangeEvent
import com.garan.drytoast.core.port.PackageIndexSource
import com.garan.drytoast.core.port.ProfileCapabilityChecker
import com.garan.drytoast.core.port.ScheduleAlarmScheduler
import com.garan.drytoast.core.port.SettingsRepository
import com.garan.drytoast.core.port.SystemClockSource
import com.garan.drytoast.core.search.ActionCandidate
import com.garan.drytoast.core.search.AppSearchCandidate
import com.garan.drytoast.core.search.FuzzyMatcher
import com.garan.drytoast.core.search.SearchEngine
import com.garan.drytoast.core.search.SearchItem
import com.garan.drytoast.core.search.SearchResult
import com.garan.drytoast.schedule.ScheduleTransitionNotifier
import com.garan.drytoast.ui.launcher.DialerPackageResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class ActiveFrictionGate(val app: SearchItem.App, val policy: FrictionPolicy)

data class QuickActionUiItem(
    val slotNumber: Int,
    val component: ComponentKey,
    val label: String,
    val accessState: AppAccessState,
)

data class SearchUiState(
    val query: String = "",
    val results: ImmutableList<SearchResult> = persistentListOf(),
    val isSearching: Boolean = false,
    val autoLaunchCandidate: SearchItem? = null,
    val activeFriction: ActiveFrictionGate? = null,
    val quickActions: ImmutableList<QuickActionUiItem> = persistentListOf(),
    val generalSettings: GeneralSettings = GeneralSettings(),
    val activeProfiles: ImmutableList<CustomProfile> = persistentListOf(),
    val capabilityIssues: ImmutableList<ProfileCapabilityIssue> = persistentListOf(),
    val breakGlassRemainingMinutes: Int = 0,
) {
    val isBreakGlassActive: Boolean get() = breakGlassRemainingMinutes > 0
}

class SearchViewModel(
    private val packageIndexSource: PackageIndexSource,
    private val hapticEngine: HapticEngine = NoOpHapticEngine,
    private val gatekeeper: IntentGatekeeper = IntentGatekeeper(),
    private val searchEngine: SearchEngine = SearchEngine(
        gatekeeper = gatekeeper,
        matcher = FuzzyMatcher(),
    ),
    defaultDialerPackage: String = DialerPackageResolver.FALLBACK_DIALER_PACKAGE,
    private val dialerPackageResolver: () -> String = { defaultDialerPackage },
    private val actions: List<ActionCandidate> = emptyList(),
    initialProfile: Profile = Profile(
        id = "ambient",
        name = "Ambient",
        defaultAccess = AppAccessState.Direct,
        appRules = emptyMap(),
    ),
    private val settingsRepository: SettingsRepository? = null,
    private val cascadingEngine: CascadingResolutionEngine = CascadingResolutionEngine(),
    private val clockSource: ClockSource = SystemClockSource,
    private val scheduleAlarmScheduler: ScheduleAlarmScheduler = NoOpScheduleAlarmScheduler,
    private val capabilityChecker: ProfileCapabilityChecker = ProfileCapabilityChecker { true },
    private val locationSource: LocationSource? = null,
    private val nfcReaderSource: NfcReaderSource = NoOpNfcReaderSource,
    private val appAccessController: AppAccessController = NoOpAppAccessController,
    private val nowEpochMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val resolvedDialerPackage: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        dialerPackageResolver()
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val reconcileMutex = Mutex()

    @Volatile
    private var lastObservedSettings: DryToastSettings? = null

    @Volatile
    private var previouslyActiveProfileIds: Set<String> = emptySet()

    @Volatile
    private var previouslyActiveTriggerIds: Set<String> = emptySet()

    @Volatile
    private var currentNfcLatches: Map<String, NfcLatchState> = emptyMap()

    @Volatile
    private var currentNfcDailyUsage: Map<String, NfcDailyUsage> = emptyMap()

    init {
        if (settingsRepository != null) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.observeSettings().collect { settings ->
                    onSettingsChanged(settings)
                }
            }
            viewModelScope.launch(ioDispatcher) {
                ScheduleTransitionNotifier.events.collect {
                    if (hasEnabledGeoProfiles(lastObservedSettings)) {
                        locationSource?.refreshLocation()
                    }
                    reconcileMutex.withLock {
                        lastObservedSettings?.let { reconcileWorkingSetLocked(it) }
                    }
                }
            }
            if (locationSource != null) {
                viewModelScope.launch(ioDispatcher) {
                    locationSource.currentLocation.collect {
                        reconcileMutex.withLock {
                            lastObservedSettings?.let { reconcileWorkingSetLocked(it) }
                        }
                    }
                }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            nfcReaderSource.tagScans.collect { rawUid ->
                processScannedNfcTag(rawUid)
            }
        }
    }

    @Volatile
    private var candidates: List<AppSearchCandidate> = emptyList()

    @Volatile
    private var activeProfile: Profile = initialProfile

    private val isIndexInitialized = AtomicBoolean(false)
    private var lastDecisiveCandidateKey: String? = null

    internal val isIndexLoaded: Boolean
        get() = isIndexInitialized.get()

    /**
     * Lazily loads the installed package index on first search interaction.
     * Must NOT be invoked eagerly during ambient home startup because [LauncherApps] label resolution
     * mmaps foreign APK resource tables into the process via Android's `ResourcesManager`.
     */
    fun ensureIndexLoaded() {
        if (!isIndexInitialized.compareAndSet(false, true)) return

        viewModelScope.launch(ioDispatcher) {
            val apps = packageIndexSource.getInstalledApps()
            reconcileMutex.withLock {
                candidates = apps.map { AppSearchCandidate(it.component, it.label) }
                val currentQuery = _uiState.value.query
                if (currentQuery.isNotBlank()) {
                    performSearch(currentQuery)
                }
            }

            packageIndexSource.observePackageChanges().collect { event ->
                if (event is PackageChangeEvent.Removed) {
                    settingsRepository?.removeApps(setOf(event.component))
                }
                reconcileMutex.withLock {
                    candidates = when (event) {
                        is PackageChangeEvent.Added -> {
                            candidates.filterNot { it.component == event.component } +
                                AppSearchCandidate(event.app.component, event.app.label)
                        }
                        is PackageChangeEvent.Removed -> {
                            candidates.filterNot { it.component == event.component }
                        }
                        is PackageChangeEvent.Changed -> {
                            candidates.filterNot { it.component == event.component } +
                                AppSearchCandidate(event.app.component, event.app.label)
                        }
                    }
                    lastObservedSettings?.let { reconcileWorkingSetLocked(it) }
                }
            }
        }
    }

    /**
     * Re-evaluates the working set of active custom profiles and effective app rules against
     * the current day, time, and location (called on minute ticks, activity resume, or alarm boundaries).
     */
    fun refreshScheduleEvaluation() {
        viewModelScope.launch(ioDispatcher) {
            if (hasEnabledGeoProfiles(lastObservedSettings)) {
                locationSource?.refreshLocation()
            }
            reconcileMutex.withLock {
                lastObservedSettings?.let { reconcileWorkingSetLocked(it) }
            }
        }
    }

    fun onNfcTagScanned(rawUid: String) {
        viewModelScope.launch(ioDispatcher) {
            processScannedNfcTag(rawUid)
        }
    }

    internal suspend fun processScannedNfcTag(rawUid: String) {
        val effective = lastObservedSettings ?: settingsRepository?.getSettings() ?: return
        if (hasEnabledGeoProfiles(effective) && locationSource?.currentLocation?.value == null) {
            locationSource?.refreshLocation()
        }
        reconcileMutex.withLock {
            lastObservedSettings = effective
            val nowMillis = nowEpochMillisProvider()
            if (cascadingEngine.matchesBreakGlassTag(rawUid, effective.general.breakGlassTagUid)) {
                val nextUntil = if (effective.isBreakGlassActive(nowMillis)) {
                    null
                } else {
                    nowMillis + effective.general.breakGlassDurationMinutes.coerceAtLeast(1) * 60_000L
                }
                val updatedSettings = effective.copy(breakGlassUntilEpochMillis = nextUntil)
                lastObservedSettings = updatedSettings
                settingsRepository?.updateBreakGlassUntil(nextUntil)
                hapticEngine.relaySnap()
                scheduleAlarmScheduler.syncNextScheduleAlarm(
                    profiles = updatedSettings.customProfiles,
                    savedTriggers = updatedSettings.savedTriggers,
                    nfcLatches = currentNfcLatches,
                    extraExpiryEpochMillis = nextUntil,
                )
                reconcileWorkingSetLocked(updatedSettings)
                return
            }
            val now = clockSource.now()
            val currentLocation = locationSource?.currentLocation?.value
            val tapResult = cascadingEngine.processNfcTagTap(
                rawTagUid = rawUid,
                customProfiles = effective.customProfiles,
                savedTriggers = effective.savedTriggers,
                currentDay = now.dayOfWeek,
                currentTime = now.timeOfDay,
                currentLocation = currentLocation,
                previouslyActiveTriggerIds = previouslyActiveTriggerIds,
                previouslyActiveProfileIds = previouslyActiveProfileIds,
                currentNfcLatches = currentNfcLatches,
                nowEpochMillis = nowMillis,
                currentDailyUsage = currentNfcDailyUsage,
                currentLocalDate = now.localDate,
            )
            val latchesChanged = tapResult.updatedLatches != currentNfcLatches
            val usageChanged = tapResult.updatedDailyUsage != currentNfcDailyUsage
            currentNfcLatches = tapResult.updatedLatches
            currentNfcDailyUsage = tapResult.updatedDailyUsage
            val updatedSettings = effective.copy(
                nfcLatches = currentNfcLatches,
                nfcDailyUsage = currentNfcDailyUsage,
            )
            lastObservedSettings = updatedSettings
            if (latchesChanged || usageChanged) {
                settingsRepository?.updateNfcLatches(
                    latches = currentNfcLatches,
                    dailyUsage = currentNfcDailyUsage,
                )
            }
            when (tapResult.outcome) {
                NfcTapOutcome.LATCHED_ON,
                NfcTapOutcome.UNLATCHED_OFF,
                -> hapticEngine.relaySnap()
                NfcTapOutcome.COMPANION_CONDITIONS_NOT_MET,
                NfcTapOutcome.DAILY_LIMIT_REACHED,
                -> hapticEngine.resistanceDetent()
                NfcTapOutcome.UNRECOGNIZED_TAG -> Unit
            }
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = updatedSettings.customProfiles,
                savedTriggers = updatedSettings.savedTriggers,
                nfcLatches = currentNfcLatches,
                extraExpiryEpochMillis = updatedSettings.breakGlassUntilEpochMillis?.takeIf { it > nowMillis },
            )
            reconcileWorkingSetLocked(updatedSettings)
        }
    }

    internal suspend fun reconcileForTesting(settings: DryToastSettings? = null) {
        if (candidates.isEmpty()) {
            val apps = packageIndexSource.getInstalledApps()
            isIndexInitialized.set(true)
            reconcileMutex.withLock {
                candidates = apps.map { AppSearchCandidate(it.component, it.label) }
            }
        }
        val effective = settings ?: settingsRepository?.getSettings() ?: lastObservedSettings ?: return
        if (hasEnabledGeoProfiles(effective)) {
            locationSource?.refreshLocation()
        }
        reconcileMutex.withLock {
            if (settings == null || settings.nfcLatches.isNotEmpty()) {
                currentNfcLatches = effective.nfcLatches
            }
            lastObservedSettings = effective
            reconcileWorkingSetLocked(effective)
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = effective.customProfiles,
                savedTriggers = effective.savedTriggers,
                nfcLatches = currentNfcLatches,
            )
        }
    }

    private fun hasEnabledGeoProfiles(settings: DryToastSettings?): Boolean {
        if (settings == null) return false
        val triggersById = settings.savedTriggersById
        return settings.customProfiles.any { profile ->
            profile.enabled && profile.resolvedTriggers(triggersById).any { it is ProfileTrigger.Geo }
        }
    }

    fun activateSearch() {
        ensureIndexLoaded()
        _uiState.update { it.copy(isSearching = true) }
    }

    fun dismissSearch() {
        lastDecisiveCandidateKey = null
        _uiState.update { current ->
            current.copy(
                query = "",
                results = persistentListOf(),
                isSearching = false,
                autoLaunchCandidate = null,
                activeFriction = null,
            )
        }
    }

    fun openFriction(app: SearchItem.App, policy: FrictionPolicy) {
        if (_uiState.value.generalSettings.requireFullTypingOfGuardedApps) {
            val query = _uiState.value.query.trim()
            if (!query.equals(app.label.trim(), ignoreCase = true)) {
                hapticEngine.resistanceDetent()
                return
            }
        }
        _uiState.update { it.copy(activeFriction = ActiveFrictionGate(app, policy)) }
    }

    fun dismissFriction() {
        _uiState.update { it.copy(activeFriction = null) }
    }

    /**
     * Temporarily unsuspends a Guarded [component] in Hardened Mode for the configured
     * [com.garan.drytoast.core.model.GeneralSettings.guardedSessionGraceMinutes] duration.
     */
    suspend fun authorizeGuardedLaunch(component: ComponentKey): Boolean {
        val graceMinutes = _uiState.value.generalSettings.guardedSessionGraceMinutes
        return appAccessController.grantGuardedSession(
            component = component,
            graceDurationMillis = graceMinutes * 60_000L,
            defaultDialerPackage = resolvedDialerPackage,
        )
    }

    /**
     * Unsuspends [component] when Hardened Mode is active before invoking [onAuthorizedLaunch].
     */
    fun confirmFrictionAndLaunch(component: ComponentKey, onAuthorizedLaunch: () -> Unit) {
        if (!appAccessController.isDeviceOwnerFlow.value) {
            onAuthorizedLaunch()
            return
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                authorizeGuardedLaunch(component)
            }
            onAuthorizedLaunch()
        }
    }

    fun markFirstRunHintSeen() {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository?.setHasSeenFirstRunHint(true)
            _uiState.update { it.copy(generalSettings = it.generalSettings.copy(hasSeenFirstRunHint = true)) }
        }
    }

    fun launchQuickAction(item: QuickActionUiItem, onDirectLaunch: () -> Unit) {
        val isDefaultDialer = (item.component.packageName == resolvedDialerPackage)
        val settings = lastObservedSettings
        val access = if (settings != null) {
            val now = clockSource.now()
            cascadingEngine.resolveAppAccess(
                component = item.component,
                baseProfile = settings.baseProfile,
                customProfiles = settings.customProfiles,
                currentDay = now.dayOfWeek,
                currentTime = now.timeOfDay,
                isDefaultDialer = isDefaultDialer,
                currentLocation = locationSource?.currentLocation?.value,
                previouslyActiveProfileIds = previouslyActiveProfileIds,
                savedTriggers = settings.savedTriggers,
                previouslyActiveTriggerIds = previouslyActiveTriggerIds,
                nfcLatchStates = currentNfcLatches,
                nowEpochMillis = nowEpochMillisProvider(),
                random = random,
            )
        } else {
            item.accessState
        }

        when (access) {
            is AppAccessState.Blocked -> {
                hapticEngine.resistanceDetent()
            }
            is AppAccessState.Guarded -> {
                _uiState.update {
                    it.copy(
                        activeFriction = ActiveFrictionGate(
                            SearchItem.App(item.component, item.label, access),
                            access.policy,
                        ),
                    )
                }
            }
            is AppAccessState.Direct -> {
                onDirectLaunch()
            }
        }
    }

    private suspend fun onSettingsChanged(settings: DryToastSettings) {
        if (hasEnabledGeoProfiles(settings) && locationSource?.currentLocation?.value == null) {
            locationSource?.refreshLocation()
        }
        reconcileMutex.withLock {
            currentNfcLatches = settings.nfcLatches
            currentNfcDailyUsage = settings.nfcDailyUsage
            lastObservedSettings = settings
            reconcileWorkingSetLocked(settings)
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = settings.customProfiles,
                savedTriggers = settings.savedTriggers,
                nfcLatches = currentNfcLatches,
                extraExpiryEpochMillis = settings.breakGlassUntilEpochMillis,
            )
        }
    }

    private suspend fun reconcileWorkingSetLocked(settings: DryToastSettings) {
        val now = clockSource.now()
        val nowMillis = nowEpochMillisProvider()
        val breakGlassActive = settings.isBreakGlassActive(nowMillis)
        val breakGlassRemainingMinutes = settings.breakGlassRemainingMinutes(nowMillis)
        val effectiveSettings = if (!breakGlassActive && settings.breakGlassUntilEpochMillis != null) {
            settingsRepository?.updateBreakGlassUntil(null)
            settings.copy(breakGlassUntilEpochMillis = null).also { lastObservedSettings = it }
        } else {
            settings
        }
        val currentLocation = locationSource?.currentLocation?.value
        val previousLatches = currentNfcLatches
        val hasRestrictiveDefaultProfile = !breakGlassActive && effectiveSettings.customProfiles.any { profile ->
            profile.enabled && (
                profile.defaultRule == com.garan.drytoast.core.model.ProfileDefaultRule.BLOCK_ALL ||
                    profile.defaultRule == com.garan.drytoast.core.model.ProfileDefaultRule.GUARD_ALL
                )
        }
        if (hasRestrictiveDefaultProfile && candidates.isEmpty()) {
            val apps = packageIndexSource.getInstalledApps()
            isIndexInitialized.set(true)
            candidates = apps.map { AppSearchCandidate(it.component, it.label) }
        }
        val additionalComponents = if (hasRestrictiveDefaultProfile) {
            buildSet {
                addAll(effectiveSettings.baseProfile.quickActions.map { it.component })
                addAll(candidates.map { it.component })
            }
        } else {
            effectiveSettings.baseProfile.quickActions.map { it.component }
        }
        val workingSet = cascadingEngine.resolveWorkingSet(
            baseProfile = effectiveSettings.baseProfile,
            customProfiles = effectiveSettings.customProfiles,
            currentDay = now.dayOfWeek,
            currentTime = now.timeOfDay,
            defaultDialerPackage = resolvedDialerPackage,
            additionalComponents = additionalComponents,
            currentLocation = currentLocation,
            previouslyActiveProfileIds = previouslyActiveProfileIds,
            savedTriggers = effectiveSettings.savedTriggers,
            previouslyActiveTriggerIds = previouslyActiveTriggerIds,
            nfcLatchStates = previousLatches,
            nowEpochMillis = nowMillis,
            random = random,
            isBreakGlassActive = breakGlassActive,
        )
        activeProfile = workingSet.effectiveProfile
        previouslyActiveProfileIds = workingSet.activeProfiles.map { it.id }.toSet()
        previouslyActiveTriggerIds = workingSet.activeTriggerIds
        currentNfcLatches = workingSet.activeNfcLatches
        if (currentNfcLatches != previousLatches) {
            lastObservedSettings = effectiveSettings.copy(nfcLatches = currentNfcLatches)
            settingsRepository?.updateNfcLatches(currentNfcLatches)
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = effectiveSettings.customProfiles,
                savedTriggers = effectiveSettings.savedTriggers,
                nfcLatches = currentNfcLatches,
                extraExpiryEpochMillis = if (breakGlassActive) effectiveSettings.breakGlassUntilEpochMillis else null,
            )
        }
        appAccessController.reconcileBlockedPackages(
            blockedComponents = workingSet.blockedComponents,
            defaultDialerPackage = resolvedDialerPackage,
            guardedComponents = workingSet.guardedComponents,
        )

        val quickApps = effectiveSettings.baseProfile.quickActions
        val quickItems = quickApps.mapIndexed { index, quickApp ->
            val comp = quickApp.component
            val access = if (breakGlassActive) {
                AppAccessState.Direct
            } else {
                workingSet.effectiveProfile.appRules[comp]
                    ?: cascadingEngine.resolveAppAccess(
                        component = comp,
                        baseProfile = effectiveSettings.baseProfile,
                        customProfiles = effectiveSettings.customProfiles,
                        currentDay = now.dayOfWeek,
                        currentTime = now.timeOfDay,
                        isDefaultDialer = (comp.packageName == resolvedDialerPackage),
                        currentLocation = currentLocation,
                        previouslyActiveProfileIds = previouslyActiveProfileIds,
                        savedTriggers = effectiveSettings.savedTriggers,
                        previouslyActiveTriggerIds = previouslyActiveTriggerIds,
                        nfcLatchStates = currentNfcLatches,
                        nowEpochMillis = nowMillis,
                        random = random,
                    )
            }
            QuickActionUiItem(
                slotNumber = index + 1,
                component = comp,
                label = quickApp.label,
                accessState = access,
            )
        }.toImmutableList()

        val capabilityIssues = capabilityChecker
            .findCapabilityIssues(effectiveSettings.customProfiles, effectiveSettings.savedTriggers)
            .toImmutableList()

        val snapshotCandidates = candidates
        _uiState.update { current ->
            val currentQuery = current.query
            if (currentQuery.isNotBlank()) {
                val response = searchEngine.searchWithAutoLaunch(
                    query = currentQuery,
                    candidates = snapshotCandidates,
                    actions = actions,
                    profile = workingSet.effectiveProfile,
                    defaultDialerPackage = resolvedDialerPackage,
                )
                current.copy(
                    generalSettings = effectiveSettings.general,
                    quickActions = quickItems,
                    activeProfiles = workingSet.activeProfiles.toImmutableList(),
                    capabilityIssues = capabilityIssues,
                    breakGlassRemainingMinutes = breakGlassRemainingMinutes,
                    results = response.results.toImmutableList(),
                    autoLaunchCandidate = response.autoLaunchCandidate,
                )
            } else {
                current.copy(
                    generalSettings = effectiveSettings.general,
                    quickActions = quickItems,
                    activeProfiles = workingSet.activeProfiles.toImmutableList(),
                    capabilityIssues = capabilityIssues,
                    breakGlassRemainingMinutes = breakGlassRemainingMinutes,
                    results = persistentListOf(),
                    autoLaunchCandidate = null,
                )
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        ensureIndexLoaded()
        _uiState.update { state ->
            state.copy(
                query = newQuery,
                isSearching = state.isSearching || newQuery.isNotBlank(),
            )
        }
        performSearch(newQuery)
    }

    fun clearQuery() {
        dismissSearch()
    }

    fun setProfile(profile: Profile) {
        activeProfile = profile
        val currentQuery = _uiState.value.query
        if (currentQuery.isNotBlank()) {
            performSearch(currentQuery)
        }
    }

    fun evaluateLaunch(item: SearchItem): LaunchResult = when (item) {
        is SearchItem.App -> {
            val isDefaultDialer = (item.component.packageName == resolvedDialerPackage)
            val settings = lastObservedSettings
            val effectiveAccess = if (settings != null) {
                val now = clockSource.now()
                cascadingEngine.resolveAppAccess(
                    component = item.component,
                    baseProfile = settings.baseProfile,
                    customProfiles = settings.customProfiles,
                    currentDay = now.dayOfWeek,
                    currentTime = now.timeOfDay,
                    isDefaultDialer = isDefaultDialer,
                    currentLocation = locationSource?.currentLocation?.value,
                    previouslyActiveProfileIds = previouslyActiveProfileIds,
                    savedTriggers = settings.savedTriggers,
                    previouslyActiveTriggerIds = previouslyActiveTriggerIds,
                    nfcLatchStates = currentNfcLatches,
                    nowEpochMillis = nowEpochMillisProvider(),
                    random = random,
                )
            } else {
                gatekeeper.resolveAccess(item.component, activeProfile, resolvedDialerPackage)
            }

            val result = when (effectiveAccess) {
                is AppAccessState.Direct -> LaunchResult.Launched
                is AppAccessState.Guarded -> LaunchResult.FrictionRequired(effectiveAccess.policy)
                is AppAccessState.Blocked -> LaunchResult.Blocked
            }
            if (result is LaunchResult.FrictionRequired &&
                _uiState.value.generalSettings.requireFullTypingOfGuardedApps
            ) {
                val query = _uiState.value.query.trim()
                if (!query.equals(item.label.trim(), ignoreCase = true)) {
                    hapticEngine.resistanceDetent()
                    LaunchResult.Blocked
                } else {
                    result
                }
            } else {
                result
            }
        }
        is SearchItem.Action -> LaunchResult.Launched
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            lastDecisiveCandidateKey = null
            _uiState.update {
                it.copy(
                    results = persistentListOf(),
                    autoLaunchCandidate = null,
                )
            }
            return
        }

        val snapshotCandidates = candidates
        val response = searchEngine.searchWithAutoLaunch(
            query = query,
            candidates = snapshotCandidates,
            actions = actions,
            profile = activeProfile,
            defaultDialerPackage = resolvedDialerPackage,
        )

        val trimmed = query.trim()
        val decisiveCandidate = response.autoLaunchCandidate
            ?: if (response.results.size == 1 && trimmed.length >= 2) response.results.first().item else null
        val newDecisiveCandidateKey = decisiveCandidate?.key

        if (newDecisiveCandidateKey != null && newDecisiveCandidateKey != lastDecisiveCandidateKey) {
            hapticEngine.targetSnap()
        }
        lastDecisiveCandidateKey = newDecisiveCandidateKey

        _uiState.update {
            it.copy(
                results = response.results.toImmutableList(),
                autoLaunchCandidate = response.autoLaunchCandidate,
            )
        }
    }
}
