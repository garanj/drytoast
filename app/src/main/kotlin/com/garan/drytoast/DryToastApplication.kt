package com.garan.drytoast

import android.app.Application
import android.content.Context
import com.garan.drytoast.core.cascading.CascadingResolutionEngine
import com.garan.drytoast.core.cascading.NfcTapOutcome
import com.garan.drytoast.core.model.ProfileTrigger
import com.garan.drytoast.core.port.ClockSource
import com.garan.drytoast.core.port.SystemClockSource
import com.garan.drytoast.data.antimetrics.AndroidAntiMetricsRepository
import com.garan.drytoast.data.haptics.AndroidHapticEngine
import com.garan.drytoast.data.location.AndroidLocationSource
import com.garan.drytoast.data.packageindex.AndroidPackageIndexSource
import com.garan.drytoast.data.settings.AndroidSettingsRepository
import com.garan.drytoast.data.wallpaper.AndroidWallpaperController
import com.garan.drytoast.dpc.AndroidAppAccessController
import com.garan.drytoast.nfc.AndroidNfcReaderSource
import com.garan.drytoast.schedule.AndroidProfileCapabilityChecker
import com.garan.drytoast.schedule.AndroidScheduleAlarmScheduler
import com.garan.drytoast.schedule.ScheduleTransitionNotifier
import com.garan.drytoast.ui.launcher.AndroidLauncherRoleController
import com.garan.drytoast.ui.launcher.DialerPackageResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class DryToastApplication : Application() {
    val container: DryToastAppContainer by lazy { DryToastAppContainer(applicationContext) }
}

/**
 * Process-scoped dependency container holding Activity-independent services and test hooks.
 * Ensures ViewModels never capture a [MainActivity] instance across configuration/recreation
 * changes and that both UI and ViewModels observe the same shared state.
 */
