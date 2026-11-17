package com.garan.threshold.dpc

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.garan.threshold.ThresholdAppContainer
import com.garan.threshold.schedule.ScheduleTransitionNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device Policy Controller (DPC) receiver enabling Threshold's optional Hardened Mode
 * (Device Owner).
 *
 * Provisioned either via Android's 6-tap Welcome screen QR setup or via ADB:
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

/**
 * Headless provisioning callback activity required by Android 12+ (`targetSdk >= 31`) during
 * 6-tap Welcome screen QR code provisioning (`ManagedProvisioning`).
 *
 * Protected by `android.permission.BIND_DEVICE_ADMIN` in `AndroidManifest.xml` so only the
 * Android OS setup wizard can invoke it.
 */
class ThresholdProvisioningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                val result = Intent().apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                        DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                    )
                }
                setResult(RESULT_OK, result)
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                val appContext = applicationContext ?: this
                val container = ThresholdAppContainer.from(appContext)
                container.appAccessController.applySelfProtectionImmediate()
                CoroutineScope(Dispatchers.IO).launch {
                    container.reconcileDpcFromSettings()
                    ScheduleTransitionNotifier.notifyTransition()
                }
                setResult(RESULT_OK)
            }
            else -> {
                setResult(RESULT_CANCELED)
            }
        }
        finish()
    }
}
