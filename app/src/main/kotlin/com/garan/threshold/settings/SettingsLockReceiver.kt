package com.garan.threshold.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.garan.threshold.ThresholdAppContainer
import com.garan.threshold.core.port.AppAccessController
import com.garan.threshold.core.port.SettingsRepository
import com.garan.threshold.schedule.ScheduleTransitionNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver allowing power users to lock/unlock Threshold settings or manage
 * Hardened Mode (Device Owner) via ADB:
 *
 * - Lock Settings:
 *   `adb shell am broadcast -a com.garan.threshold.action.LOCK_SETTINGS -n com.garan.threshold/.settings.SettingsLockReceiver`
 * - Unlock Settings:
 *   `adb shell am broadcast -a com.garan.threshold.action.UNLOCK_SETTINGS -n com.garan.threshold/.settings.SettingsLockReceiver`
 * - Disable Hardened Mode (unsuspends all apps, clears self-protection locks, releases Device Owner):
 *   `adb shell am broadcast -a com.garan.threshold.action.DISABLE_HARDENED_MODE -n com.garan.threshold/.settings.SettingsLockReceiver`
 * - Sync Hardened Mode (re-applies self-protection and reconciles package suspension):
 *   `adb shell am broadcast -a com.garan.threshold.action.SYNC_HARDENED_MODE -n com.garan.threshold/.settings.SettingsLockReceiver`
 *
 * Protected in `AndroidManifest.xml` with `android.permission.DUMP` so `adb shell` (UID 2000)
 * is authorized while third-party apps cannot spoof the broadcast.
 */
class SettingsLockReceiver internal constructor(
    private val settingsRepositoryOverride: SettingsRepository?,
    private val appAccessControllerOverride: AppAccessController?,
    private val ioDispatcher: CoroutineDispatcher,
) : BroadcastReceiver() {

    internal constructor(
        settingsRepositoryOverride: SettingsRepository?,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        settingsRepositoryOverride = settingsRepositoryOverride,
        appAccessControllerOverride = null,
        ioDispatcher = ioDispatcher,
    )

    constructor() : this(
        settingsRepositoryOverride = null,
        appAccessControllerOverride = null,
        ioDispatcher = Dispatchers.IO,
    )

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext ?: context
        CoroutineScope(ioDispatcher).launch {
            try {
                when (action) {
                    ACTION_LOCK_SETTINGS,
                    ACTION_UNLOCK_SETTINGS,
                    -> {
                        val targetLockState = (action == ACTION_LOCK_SETTINGS)
                        val repository = settingsRepositoryOverride
                            ?: ThresholdAppContainer.from(appContext).settingsRepository
                        repository.setSettingsLocked(targetLockState)
                    }
                    ACTION_DISABLE_HARDENED_MODE -> {
                        val controller = appAccessControllerOverride
                            ?: ThresholdAppContainer.from(appContext).appAccessController
                        controller.deactivateHardenedMode()
                        ScheduleTransitionNotifier.notifyTransition()
                    }
                    ACTION_SYNC_HARDENED_MODE -> {
                        val container = ThresholdAppContainer.from(appContext)
                        val controller = appAccessControllerOverride ?: container.appAccessController
                        controller.enforceHardenedSelfProtection()
                        if (appAccessControllerOverride == null) {
                            container.reconcileDpcFromSettings()
                        }
                        ScheduleTransitionNotifier.notifyTransition()
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCK_SETTINGS = "com.garan.threshold.action.LOCK_SETTINGS"
        const val ACTION_UNLOCK_SETTINGS = "com.garan.threshold.action.UNLOCK_SETTINGS"
        const val ACTION_DISABLE_HARDENED_MODE = "com.garan.threshold.action.DISABLE_HARDENED_MODE"
        const val ACTION_SYNC_HARDENED_MODE = "com.garan.threshold.action.SYNC_HARDENED_MODE"

        private val HANDLED_ACTIONS = setOf(
            ACTION_LOCK_SETTINGS,
            ACTION_UNLOCK_SETTINGS,
            ACTION_DISABLE_HARDENED_MODE,
            ACTION_SYNC_HARDENED_MODE,
        )
    }
}