class DryToastAppContainer(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val packageIndexSource by lazy { AndroidPackageIndexSource(appContext) }
    val hapticEngine by lazy { AndroidHapticEngine(appContext) }
    val settingsRepository by lazy { AndroidSettingsRepository(appContext) }
    val locationSource by lazy { AndroidLocationSource(appContext) }
    val antiMetricsRepository by lazy {
        AndroidAntiMetricsRepository(
            context = appContext,
            onScreenOffListener = {
                ScheduleTransitionNotifier.notifyTransition()
                backgroundScope.launch {
                    reconcileDpcFromSettings()
                }
            },
        )
    }
    val nfcReaderSource by lazy { AndroidNfcReaderSource(appContext, hapticEngine) }
    val wallpaperController by lazy { AndroidWallpaperController(appContext) }

    @Volatile
    var clockOverrideForTesting: ClockSource? = null

    @Volatile
    var exactAlarmOverrideForTesting: Boolean? = null

    @Volatile
    var nfcCapabilityOverrideForTesting: Boolean? = null

    @Volatile
    var nowEpochMillisOverrideForTesting: Long? = null

    @Volatile
    var pendingOnboardingRoleRequest: Boolean = false

    @Volatile
    var defaultLauncherOverrideForTesting: Boolean? = null
        set(value) {
            field = value
            launcherRoleController.refresh()
        }

    @Volatile
    var deviceOwnerOverrideForTesting: Boolean? = null
        set(value) {
            field = value
            appAccessController.isDeviceOwner()
        }

    val launcherRoleController by lazy {
        AndroidLauncherRoleController(
            context = appContext,
            overrideProvider = { defaultLauncherOverrideForTesting },
        )
    }

    val activeClockSource: ClockSource = ClockSource {
        clockOverrideForTesting?.now() ?: SystemClockSource.now()
    }

    val activeNowEpochMillisProvider: () -> Long = {
        nowEpochMillisOverrideForTesting ?: System.currentTimeMillis()
    }

    val appAccessController by lazy {
        AndroidAppAccessController(
            context = appContext,
            settingsRepository = settingsRepository,
            deviceOwnerOverrideProvider = { deviceOwnerOverrideForTesting },
            nowEpochMillisProvider = activeNowEpochMillisProvider,
        )
    }

    private val dpcCascadingEngine = CascadingResolutionEngine()

    val scheduleAlarmScheduler by lazy {
        AndroidScheduleAlarmScheduler(
            context = appContext,
            clockSource = activeClockSource,
            nowEpochMillisProvider = {
                nowEpochMillisOverrideForTesting
                    ?: ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli()
            },
            exactNowEpochMillisProvider = activeNowEpochMillisProvider,
        )
    }

    val capabilityChecker by lazy {
        AndroidProfileCapabilityChecker(
            context = appContext,
            exactAlarmOverride = { exactAlarmOverrideForTesting },
            nfcCapabilityOverride = { nfcCapabilityOverrideForTesting },
        )
    }

    val dialerPackageResolver: () -> String = {
        DialerPackageResolver.resolveDefaultDialerPackage(appContext)
    }

    /**
     * Evaluates the current effective working set from persisted [settingsRepository] state,
     * prunes expired or companion-invalidated NFC latches, and reconciles OS-level package
     * suspension via [appAccessController] when Hardened Mode is active.
     * Safe to call from background receivers ([com.garan.drytoast.schedule.ScheduleAlarmReceiver],
     * [com.garan.drytoast.dpc.DryToastDeviceAdminReceiver], [com.garan.drytoast.settings.SettingsLockReceiver])
     * even when [MainActivity] is not running.
     */
    suspend fun reconcileDpcFromSettings() {
        val settings = settingsRepository.getSettings()
        val triggersById = settings.savedTriggersById
        val hasEnabledGeoProfiles = settings.customProfiles.any { profile ->
            profile.enabled && profile.resolvedTriggers(triggersById).any { it is ProfileTrigger.Geo }
        }
        if (hasEnabledGeoProfiles && locationSource.currentLocation.value == null) {
            locationSource.refreshLocation()
        }
        val now = activeClockSource.now()
        val nowMillis = activeNowEpochMillisProvider()
        val breakGlassActive = settings.isBreakGlassActive(nowMillis)
        if (!breakGlassActive && settings.breakGlassUntilEpochMillis != null) {
            settingsRepository.updateBreakGlassUntil(null)
        }
        val currentLocation = locationSource.currentLocation.value
        val dialerPkg = dialerPackageResolver()
        val workingSet = dpcCascadingEngine.resolveWorkingSet(
            baseProfile = settings.baseProfile,
            customProfiles = settings.customProfiles,
            currentDay = now.dayOfWeek,
            currentTime = now.timeOfDay,
            defaultDialerPackage = dialerPkg,
            additionalComponents = settings.baseProfile.quickActions.map { it.component },
            currentLocation = currentLocation,
            savedTriggers = settings.savedTriggers,
            nfcLatchStates = settings.nfcLatches,
            nowEpochMillis = nowMillis,
            isBreakGlassActive = breakGlassActive,
        )
        val latchesChanged = workingSet.activeNfcLatches != settings.nfcLatches
        if (latchesChanged) {
            settingsRepository.updateNfcLatches(workingSet.activeNfcLatches)
        }
        if (latchesChanged || breakGlassActive || settings.breakGlassUntilEpochMillis != null) {
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = settings.customProfiles,
                savedTriggers = settings.savedTriggers,
                nfcLatches = workingSet.activeNfcLatches,
                extraExpiryEpochMillis = if (breakGlassActive) settings.breakGlassUntilEpochMillis else null,
            )
        }
        if (appAccessController.isDeviceOwner()) {
            appAccessController.reconcileBlockedPackages(
                blockedComponents = workingSet.blockedComponents,
                defaultDialerPackage = dialerPkg,
                guardedComponents = workingSet.guardedComponents,
            )
        }
    }

    /**
     * Fallback handler when an NFC tag is tapped via [com.garan.drytoast.nfc.NfcTagDispatchActivity]
     * while no active [com.garan.drytoast.ui.search.SearchViewModel] is currently subscribed to
     * [nfcReaderSource].
     */
    fun processBackgroundNfcTagScan(rawUid: String) {
        backgroundScope.launch {
            val settings = settingsRepository.getSettings()
            val nowMillis = activeNowEpochMillisProvider()
            if (dpcCascadingEngine.matchesBreakGlassTag(rawUid, settings.general.breakGlassTagUid)) {
                val nextUntil = if (settings.isBreakGlassActive(nowMillis)) {
                    null
                } else {
                    nowMillis + settings.general.breakGlassDurationMinutes.coerceAtLeast(1) * 60_000L
                }
                settingsRepository.updateBreakGlassUntil(nextUntil)
                hapticEngine.relaySnap()
                scheduleAlarmScheduler.syncNextScheduleAlarm(
                    profiles = settings.customProfiles,
                    savedTriggers = settings.savedTriggers,
                    nfcLatches = settings.nfcLatches,
                    extraExpiryEpochMillis = nextUntil,
                )
                ScheduleTransitionNotifier.notifyTransition()
                reconcileDpcFromSettings()
                return@launch
            }
            val triggersById = settings.savedTriggersById
            val hasEnabledGeoProfiles = settings.customProfiles.any { profile ->
                profile.enabled && profile.resolvedTriggers(triggersById).any { it is ProfileTrigger.Geo }
            }
            if (hasEnabledGeoProfiles && locationSource.currentLocation.value == null) {
                locationSource.refreshLocation()
            }
            val now = activeClockSource.now()
            val currentLocation = locationSource.currentLocation.value
            val tapResult = dpcCascadingEngine.processNfcTagTap(
                rawTagUid = rawUid,
                customProfiles = settings.customProfiles,
                savedTriggers = settings.savedTriggers,
                currentDay = now.dayOfWeek,
                currentTime = now.timeOfDay,
                currentLocation = currentLocation,
                currentNfcLatches = settings.nfcLatches,
                nowEpochMillis = nowMillis,
                currentDailyUsage = settings.nfcDailyUsage,
                currentLocalDate = now.localDate,
            )
            if (tapResult.updatedLatches != settings.nfcLatches ||
                tapResult.updatedDailyUsage != settings.nfcDailyUsage
            ) {
                settingsRepository.updateNfcLatches(
                    latches = tapResult.updatedLatches,
                    dailyUsage = tapResult.updatedDailyUsage,
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
                profiles = settings.customProfiles,
                savedTriggers = settings.savedTriggers,
                nfcLatches = tapResult.updatedLatches,
                extraExpiryEpochMillis = settings.breakGlassUntilEpochMillis?.takeIf { it > nowMillis },
            )
            ScheduleTransitionNotifier.notifyTransition()
            reconcileDpcFromSettings()
        }
    }

    companion object {
        @Volatile
        private var fallbackInstance: DryToastAppContainer? = null

        fun from(context: Context): DryToastAppContainer {
            val app = context.applicationContext as? DryToastApplication
            if (app != null) {
                return app.container
            }
            return fallbackInstance ?: synchronized(this) {
                fallbackInstance ?: DryToastAppContainer(context.applicationContext ?: context).also {
                    fallbackInstance = it
                }
            }
        }
    }
}
