package com.garan.threshold.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.garan.threshold.ThresholdAppContainer
import com.garan.threshold.schedule.ScheduleTransitionNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device Policy Controller (DPC) receiver enabling Threshold's optional Hardened Mode
 * (Device Owner).
 *
 * Provisioned via ADB:
 * `adb shell dpm set-device-owner com.garan.threshold/.dpc.ThresholdDeviceAdminReceiver`
 */
class ThresholdDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        syncHardenedModeState(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        syncHardenedModeState(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val appContext = context.applicationContext ?: context
        ThresholdAppContainer.from(appContext).appAccessController.isDeviceOwner()
        ScheduleTransitionNotifier.notifyTransition()
    }

    private fun syncHardenedModeState(context: Context) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext ?: context
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = ThresholdAppContainer.from(appContext)
                container.appAccessController.enforceHardenedSelfProtection()
                container.reconcileDpcFromSettings()
                ScheduleTransitionNotifier.notifyTransition()
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext ?: context, ThresholdDeviceAdminReceiver::class.java)
    }
}
