package com.garan.threshold

import android.app.Application
import android.content.Context
import com.garan.threshold.core.cascading.CascadingResolutionEngine
import com.garan.threshold.core.model.ProfileTrigger
import com.garan.threshold.core.port.ClockSource
import com.garan.threshold.core.port.SystemClockSource
import com.garan.threshold.data.antimetrics.AndroidAntiMetricsRepository
import com.garan.threshold.data.haptics.AndroidHapticEngine
import com.garan.threshold.data.location.AndroidLocationSource
import com.garan.threshold.data.packageindex.AndroidPackageIndexSource
import com.garan.threshold.data.settings.AndroidSettingsRepository
import com.garan.threshold.data.wallpaper.AndroidWallpaperController
import com.garan.threshold.dpc.AndroidAppAccessController
import com.garan.threshold.nfc.AndroidNfcReaderSource
import com.garan.threshold.schedule.AndroidProfileCapabilityChecker
import com.garan.threshold.schedule.AndroidScheduleAlarmScheduler
import com.garan.threshold.ui.launcher.AndroidLauncherRoleController
import com.garan.threshold.ui.launcher.DialerPackageResolver
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class ThresholdApplication : Application() {
    val container: ThresholdAppContainer by lazy { ThresholdAppContainer(applicationContext) }
}

/**
 * Process-scoped dependency container holding Activity-independent services and test hooks.
 * Ensures ViewModels never capture a [MainActivity] instance across configuration/recreation
 * changes and that both UI and ViewModels observe the same shared state.
 */
class ThresholdAppContainer(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    val packageIndexSource by lazy { AndroidPackageIndexSource(appContext) }
    val hapticEngine by lazy { AndroidHapticEngine(appContext) }
    val settingsRepository by lazy { AndroidSettingsRepository(appContext) }
    val locationSource by lazy { AndroidLocationSource(appContext) }
    val antiMetricsRepository by lazy { AndroidAntiMetricsRepository(appContext) }
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

    val appAccessController by lazy {
        AndroidAppAccessController(
            context = appContext,
            settingsRepository = settingsRepository,
            deviceOwnerOverrideProvider = { deviceOwnerOverrideForTesting },
        )
    }

    private val dpcCascadingEngine = CascadingResolutionEngine()

    val activeClockSource: ClockSource = ClockSource {
        clockOverrideForTesting?.now() ?: SystemClockSource.now()
    }

    val activeNowEpochMillisProvider: () -> Long = {
        nowEpochMillisOverrideForTesting ?: System.currentTimeMillis()
    }

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
     * Safe to call from background receivers ([com.garan.threshold.schedule.ScheduleAlarmReceiver],
     * [com.garan.threshold.dpc.ThresholdDeviceAdminReceiver], [com.garan.threshold.settings.SettingsLockReceiver])
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
        )
        if (workingSet.activeNfcLatches != settings.nfcLatches) {
            settingsRepository.updateNfcLatches(workingSet.activeNfcLatches)
            scheduleAlarmScheduler.syncNextScheduleAlarm(
                profiles = settings.customProfiles,
                savedTriggers = settings.savedTriggers,
                nfcLatches = workingSet.activeNfcLatches,
            )
        }
        if (appAccessController.isDeviceOwner()) {
            appAccessController.reconcileBlockedPackages(
                blockedComponents = workingSet.blockedComponents,
                defaultDialerPackage = dialerPkg,
            )
        }
    }

    companion object {
        @Volatile
        private var fallbackInstance: ThresholdAppContainer? = null

        fun from(context: Context): ThresholdAppContainer {
            val app = context.applicationContext as? ThresholdApplication
            if (app != null) {
                return app.container
            }
            return fallbackInstance ?: synchronized(this) {
                fallbackInstance ?: ThresholdAppContainer(context.applicationContext ?: context).also {
                    fallbackInstance = it
                }
            }
        }
    }
}
