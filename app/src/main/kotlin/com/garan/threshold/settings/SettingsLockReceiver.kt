package com.garan.threshold.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.garan.threshold.ThresholdAppContainer
import com.garan.threshold.core.port.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver allowing power users to lock or unlock Threshold settings via ADB:
 *
 * - Lock:
 *   `adb shell am broadcast -a com.garan.threshold.action.LOCK_SETTINGS -p com.garan.threshold`
 * - Unlock:
 *   `adb shell am broadcast -a com.garan.threshold.action.UNLOCK_SETTINGS -p com.garan.threshold`
 *
 * Protected in `AndroidManifest.xml` with `android.permission.DUMP` so `adb shell` (UID 2000)
 * is authorized while third-party apps cannot spoof the broadcast.
 */
class SettingsLockReceiver internal constructor(
    private val settingsRepositoryOverride: SettingsRepository?,
    private val ioDispatcher: CoroutineDispatcher,
) : BroadcastReceiver() {

    constructor() : this(
        settingsRepositoryOverride = null,
        ioDispatcher = Dispatchers.IO,
    )

    override fun onReceive(context: Context, intent: Intent?) {
        val targetLockState = when (intent?.action) {
            ACTION_LOCK_SETTINGS -> true
            ACTION_UNLOCK_SETTINGS -> false
            else -> return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext ?: context
        CoroutineScope(ioDispatcher).launch {
            try {
                val repository = settingsRepositoryOverride
                    ?: ThresholdAppContainer.from(appContext).settingsRepository
                repository.setSettingsLocked(targetLockState)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCK_SETTINGS = "com.garan.threshold.action.LOCK_SETTINGS"
        const val ACTION_UNLOCK_SETTINGS = "com.garan.threshold.action.UNLOCK_SETTINGS"
    }
}
