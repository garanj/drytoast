package com.garan.drytoast.dpc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.garan.drytoast.MainActivity
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.port.AppAccessController
import com.garan.drytoast.core.port.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Low-level bridge abstracting Android [DevicePolicyManager] calls for Hardened Mode.
 * Allows deterministic unit testing of partial-failure arrays and security exceptions while
 * delegating directly to [DevicePolicyManager] in production and Robolectric tests.
 */
interface DpcPolicyBridge {
    fun isDeviceOwnerApp(packageName: String): Boolean
    fun setPackagesSuspended(admin: ComponentName, packageNames: Array<String>, suspended: Boolean): Array<String>
    fun setUninstallBlocked(admin: ComponentName, packageName: String, uninstallBlocked: Boolean)
    fun setUserControlDisabledPackages(admin: ComponentName, packages: List<String>)
    fun addUserRestriction(admin: ComponentName, key: String)
    fun clearUserRestriction(admin: ComponentName, key: String)
    fun addPersistentPreferredActivity(admin: ComponentName, filter: IntentFilter, activity: ComponentName)
    fun clearPackagePersistentPreferredActivities(admin: ComponentName, packageName: String)
    fun setBackupServiceEnabled(admin: ComponentName, enabled: Boolean)
    fun setOrganizationName(admin: ComponentName, title: CharSequence?)
    fun setShortSupportMessage(admin: ComponentName, message: CharSequence?)
    fun setDeviceOwnerLockScreenInfo(admin: ComponentName, info: CharSequence?)
    fun clearDeviceOwnerApp(packageName: String)
}

internal class AndroidDpcPolicyBridge(private val context: Context) : DpcPolicyBridge {
    private val dpm: DevicePolicyManager?
        get() = (context.applicationContext ?: context)
            .getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    override fun isDeviceOwnerApp(packageName: String): Boolean =
        runCatching { dpm?.isDeviceOwnerApp(packageName) == true }.getOrDefault(false)

    override fun setPackagesSuspended(
        admin: ComponentName,
        packageNames: Array<String>,
        suspended: Boolean,
    ): Array<String> = runCatching {
        dpm?.setPackagesSuspended(admin, packageNames, suspended) ?: packageNames
    }.getOrDefault(packageNames)

    override fun setUninstallBlocked(admin: ComponentName, packageName: String, uninstallBlocked: Boolean) {
        runCatching { dpm?.setUninstallBlocked(admin, packageName, uninstallBlocked) }
    }

    override fun setUserControlDisabledPackages(admin: ComponentName, packages: List<String>) {
        runCatching { dpm?.setUserControlDisabledPackages(admin, packages) }
    }

    override fun addUserRestriction(admin: ComponentName, key: String) {
        runCatching { dpm?.addUserRestriction(admin, key) }
    }

    override fun clearUserRestriction(admin: ComponentName, key: String) {
        runCatching { dpm?.clearUserRestriction(admin, key) }
    }

    override fun addPersistentPreferredActivity(admin: ComponentName, filter: IntentFilter, activity: ComponentName) {
        runCatching { dpm?.addPersistentPreferredActivity(admin, filter, activity) }
    }

    override fun clearPackagePersistentPreferredActivities(admin: ComponentName, packageName: String) {
        runCatching { dpm?.clearPackagePersistentPreferredActivities(admin, packageName) }
    }

    override fun setBackupServiceEnabled(admin: ComponentName, enabled: Boolean) {
        runCatching { dpm?.setBackupServiceEnabled(admin, enabled) }
    }

    override fun setOrganizationName(admin: ComponentName, title: CharSequence?) {
        runCatching { dpm?.setOrganizationName(admin, title) }
    }

    override fun setShortSupportMessage(admin: ComponentName, message: CharSequence?) {
        runCatching { dpm?.setShortSupportMessage(admin, message) }
    }

    override fun setDeviceOwnerLockScreenInfo(admin: ComponentName, info: CharSequence?) {
        runCatching { dpm?.setDeviceOwnerLockScreenInfo(admin, info) }
    }

