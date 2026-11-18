package com.garan.threshold.core.port

import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.FrictionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result returned when attempting to launch an application component through Threshold's gatekeeper.
 */
sealed interface LaunchResult {
    /** The launch was approved and dispatched to the platform launcher. */
    data object Launched : LaunchResult

    /** The launch requires completing an intentional friction checkpoint. */
    data class FrictionRequired(val policy: FrictionPolicy) : LaunchResult

    /** The launch was rejected because the application is blocked under the active profile. */
    data object Blocked : LaunchResult
}

/**
 * Outbound port for mediating optional Device Policy Controller (DPC) / Hardened Mode
 * kernel-level package suspension (`setPackagesSuspended`) and anti-tamper self-protection.
 */
interface AppAccessController {

    /**
     * Reactive stream of whether Threshold currently holds active Device Owner (Hardened Mode) privileges.
     */
    val isDeviceOwnerFlow: StateFlow<Boolean>

    /**
     * Determines whether Threshold is currently running with active Device Owner / DPC privileges,
     * refreshing [isDeviceOwnerFlow].
     */
    fun isDeviceOwner(): Boolean

    /**
     * Enforces Hardened Mode self-protection policies (`setUninstallBlocked`,
     * `setUserControlDisabledPackages`, and persistent default Home launcher activity)
     * when running as Device Owner. Returns `true` if applied.
     */
    suspend fun enforceHardenedSelfProtection(): Boolean = false

    /**
     * Reconciles OS-level package suspension (`setPackagesSuspended`) against the current set
     * of [blockedComponents] and resting-state [guardedComponents] resolved by the cascading profile engine.
     *
     * Invariants:
     * 1. No-op when [isDeviceOwner] is `false`.
     * 2. Never suspends [defaultDialerPackage] (Universal Dialer Safety Invariant) or Threshold itself.
     * 3. Suspends newly blocked packages and guarded packages at rest (except guarded packages holding
     *    an active, non-expired session lease that are not currently blocked), and unsuspends any
     *    previously suspended packages that are no longer blocked/guarded or have an active lease.
     *
     * @return The set of package names currently suspended by Threshold after reconciliation.
     */
    suspend fun reconcileBlockedPackages(
        blockedComponents: Set<ComponentKey>,
        defaultDialerPackage: String,
        guardedComponents: Set<ComponentKey> = emptySet(),
    ): Set<String> = emptySet()

    /**
     * Temporarily unsuspends a Guarded [component] prior to launch after passing a friction gate,
     * recording a session lease for [graceDurationMillis].
     * Returns `true` if the package was unsuspended (or if not in Device Owner mode, `false`).
     */
    suspend fun grantGuardedSession(
        component: ComponentKey,
        graceDurationMillis: Long,
        defaultDialerPackage: String = "",
    ): Boolean = false

    /**
     * Explicitly revokes a Guarded session lease for [packageName] and re-suspends it if applicable.
     */
    suspend fun revokeGuardedSession(packageName: String): Boolean = false

    /**
     * Direct DPC-level package suspension (`setPackagesSuspended`).
     * Returns `true` if successfully applied; `false` if unsupported or not in DPC mode.
     */
    suspend fun setPackagesSuspended(components: Set<ComponentKey>, suspended: Boolean): Boolean = false

    /**
     * Cleanly deactivates Hardened Mode (triggered via ADB broadcast from a computer):
     * 1. Unsuspends all packages previously suspended by Threshold.
     * 2. Clears persistent preferred Home activity, user-control restrictions, and uninstall block.
     * 3. Relinquishes Device Owner privileges (`clearDeviceOwnerApp`).
     */
    suspend fun deactivateHardenedMode(): Boolean = false
}

/**
 * Default no-op implementation of [AppAccessController] when DPC is not configured.
 */
object NoOpAppAccessController : AppAccessController {
    private val _isDeviceOwnerFlow = MutableStateFlow(false)
    override val isDeviceOwnerFlow: StateFlow<Boolean> = _isDeviceOwnerFlow.asStateFlow()
    override fun isDeviceOwner(): Boolean = false
}
