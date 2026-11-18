package com.garan.threshold.core.model

/**
 * Global configuration settings for Threshold.
 *
 * @property showUnlocks Whether to display unlock count anti-metric on the home surface.
 * @property showRest Whether to display rest duration anti-metric on the home surface.
 * @property showDialerIcon Whether to display the default dialer keypad icon on the launcher screen.
 * @property requireFullTypingOfGuardedApps When true, guarded apps in search results require typing their full name.
 * @property hasSeenFirstRunHint Whether the user has seen or dismissed the first-run onboarding hint.
 * @property requireRebootForSettingsChange When true, all configuration changes are staged in shadow settings
 *   and only take effect after the device is rebooted.
 * @property hasCompletedOnboarding Whether the user has completed the first-run onboarding wizard with Threshold
 *   set as the default home launcher.
 * @property guardedSessionGraceMinutes Duration in minutes (`0`, `1`, `5`, `15`, or `30`) that a Guarded app
 *   remains unsuspended in Hardened (Device Owner) mode after passing a friction gate before being re-suspended
 *   upon returning home or locking the screen.
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
) {
    /**
     * Returns `true` if the user-configurable general settings match [other], ignoring
     * runtime-only flags such as [hasSeenFirstRunHint] and [hasCompletedOnboarding].
     */
    fun hasSameConfigurationAs(other: GeneralSettings): Boolean = showUnlocks == other.showUnlocks &&
        showRest == other.showRest &&
        showDialerIcon == other.showDialerIcon &&
        requireFullTypingOfGuardedApps == other.requireFullTypingOfGuardedApps &&
        requireRebootForSettingsChange == other.requireRebootForSettingsChange &&
        guardedSessionGraceMinutes == other.guardedSessionGraceMinutes

    companion object {
        const val DEFAULT_GUARDED_SESSION_GRACE_MINUTES = 5
        val GUARDED_SESSION_GRACE_OPTIONS_MINUTES = listOf(0, 1, 5, 15, 30)
    }
}