    @Suppress("DEPRECATION")
    override fun clearDeviceOwnerApp(packageName: String) {
        runCatching { dpm?.clearDeviceOwnerApp(packageName) }
    }
}

/**
 * Android Device Policy Controller (DPC) implementation of [AppAccessController].
 *
 * When Dry Toast holds Device Owner privileges ("Hardened Mode"):
 * 1. Enforces anti-tamper self-protection (`setUninstallBlocked`, `setUserControlDisabledPackages`,
 *    and persistent default `CATEGORY_HOME` launcher activity).
 * 2. Diff-reconciles `Blocked` packages and resting-state `Guarded` packages via `setPackagesSuspended`,
 *    while strictly preserving Prime Invariant #2 (Universal Dialer Safety Invariant) and temporarily
 *    exempting `Guarded` packages holding an active post-friction session lease.
 * 3. Persists the set of suspended packages in [SettingsRepository] so unblocked apps or ended
 *    profiles are reliably unsuspended even across process restarts or device reboots.
 */
class AndroidAppAccessController(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val bridge: DpcPolicyBridge = AndroidDpcPolicyBridge(context.applicationContext ?: context),
    private val adminComponent: ComponentName = DryToastDeviceAdminReceiver.getComponentName(context),
    private val homeActivityComponent: ComponentName = ComponentName(
        context.applicationContext ?: context,
        MainActivity::class.java,
    ),
    private val selfPackageName: String = (context.applicationContext ?: context).packageName,
    private val deviceOwnerOverrideProvider: () -> Boolean? = { null },
    private val nowEpochMillisProvider: () -> Long = System::currentTimeMillis,
) : AppAccessController {

    private val mutex = Mutex()

    @Volatile
    private var lastSuspendedPackages: Set<String> = emptySet()

    @Volatile
    private var isSelfProtectionApplied: Boolean = false

    private val activeGuardedLeases = mutableMapOf<String, Long>()

    @Volatile
    private var nextLeaseExpiryEpochMillis: Long = Long.MAX_VALUE

    private var lastReconciledBlocked: Set<ComponentKey>? = null
    private var lastReconciledGuarded: Set<ComponentKey>? = null
    private var lastReconciledDialer: String? = null
    private var lastBlockNewAppInstalls: Boolean? = null
    private var lastOwnerName: String? = null
    private var lastLockScreenInfo: String? = null
    private var hasPendingRetry: Boolean = false

    private val _isDeviceOwnerFlow = MutableStateFlow(computeIsDeviceOwner())
    override val isDeviceOwnerFlow: StateFlow<Boolean> = _isDeviceOwnerFlow.asStateFlow()

    private fun computeIsDeviceOwner(): Boolean =
        deviceOwnerOverrideProvider() ?: bridge.isDeviceOwnerApp(selfPackageName)

    override fun isDeviceOwner(): Boolean {
        val active = computeIsDeviceOwner()
        if (!active) {
            isSelfProtectionApplied = false
        }
        _isDeviceOwnerFlow.value = active
        return active
    }

    override suspend fun enforceHardenedSelfProtection(): Boolean = mutex.withLock {
        val general = settingsRepository.getSettings().general
        val applied = applySelfProtectionImmediate(ownerName = general.effectiveHardenedOwnerName())
        if (applied) {
            syncInstallAppsRestrictionLocked(general.blockNewAppInstalls)
            syncLockScreenBrandingLocked(general)
        }
        applied
    }

    private fun syncInstallAppsRestrictionLocked(blockNewAppInstalls: Boolean) {
        if (lastBlockNewAppInstalls == blockNewAppInstalls) return
        if (blockNewAppInstalls) {
            bridge.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        } else if (lastBlockNewAppInstalls == true) {
            bridge.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        }
        lastBlockNewAppInstalls = blockNewAppInstalls
    }

    private fun syncLockScreenBrandingLocked(general: GeneralSettings) {
        val ownerName = general.effectiveHardenedOwnerName()
        if (lastOwnerName != ownerName) {
            bridge.setOrganizationName(adminComponent, ownerName)
            lastOwnerName = ownerName
        }
        val lockScreenInfo = general.hardenedLockScreenInfo.trim().takeIf { it.isNotEmpty() }
        if (lockScreenInfo != null && lastLockScreenInfo != lockScreenInfo) {
            bridge.setDeviceOwnerLockScreenInfo(adminComponent, lockScreenInfo)
            lastLockScreenInfo = lockScreenInfo
        } else if (lockScreenInfo == null && lastLockScreenInfo != null) {
            bridge.setDeviceOwnerLockScreenInfo(adminComponent, null)
            lastLockScreenInfo = null
        }
    }

    internal fun applySelfProtectionImmediate(ownerName: String = HARDENED_ORGANIZATION_NAME): Boolean {
        if (!isDeviceOwner()) {
            isSelfProtectionApplied = false
            return false
        }
        applySelfProtectionLocked(ownerName = ownerName)
        return true
    }

    private fun applySelfProtectionLocked(ownerName: String = HARDENED_ORGANIZATION_NAME) {
        bridge.setUninstallBlocked(adminComponent, selfPackageName, true)
        bridge.setUserControlDisabledPackages(adminComponent, listOf(selfPackageName))
        for (restriction in HARDENED_USER_RESTRICTIONS) {
            bridge.addUserRestriction(adminComponent, restriction)
        }
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        bridge.addPersistentPreferredActivity(adminComponent, homeFilter, homeActivityComponent)
        // Android disables the system Backup Service by default on Device Owner devices; explicitly
        // re-enable it so ongoing Google Backup continues working normally in Hardened Mode.
        bridge.setBackupServiceEnabled(adminComponent, true)
        bridge.setOrganizationName(adminComponent, ownerName)
        lastOwnerName = ownerName
        bridge.setShortSupportMessage(adminComponent, HARDENED_SHORT_SUPPORT_MESSAGE)
        isSelfProtectionApplied = true
    }

    override suspend fun reconcileBlockedPackages(
        blockedComponents: Set<ComponentKey>,
        defaultDialerPackage: String,
        guardedComponents: Set<ComponentKey>,
    ): Set<String> = mutex.withLock {
        if (!isDeviceOwner()) {
            isSelfProtectionApplied = false
            invalidateFastPathLocked()
            return emptySet()
        }

        val currentSettings = settingsRepository.getSettings()
        if (!isSelfProtectionApplied) {
            applySelfProtectionLocked(ownerName = currentSettings.general.effectiveHardenedOwnerName())
        }

        val now = nowEpochMillisProvider()

        val effectiveBlockNewAppInstalls =
            currentSettings.general.blockNewAppInstalls && !currentSettings.isBreakGlassActive(now)
        syncInstallAppsRestrictionLocked(effectiveBlockNewAppInstalls)
        syncLockScreenBrandingLocked(currentSettings.general)

        // O(1) fast-path short-circuit when no guarded session lease has expired, no prior partial
        // OS failure needs retrying, and the resolved blocked/guarded component sets are unchanged.
        if (!hasPendingRetry &&
            now < nextLeaseExpiryEpochMillis &&
            lastReconciledBlocked == blockedComponents &&
            lastReconciledGuarded == guardedComponents &&
            lastReconciledDialer == defaultDialerPackage
        ) {
            return lastSuspendedPackages
        }

        val blockedPackageNames = blockedComponents.mapTo(hashSetOf()) { it.packageName }

        // Prune expired guarded leases and any lease for a package that is now explicitly Blocked
        if (activeGuardedLeases.isNotEmpty()) {
            val iterator = activeGuardedLeases.entries.iterator()
            while (iterator.hasNext()) {
                val (pkg, expiresAt) = iterator.next()
                if (now >= expiresAt || pkg in blockedPackageNames) {
                    iterator.remove()
                }
            }
            nextLeaseExpiryEpochMillis = activeGuardedLeases.values.minOrNull() ?: Long.MAX_VALUE
        }

        val settings = settingsRepository.getSettings()
        val previouslySuspended = lastSuspendedPackages + settings.dpcSuspendedPackages

        // Prime Invariant #2 (Universal Dialer Safety Invariant) + Self-Protection:
        // Never suspend the default dialer, Dry Toast itself, or core system packages.
        // Guarded packages are suspended at rest except while holding an active, non-expired session lease.
        val targetPackages = buildSet {
            for (pkg in blockedPackageNames) {
                if (isSuspendablePackage(pkg, defaultDialerPackage)) {
                    add(pkg)
                }
            }
            for (comp in guardedComponents) {
                val pkg = comp.packageName
                if (pkg !in blockedPackageNames &&
                    pkg !in activeGuardedLeases &&
                    isSuspendablePackage(pkg, defaultDialerPackage)
                ) {
                    add(pkg)
                }
            }
        }

        val toSuspend = targetPackages - previouslySuspended
        val toUnsuspend = previouslySuspended - targetPackages

        var failedToSuspend: Set<String> = emptySet()
        val newlySuspended = if (toSuspend.isNotEmpty()) {
            failedToSuspend = bridge.setPackagesSuspended(
                adminComponent,
                toSuspend.sorted().toTypedArray(),
                true,
            ).toSet()
            toSuspend - failedToSuspend
        } else {
            emptySet()
        }

        val failedToUnsuspend = if (toUnsuspend.isNotEmpty()) {
            bridge.setPackagesSuspended(
                adminComponent,
                toUnsuspend.sorted().toTypedArray(),
                false,
            ).toSet()
        } else {
            emptySet()
        }

        val updatedSuspended = (previouslySuspended - toUnsuspend) +
            newlySuspended +
            toUnsuspend.intersect(failedToUnsuspend)

        lastSuspendedPackages = updatedSuspended
        lastReconciledBlocked = blockedComponents
        lastReconciledGuarded = guardedComponents
        lastReconciledDialer = defaultDialerPackage
        hasPendingRetry = failedToSuspend.isNotEmpty() || failedToUnsuspend.isNotEmpty()

        if (updatedSuspended != settings.dpcSuspendedPackages) {
            settingsRepository.updateDpcSuspendedPackages(updatedSuspended)
        }
        updatedSuspended
    }

    override suspend fun grantGuardedSession(
        component: ComponentKey,
        graceDurationMillis: Long,
        defaultDialerPackage: String,
    ): Boolean = mutex.withLock {
        if (!isDeviceOwner()) return false
        val pkg = component.packageName
        if (!isSuspendablePackage(pkg, defaultDialerPackage)) return false

        val now = nowEpochMillisProvider()
        val effectiveGraceMs = maxOf(graceDurationMillis, MIN_LAUNCH_HANDOVER_GRACE_MS)
        activeGuardedLeases[pkg] = now + effectiveGraceMs
        nextLeaseExpiryEpochMillis = activeGuardedLeases.values.minOrNull() ?: Long.MAX_VALUE
        invalidateFastPathLocked()

        val failed = bridge.setPackagesSuspended(adminComponent, arrayOf(pkg), false)
        if (failed.isNotEmpty()) {
            hasPendingRetry = true
            return false
        }

        val currentPersisted = settingsRepository.getSettings().dpcSuspendedPackages
        val updated = (lastSuspendedPackages + currentPersisted) - pkg
        lastSuspendedPackages = updated
        if (updated != currentPersisted) {
            settingsRepository.updateDpcSuspendedPackages(updated)
        }
        true
    }

    override suspend fun revokeGuardedSession(packageName: String): Boolean = mutex.withLock {
        val removed = activeGuardedLeases.remove(packageName) != null
        nextLeaseExpiryEpochMillis = activeGuardedLeases.values.minOrNull() ?: Long.MAX_VALUE
        if (removed) {
            invalidateFastPathLocked()
        }
        removed
    }

    override suspend fun setPackagesSuspended(components: Set<ComponentKey>, suspended: Boolean): Boolean =
        mutex.withLock {
            if (!isDeviceOwner()) return false
            val packages = components
                .map { it.packageName }
                .filter { pkg -> isSuspendablePackage(pkg, defaultDialerPackage = "") }
                .distinct()
                .sorted()
                .toTypedArray()
            if (packages.isEmpty()) return true
            val failed = bridge.setPackagesSuspended(adminComponent, packages, suspended)
            val succeeded = packages.toSet() - failed.toSet()
            val currentPersisted = settingsRepository.getSettings().dpcSuspendedPackages
            val updated = if (suspended) {
                (lastSuspendedPackages + currentPersisted) + succeeded
            } else {
                (lastSuspendedPackages + currentPersisted) - succeeded
            }
            lastSuspendedPackages = updated
            invalidateFastPathLocked()
            if (updated != currentPersisted) {
                settingsRepository.updateDpcSuspendedPackages(updated)
            }
            failed.isEmpty()
        }

    override suspend fun deactivateHardenedMode(): Boolean = mutex.withLock {
        val settings = settingsRepository.getSettings()
        val configuredPackages = buildSet {
            addAll(settings.baseProfile.appAssignments.keys.map { it.packageName })
            addAll(settings.baseProfile.quickActions.map { it.component.packageName })
            for (profile in settings.customProfiles) {
                addAll(profile.appAssignments.keys.map { it.packageName })
            }
        }
        val packagesToUnsuspend = (lastSuspendedPackages + settings.dpcSuspendedPackages + configuredPackages)
            .filter { pkg -> isSuspendablePackage(pkg, defaultDialerPackage = "") }
            .sorted()
            .toTypedArray()

        val wasDeviceOwner = isDeviceOwner()
        if (wasDeviceOwner) {
            if (packagesToUnsuspend.isNotEmpty()) {
                bridge.setPackagesSuspended(adminComponent, packagesToUnsuspend, false)
            }
            for (restriction in HARDENED_USER_RESTRICTIONS) {
                bridge.clearUserRestriction(adminComponent, restriction)
            }
            bridge.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            bridge.clearPackagePersistentPreferredActivities(adminComponent, selfPackageName)
            bridge.setUserControlDisabledPackages(adminComponent, emptyList())
            bridge.setUninstallBlocked(adminComponent, selfPackageName, false)
            bridge.setShortSupportMessage(adminComponent, null)
            bridge.setOrganizationName(adminComponent, null)
            bridge.setDeviceOwnerLockScreenInfo(adminComponent, null)
            bridge.clearDeviceOwnerApp(selfPackageName)
        }

        activeGuardedLeases.clear()
        nextLeaseExpiryEpochMillis = Long.MAX_VALUE
        invalidateFastPathLocked()
        lastSuspendedPackages = emptySet()
        lastBlockNewAppInstalls = null
        lastOwnerName = null
        lastLockScreenInfo = null
        isSelfProtectionApplied = false
        if (settings.dpcSuspendedPackages.isNotEmpty()) {
            settingsRepository.updateDpcSuspendedPackages(emptySet())
        }
        isDeviceOwner()
        wasDeviceOwner
    }

    private fun invalidateFastPathLocked() {
        lastReconciledBlocked = null
        lastReconciledGuarded = null
        lastReconciledDialer = null
    }

    private fun isSuspendablePackage(packageName: String, defaultDialerPackage: String): Boolean =
        packageName.isNotBlank() &&
            packageName != selfPackageName &&
            packageName != defaultDialerPackage &&
            packageName !in PROTECTED_SYSTEM_PACKAGES

    companion object {
        /**
         * Minimum handover window (5 seconds) when a guarded app is launched with a 0-minute grace period
         * so activity transition lifecycle callbacks cannot re-suspend the target package before it starts.
         */
        const val MIN_LAUNCH_HANDOVER_GRACE_MS = 5_000L

        const val HARDENED_ORGANIZATION_NAME = "Dry Toast"
        const val HARDENED_SHORT_SUPPORT_MESSAGE =
            "Hardened Mode is active in Dry Toast (drytoast.app)"

        val HARDENED_USER_RESTRICTIONS = listOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )

        val PROTECTED_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
        )
    }
}
