package com.garan.drytoast.core.model

/**
 * Global configuration settings for Dry Toast.
 *
 * @property showUnlocks Whether to display unlock count anti-metric on the home surface.
 * @property showRest Whether to display rest duration anti-metric on the home surface.
 * @property showDialerIcon Whether to display the default dialer keypad icon on the launcher screen.
 * @property requireFullTypingOfGuardedApps When true, guarded apps in search results require typing their full name.
 * @property hasSeenFirstRunHint Whether the user has seen or dismissed the first-run onboarding hint.
 * @property requireRebootForSettingsChange When true, all configuration changes are staged in shadow settings
 *   and only take effect after the device is rebooted.
 * @property hasCompletedOnboarding Whether the user has completed the first-run onboarding wizard with Dry Toast
 *   set as the default home launcher.
 * @property guardedSessionGraceMinutes Duration in minutes (`0`, `1`, `5`, `15`, or `30`) that a Guarded app
 *   remains unsuspended in Hardened (Device Owner) mode after passing a friction gate before being re-suspended
 *   upon returning home or locking the screen.
 * @property blockNewAppInstalls When true in Hardened (Device Owner) mode, enforces
 *   `UserManager.DISALLOW_INSTALL_APPS` to block installing new apps from Google Play or other sources.
 * @property hardenedOwnerName Custom owner/organization name displayed on the lock screen disclosure
 *   (`"This device belongs to <name>"`) in Hardened Mode. Defaults to `"Dry Toast"`.
 * @property hardenedLockScreenInfo Optional additional line displayed on the Android lock screen
 *   via `DevicePolicyManager.setDeviceOwnerLockScreenInfo` in Hardened Mode. Blank clears the line.
 */
data class GeneralSettings(
    val showUnlocks: Boolean = true,
    val showRest: Boolean = true,
    val showDialerIcon: Boolean = true,
    val requireFullTypingOfGuardedApps: Boolean = false,
    val hasSeenFirstRunHint: Boolean = false,
    val requireRebootForSettingsChange: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val guardedSessionGraceMinutes: Int = DEFAULT_GUARDED_SESSION_GRACE_MINUTES,
    val blockNewAppInstalls: Boolean = false,
    val hardenedOwnerName: String = DEFAULT_HARDENED_OWNER_NAME,
    val hardenedLockScreenInfo: String = "",
) {
    /**
     * Returns the trimmed owner name for Android's Device Owner lock screen disclosure,
     * falling back to [DEFAULT_HARDENED_OWNER_NAME] if blank.
     */
    fun effectiveHardenedOwnerName(): String = hardenedOwnerName.trim().ifEmpty { DEFAULT_HARDENED_OWNER_NAME }

    /**
     * Returns `true` if the user-configurable general settings match [other], ignoring
     * runtime-only flags such as [hasSeenFirstRunHint] and [hasCompletedOnboarding].
     */
    fun hasSameConfigurationAs(other: GeneralSettings): Boolean = showUnlocks == other.showUnlocks &&
        showRest == other.showRest &&
        showDialerIcon == other.showDialerIcon &&
        requireFullTypingOfGuardedApps == other.requireFullTypingOfGuardedApps &&
        requireRebootForSettingsChange == other.requireRebootForSettingsChange &&
        guardedSessionGraceMinutes == other.guardedSessionGraceMinutes &&
        blockNewAppInstalls == other.blockNewAppInstalls &&
        effectiveHardenedOwnerName() == other.effectiveHardenedOwnerName() &&
        hardenedLockScreenInfo.trim() == other.hardenedLockScreenInfo.trim()

    companion object {
        const val DEFAULT_GUARDED_SESSION_GRACE_MINUTES = 5
        const val DEFAULT_HARDENED_OWNER_NAME = "Dry Toast"
        val GUARDED_SESSION_GRACE_OPTIONS_MINUTES = listOf(0, 1, 5, 15, 30)
    }
}
