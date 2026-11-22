package com.garan.threshold.dpc

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.garan.threshold.ThresholdAppContainer
import com.garan.threshold.core.model.AppAssignment
import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.CustomProfile
import com.garan.threshold.core.model.DayOfWeek
import com.garan.threshold.core.model.NfcLatchState
import com.garan.threshold.core.model.NfcTagEntry
import com.garan.threshold.core.model.NfcTrigger
import com.garan.threshold.core.model.NfcTriggerMode
import com.garan.threshold.core.model.ProfileTrigger
import com.garan.threshold.core.model.SavedTrigger
import com.garan.threshold.core.model.ScheduleTrigger
import com.garan.threshold.core.model.TimeOfDay
import com.garan.threshold.core.model.TriggerRule
import com.garan.threshold.core.port.ClockSource
import com.garan.threshold.core.port.LocalDayTime
import com.garan.threshold.settings.SettingsLockReceiver
import com.garan.threshold.ui.settings.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAppAccessControllerTest {

    private lateinit var context: Context
    private lateinit var fakeRepository: FakeSettingsRepository
    private lateinit var recordingBridge: RecordingDpcPolicyBridge
    private lateinit var controller: AndroidAppAccessController
    private var currentNowMillis: Long = 1_000_000L

    private val dialerKey = ComponentKey("com.google.android.dialer", "DialtactsActivity")
    private val instagramKey = ComponentKey("com.instagram.android", "MainActivity")
    private val tiktokKey = ComponentKey("com.zhiliaoapp.musically", "MainActivity")
    private val youtubeKey = ComponentKey("com.google.android.youtube", "HomeActivity")
    private val thresholdSelfKey = ComponentKey("com.garan.threshold", "MainActivity")
    private val systemSettingsKey = ComponentKey("com.android.settings", "Settings")
    private val systemUiKey = ComponentKey("com.android.systemui", "SystemUIService")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeRepository = FakeSettingsRepository()
        recordingBridge = RecordingDpcPolicyBridge(isDeviceOwner = false)
        currentNowMillis = 1_000_000L
        controller = AndroidAppAccessController(
            context = context,
            settingsRepository = fakeRepository,
            bridge = recordingBridge,
            nowEpochMillisProvider = { currentNowMillis },
        )
    }

    @Test
    fun manifestRegistration_exportsThresholdDeviceAdminReceiverWithBindDeviceAdminPermission() {
        val pm = context.packageManager
        val component = ThresholdDeviceAdminReceiver.getComponentName(context)
        val receiverInfo = pm.getReceiverInfo(component, PackageManager.GET_META_DATA)

        assertThat(receiverInfo.exported).isTrue()
        assertThat(receiverInfo.permission).isEqualTo(Manifest.permission.BIND_DEVICE_ADMIN)
        assertThat(receiverInfo.metaData?.getInt(DeviceAdminReceiver.DEVICE_ADMIN_META_DATA, 0))
            .isNotEqualTo(0)

        val provisioningComponent = ComponentName(context, ThresholdProvisioningActivity::class.java)
        val activityInfo = pm.getActivityInfo(provisioningComponent, 0)
        assertThat(activityInfo.exported).isTrue()
        assertThat(activityInfo.permission).isEqualTo(Manifest.permission.BIND_DEVICE_ADMIN)
    }

    @Test
    fun thresholdProvisioningActivity_handlesGetProvisioningModeAndAdminPolicyCompliance() {
        val getModeIntent = Intent(DevicePolicyManager.ACTION_GET_PROVISIONING_MODE)
        Robolectric.buildActivity(ThresholdProvisioningActivity::class.java, getModeIntent).use { controller ->
            controller.setup()
            val activity = controller.get()
            val shadowActivity = Shadows.shadowOf(activity)
            assertThat(shadowActivity.resultCode).isEqualTo(Activity.RESULT_OK)
            assertThat(
                shadowActivity.resultIntent.getIntExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    -1,
                ),
            ).isEqualTo(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
            assertThat(activity.isFinishing).isTrue()
        }

        val complianceIntent = Intent(DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE)
        Robolectric.buildActivity(ThresholdProvisioningActivity::class.java, complianceIntent).use { controller ->
            controller.setup()
            val activity = controller.get()
            val shadowActivity = Shadows.shadowOf(activity)
            assertThat(shadowActivity.resultCode).isEqualTo(Activity.RESULT_OK)
            assertThat(activity.isFinishing).isTrue()
        }
    }

    @Test
    fun standardMode_whenNotDeviceOwner_performsZeroDpcOperations() = runTest {
        recordingBridge.isDeviceOwner = false

        assertThat(controller.isDeviceOwner()).isFalse()
        assertThat(controller.isDeviceOwnerFlow.value).isFalse()

        val selfProtected = controller.enforceHardenedSelfProtection()
        val suspended = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        val directSuspend = controller.setPackagesSuspended(setOf(instagramKey), suspended = true)

        assertThat(selfProtected).isFalse()
        assertThat(suspended).isEmpty()
        assertThat(directSuspend).isFalse()
        assertThat(recordingBridge.suspendCalls).isEmpty()
        assertThat(recordingBridge.uninstallBlockedCalls).isEmpty()
        assertThat(recordingBridge.userControlDisabledCalls).isEmpty()
        assertThat(recordingBridge.persistentHomeAddedCalls).isEqualTo(0)
        assertThat(recordingBridge.backupServiceEnabledCalls).isEmpty()
    }

    @Test
    fun hardenedMode_enforceSelfProtection_blocksUninstallAndUserControlAndSetsPersistentHome() = runTest {
        recordingBridge.isDeviceOwner = true

        val applied = controller.enforceHardenedSelfProtection()

        assertThat(applied).isTrue()
        assertThat(controller.isDeviceOwnerFlow.value).isTrue()
        assertThat(recordingBridge.uninstallBlockedCalls).containsExactly("com.garan.threshold" to true)
        assertThat(recordingBridge.userControlDisabledCalls).containsExactly(listOf("com.garan.threshold"))
        assertThat(recordingBridge.addedUserRestrictions).containsExactly(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )
        assertThat(recordingBridge.persistentHomeAddedCalls).isEqualTo(1)
        assertThat(recordingBridge.backupServiceEnabledCalls).containsExactly(true)
        assertThat(recordingBridge.organizationNameCalls)
            .containsExactly(AndroidAppAccessController.HARDENED_ORGANIZATION_NAME)
        assertThat(recordingBridge.shortSupportMessageCalls)
            .containsExactly(AndroidAppAccessController.HARDENED_SHORT_SUPPORT_MESSAGE)
    }

    @Test
    fun reconcileBlockedPackages_appliesDiffOnly_andAvoidsRedundantDpcCalls() = runTest {
        recordingBridge.isDeviceOwner = true

        // 1. Initial block of Instagram + TikTok -> 1 suspend call with both packages
        val firstSuspended = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(firstSuspended).containsExactly("com.instagram.android", "com.zhiliaoapp.musically")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(
                packages = listOf("com.instagram.android", "com.zhiliaoapp.musically"),
                suspended = true,
            ),
        )
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages)
            .containsExactly("com.instagram.android", "com.zhiliaoapp.musically")

        // 2. Reconcile identical set (e.g. minute tick) -> ZERO additional DPC calls!
        recordingBridge.suspendCalls.clear()
        val secondSuspended = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(secondSuspended).containsExactly("com.instagram.android", "com.zhiliaoapp.musically")
        assertThat(recordingBridge.suspendCalls).isEmpty()

        // 3. Unblock TikTok and block YouTube -> only YouTube is suspended and only TikTok is unsuspended
        val thirdSuspended = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, youtubeKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(thirdSuspended).containsExactly("com.instagram.android", "com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = true),
            SuspendCall(packages = listOf("com.zhiliaoapp.musically"), suspended = false),
        )
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages)
            .containsExactly("com.instagram.android", "com.google.android.youtube")

        // 4. All profiles end (empty blocked set) -> both Instagram and YouTube are unsuspended
        recordingBridge.suspendCalls.clear()
        val fourthSuspended = controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(fourthSuspended).isEmpty()
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(
                packages = listOf("com.google.android.youtube", "com.instagram.android"),
                suspended = false,
            ),
        )
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages).isEmpty()
    }

    @Test
    fun reconcileBlockedPackages_neverSuspendsDefaultDialerThresholdOrProtectedSystemPackages() = runTest {
        recordingBridge.isDeviceOwner = true

        val suspended = controller.reconcileBlockedPackages(
            blockedComponents = setOf(
                dialerKey,
                thresholdSelfKey,
                systemSettingsKey,
                systemUiKey,
                ComponentKey("android", "ResolverActivity"),
                ComponentKey("com.android.phone", "EmergencyDialer"),
                instagramKey,
            ),
            defaultDialerPackage = dialerKey.packageName,
        )

        // Only Instagram may be suspended; Dialer, Threshold, Settings, SystemUI, Phone, and "android" are protected
        assertThat(suspended).containsExactly("com.instagram.android")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.instagram.android"), suspended = true),
        )
    }

    @Test
    fun reconcileBlockedPackages_recoversPersistedSuspendedSetAfterProcessRestartAndUnsuspendsWhenProfileEnds() =
        runTest {
            recordingBridge.isDeviceOwner = true

            // Session 1: Suspend Instagram and TikTok
            controller.reconcileBlockedPackages(
                blockedComponents = setOf(instagramKey, tiktokKey),
                defaultDialerPackage = dialerKey.packageName,
            )
            assertThat(fakeRepository.getSettings().dpcSuspendedPackages)
                .containsExactly("com.instagram.android", "com.zhiliaoapp.musically")

            // Simulate process restart: new AndroidAppAccessController instance with empty in-memory state,
            // sharing the same persisted SettingsRepository
            val freshBridge = RecordingDpcPolicyBridge(isDeviceOwner = true)
            val restartedController = AndroidAppAccessController(
                context = context,
                settingsRepository = fakeRepository,
                bridge = freshBridge,
            )

            // Profile ends after process restart (blockedComponents is now empty)
            val remaining = restartedController.reconcileBlockedPackages(
                blockedComponents = emptySet(),
                defaultDialerPackage = dialerKey.packageName,
            )

            assertThat(remaining).isEmpty()
            assertThat(freshBridge.suspendCalls).containsExactly(
                SuspendCall(
                    packages = listOf("com.instagram.android", "com.zhiliaoapp.musically"),
                    suspended = false,
                ),
            )
            assertThat(fakeRepository.getSettings().dpcSuspendedPackages).isEmpty()
        }

    @Test
    fun reconcileBlockedPackages_handlesPartialOsRejectionAndRetriesRejectedPackageOnNextPass() = runTest {
        recordingBridge.isDeviceOwner = true
        // Simulate OS temporarily refusing to suspend TikTok (returning it in failed array)
        recordingBridge.packagesRefusedToSuspend = setOf("com.zhiliaoapp.musically")

        val firstPass = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(firstPass).containsExactly("com.instagram.android")
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages).containsExactly("com.instagram.android")

        // Next pass: OS now allows suspending TikTok -> only TikTok is retried and succeeds
        recordingBridge.packagesRefusedToSuspend = emptySet()
        recordingBridge.suspendCalls.clear()
        val secondPass = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(secondPass).containsExactly("com.instagram.android", "com.zhiliaoapp.musically")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.zhiliaoapp.musically"), suspended = true),
        )
    }

    @Test
    fun deactivateHardenedMode_unsuspendsAllAppsClearsSelfProtectionAndRelinquishesDeviceOwner() = runTest {
        recordingBridge.isDeviceOwner = true
        fakeRepository.updateBaseProfile(
            BaseProfileSettings(
                appAssignments = mapOf(
                    instagramKey to AppAssignment.Blocked,
                    youtubeKey to AppAssignment.Guarded(),
                ),
            ),
        )
        controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey, tiktokKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        recordingBridge.suspendCalls.clear()

        val deactivated = controller.deactivateHardenedMode()

        assertThat(deactivated).isTrue()
        // Unsuspends both actively suspended packages (Instagram, TikTok) and configured packages (YouTube)
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(
                packages = listOf(
                    "com.google.android.youtube",
                    "com.instagram.android",
                    "com.zhiliaoapp.musically",
                ),
                suspended = false,
            ),
        )
        assertThat(recordingBridge.persistentHomeClearedPackages).containsExactly("com.garan.threshold")
        assertThat(recordingBridge.clearedUserRestrictions).containsExactly(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )
        assertThat(recordingBridge.organizationNameCalls.last()).isNull()
        assertThat(recordingBridge.shortSupportMessageCalls.last()).isNull()
        assertThat(recordingBridge.userControlDisabledCalls.last()).isEmpty()
        assertThat(recordingBridge.uninstallBlockedCalls.last()).isEqualTo("com.garan.threshold" to false)
        assertThat(recordingBridge.clearedDeviceOwnerPackages).containsExactly("com.garan.threshold")
        assertThat(controller.isDeviceOwnerFlow.value).isFalse()
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages).isEmpty()
    }

    @Test
    fun settingsLockReceiver_disableHardenedModeBroadcast_triggersFullCleanupViaAdb() = runTest {
        recordingBridge.isDeviceOwner = true
        controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey),
            defaultDialerPackage = dialerKey.packageName,
        )
        recordingBridge.suspendCalls.clear()

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val receiver = SettingsLockReceiver(
            settingsRepositoryOverride = fakeRepository,
            appAccessControllerOverride = controller,
            ioDispatcher = testDispatcher,
        )

        receiver.onReceive(context, Intent(SettingsLockReceiver.ACTION_DISABLE_HARDENED_MODE))

        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.instagram.android"), suspended = false),
        )
        assertThat(recordingBridge.clearedDeviceOwnerPackages).containsExactly("com.garan.threshold")
        assertThat(controller.isDeviceOwnerFlow.value).isFalse()
    }

    @Test
    fun realShadowDevicePolicyManager_endToEndProvisioningAndScheduleAlarmBackgroundSuspension() = runTest {
        val container = ThresholdAppContainer.from(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val shadowDpm = Shadows.shadowOf(dpm)
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val adminComponent = ThresholdDeviceAdminReceiver.getComponentName(context)

        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = "com.instagram.android"
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = "com.instagram.android"
                }
            },
        )

        try {
            // 1. Configure a schedule profile blocking Instagram on Monday 09:00..17:00
            val workProfile = CustomProfile(
                id = "work",
                name = "Work",
                enabled = true,
                priority = 0,
                trigger = ProfileTrigger.Schedule(
                    ScheduleTrigger(
                        days = setOf(DayOfWeek.MONDAY),
                        startTime = TimeOfDay(9, 0),
                        endTime = TimeOfDay(17, 0),
                    ),
                ),
                appAssignments = mapOf(instagramKey to AppAssignment.Blocked),
            )
            container.settingsRepository.updateBaseProfile(BaseProfileSettings())
            container.settingsRepository.updateCustomProfiles(listOf(workProfile))
            container.clockOverrideForTesting = ClockSource {
                LocalDayTime(DayOfWeek.MONDAY, TimeOfDay(10, 0))
            }

            // 2. Provision ThresholdDeviceAdminReceiver as Device Owner in ShadowDevicePolicyManager
            shadowDpm.setActiveAdmin(adminComponent)
            shadowDpm.setDeviceOwner(adminComponent)
            assertThat(container.appAccessController.isDeviceOwner()).isTrue()

            // 3. Reconcile from background (as ScheduleAlarmReceiver / ThresholdDeviceAdminReceiver does)
            container.appAccessController.enforceHardenedSelfProtection()
            container.reconcileDpcFromSettings()

            assertThat(dpm.isUninstallBlocked(adminComponent, context.packageName)).isTrue()
            assertThat(container.settingsRepository.getSettings().dpcSuspendedPackages)
                .containsExactly("com.instagram.android")

            // 4. Advance clock past 17:00 (profile inactive) and trigger ScheduleAlarmReceiver
            container.clockOverrideForTesting = ClockSource {
                LocalDayTime(DayOfWeek.MONDAY, TimeOfDay(18, 0))
            }
            container.reconcileDpcFromSettings()

            assertThat(container.settingsRepository.getSettings().dpcSuspendedPackages).isEmpty()

            // 5. Deactivate Hardened Mode
            container.appAccessController.deactivateHardenedMode()
            assertThat(dpm.isUninstallBlocked(adminComponent, context.packageName)).isFalse()
        } finally {
            container.clockOverrideForTesting = null
            container.deviceOwnerOverrideForTesting = null
            container.settingsRepository.updateDpcSuspendedPackages(emptySet())
            container.settingsRepository.updateCustomProfiles(emptyList())
        }
    }

    @Test
    fun backgroundReconcile_prunesCompanionInvalidatedNfcLatchesEvenInStandardMode() = runTest {
        val container = ThresholdAppContainer.from(context)
        try {
            container.deviceOwnerOverrideForTesting = false
            val scheduleTrigger = SavedTrigger(
                id = "trig-schedule",
                name = "Work Hours",
                trigger = ProfileTrigger.Schedule(
                    ScheduleTrigger(
                        days = setOf(DayOfWeek.MONDAY),
                        startTime = TimeOfDay(9, 0),
                        endTime = TimeOfDay(17, 0),
                    ),
                ),
            )
            val nfcTrigger = SavedTrigger(
                id = "trig-nfc",
                name = "Desk Tag",
                trigger = ProfileTrigger.Nfc(
                    NfcTrigger(
                        tags = listOf(NfcTagEntry("04:A1:B2:C3", "Desk")),
                        mode = NfcTriggerMode.TOGGLE_ON_TAP,
                    ),
                ),
            )
            val profile = CustomProfile(
                id = "work-nfc",
                name = "Work NFC",
                enabled = true,
                priority = 0,
                activationRules = listOf(TriggerRule(listOf("trig-schedule", "trig-nfc"))),
                appAssignments = mapOf(instagramKey to AppAssignment.Blocked),
            )
            container.settingsRepository.saveTrigger(scheduleTrigger)
            container.settingsRepository.saveTrigger(nfcTrigger)
            container.settingsRepository.updateCustomProfiles(listOf(profile))
            container.settingsRepository.updateNfcLatches(
                mapOf(
                    "trig-nfc" to NfcLatchState(
                        triggerId = "trig-nfc",
                        latchedAtEpochMillis = 1_000L,
                        latchedByTagUid = "04:A1:B2:C3",
                    ),
                ),
            )

            // At 18:00 Monday (schedule ended), background reconciliation must unlatch and persist empty latches
            container.clockOverrideForTesting = ClockSource {
                LocalDayTime(DayOfWeek.MONDAY, TimeOfDay(18, 0))
            }
            container.reconcileDpcFromSettings()

            assertThat(container.settingsRepository.getSettings().nfcLatches).isEmpty()
        } finally {
            container.clockOverrideForTesting = null
            container.deviceOwnerOverrideForTesting = null
            container.settingsRepository.updateNfcLatches(emptyMap())
            container.settingsRepository.updateCustomProfiles(emptyList())
            container.settingsRepository.deleteTrigger("trig-schedule")
            container.settingsRepository.deleteTrigger("trig-nfc")
        }
    }

    @Test
    fun guardedComponents_suspendedAtRest_unsuspendedDuringGraceLease_andResuspendedWhenLeaseExpires() = runTest {
        recordingBridge.isDeviceOwner = true

        // 1. At rest: Instagram is Blocked, YouTube and Dialer are Guarded -> Instagram and YouTube suspended
        val suspendedAtRest = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey, dialerKey),
        )
        assertThat(suspendedAtRest).containsExactly("com.instagram.android", "com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(
                packages = listOf("com.google.android.youtube", "com.instagram.android"),
                suspended = true,
            ),
        )

        // 2. User completes friction gate for YouTube with 5-minute grace period
        recordingBridge.suspendCalls.clear()
        val granted = controller.grantGuardedSession(
            component = youtubeKey,
            graceDurationMillis = 5 * 60_000L,
            defaultDialerPackage = dialerKey.packageName,
        )
        assertThat(granted).isTrue()
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = false),
        )
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages).containsExactly("com.instagram.android")

        // 3. Two minutes later (within 5-min grace period), user returns home (ON_RESUME) -> stays unsuspended
        recordingBridge.suspendCalls.clear()
        currentNowMillis += 2 * 60_000L
        val suspendedDuringLease = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey, dialerKey),
        )
        assertThat(suspendedDuringLease).containsExactly("com.instagram.android")
        assertThat(recordingBridge.suspendCalls).isEmpty()

        // 4. Five minutes elapsed -> next ON_RESUME or ACTION_SCREEN_OFF re-suspends YouTube
        currentNowMillis += 3 * 60_000L
        val suspendedAfterExpiry = controller.reconcileBlockedPackages(
            blockedComponents = setOf(instagramKey),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey, dialerKey),
        )
        assertThat(suspendedAfterExpiry).containsExactly("com.instagram.android", "com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = true),
        )
        assertThat(fakeRepository.getSettings().dpcSuspendedPackages)
            .containsExactly("com.instagram.android", "com.google.android.youtube")
    }

    @Test
    fun guardedSession_immediatelyRevokedAndSuspended_whenProfileUpgradesAppFromGuardedToBlocked() = runTest {
        recordingBridge.isDeviceOwner = true

        controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        controller.grantGuardedSession(
            component = youtubeKey,
            graceDurationMillis = 15 * 60_000L,
            defaultDialerPackage = dialerKey.packageName,
        )
        recordingBridge.suspendCalls.clear()

        // Custom profile activates 1 minute later and blocks YouTube
        currentNowMillis += 60_000L
        val suspendedWhenBlocked = controller.reconcileBlockedPackages(
            blockedComponents = setOf(youtubeKey),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = emptySet(),
        )
        assertThat(suspendedWhenBlocked).containsExactly("com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = true),
        )
    }

    @Test
    fun zeroMinuteGracePeriod_appliesMinimumLaunchHandoverGuard_thenResuspendsAfterHandoverWindow() = runTest {
        recordingBridge.isDeviceOwner = true

        controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        controller.grantGuardedSession(
            component = youtubeKey,
            graceDurationMillis = 0L,
            defaultDialerPackage = dialerKey.packageName,
        )
        recordingBridge.suspendCalls.clear()

        // 1 second later (during activity start handover): still unsuspended
        currentNowMillis += 1_000L
        val duringHandover = controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        assertThat(duringHandover).isEmpty()
        assertThat(recordingBridge.suspendCalls).isEmpty()

        // 6 seconds later (user returns home or locks screen): re-suspended
        currentNowMillis += 5_000L
        val afterReturn = controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        assertThat(afterReturn).containsExactly("com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = true),
        )
    }

    @Test
    fun revokeGuardedSession_clearsActiveLeaseSoNextReconcileResuspendsImmediately() = runTest {
        recordingBridge.isDeviceOwner = true

        controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        controller.grantGuardedSession(
            component = youtubeKey,
            graceDurationMillis = 15 * 60_000L,
            defaultDialerPackage = dialerKey.packageName,
        )
        recordingBridge.suspendCalls.clear()

        val revoked = controller.revokeGuardedSession(youtubeKey.packageName)
        assertThat(revoked).isTrue()

        val suspended = controller.reconcileBlockedPackages(
            blockedComponents = emptySet(),
            defaultDialerPackage = dialerKey.packageName,
            guardedComponents = setOf(youtubeKey),
        )
        assertThat(suspended).containsExactly("com.google.android.youtube")
        assertThat(recordingBridge.suspendCalls).containsExactly(
            SuspendCall(packages = listOf("com.google.android.youtube"), suspended = true),
        )
    }

    private data class SuspendCall(val packages: List<String>, val suspended: Boolean)

    private class RecordingDpcPolicyBridge(var isDeviceOwner: Boolean) : DpcPolicyBridge {
        var packagesRefusedToSuspend: Set<String> = emptySet()
        val suspendCalls = mutableListOf<SuspendCall>()
        val uninstallBlockedCalls = mutableListOf<Pair<String, Boolean>>()
        val userControlDisabledCalls = mutableListOf<List<String>>()
        val addedUserRestrictions = mutableListOf<String>()
        val clearedUserRestrictions = mutableListOf<String>()
        var persistentHomeAddedCalls = 0
        val persistentHomeClearedPackages = mutableListOf<String>()
        val backupServiceEnabledCalls = mutableListOf<Boolean>()
        val organizationNameCalls = mutableListOf<CharSequence?>()
        val shortSupportMessageCalls = mutableListOf<CharSequence?>()
        val clearedDeviceOwnerPackages = mutableListOf<String>()

        override fun isDeviceOwnerApp(packageName: String): Boolean = isDeviceOwner

        override fun setPackagesSuspended(
            admin: ComponentName,
            packageNames: Array<String>,
            suspended: Boolean,
        ): Array<String> {
            suspendCalls.add(SuspendCall(packageNames.toList(), suspended))
            return if (suspended) {
                packageNames.filter { it in packagesRefusedToSuspend }.toTypedArray()
            } else {
                emptyArray()
            }
        }

        override fun setUninstallBlocked(admin: ComponentName, packageName: String, uninstallBlocked: Boolean) {
            uninstallBlockedCalls.add(packageName to uninstallBlocked)
        }

        override fun setUserControlDisabledPackages(admin: ComponentName, packages: List<String>) {
            userControlDisabledCalls.add(packages)
        }

        override fun addUserRestriction(admin: ComponentName, key: String) {
            addedUserRestrictions.add(key)
        }

        override fun clearUserRestriction(admin: ComponentName, key: String) {
            clearedUserRestrictions.add(key)
        }

        override fun addPersistentPreferredActivity(
            admin: ComponentName,
            filter: IntentFilter,
            activity: ComponentName,
        ) {
            persistentHomeAddedCalls++
        }

        override fun clearPackagePersistentPreferredActivities(admin: ComponentName, packageName: String) {
            persistentHomeClearedPackages.add(packageName)
        }

        override fun setBackupServiceEnabled(admin: ComponentName, enabled: Boolean) {
            backupServiceEnabledCalls.add(enabled)
        }

        override fun setOrganizationName(admin: ComponentName, title: CharSequence?) {
            organizationNameCalls.add(title)
        }

        override fun setShortSupportMessage(admin: ComponentName, message: CharSequence?) {
            shortSupportMessageCalls.add(message)
        }

        override fun clearDeviceOwnerApp(packageName: String) {
            clearedDeviceOwnerPackages.add(packageName)
            isDeviceOwner = false
        }
    }
}
