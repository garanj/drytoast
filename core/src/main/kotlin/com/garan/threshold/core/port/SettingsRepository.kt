package com.garan.threshold.core.port

import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.CustomProfile
import com.garan.threshold.core.model.GeneralSettings
import com.garan.threshold.core.model.NfcDailyUsage
import com.garan.threshold.core.model.NfcLatchState
import com.garan.threshold.core.model.SavedTrigger
import com.garan.threshold.core.model.ThresholdSettings
import kotlinx.coroutines.flow.Flow

/**
 * Port for observing and persisting user settings, base app assignments, quick actions,
 * reusable triggers, and custom profiles.
 */
interface SettingsRepository {

    /**
     * Emits the latest [ThresholdSettings] whenever any preference or assignment updates.
     */
    fun observeSettings(): Flow<ThresholdSettings>

    /**
     * Reads the current snapshot of [ThresholdSettings].
     */
    suspend fun getSettings(): ThresholdSettings

    /**
     * Updates the [GeneralSettings].
     */
    suspend fun updateGeneralSettings(general: GeneralSettings)

    /**
     * Updates the [BaseProfileSettings] (friction preferences, quick actions, and app assignments).
     */
    suspend fun updateBaseProfile(baseProfile: BaseProfileSettings)

    /**
     * Sets whether the first-run onboarding hint has been displayed.
     */
    suspend fun setHasSeenFirstRunHint(hasSeen: Boolean)

    /**
     * Sets whether the first-run onboarding wizard has been completed.
     */
    suspend fun setHasCompletedOnboarding(completed: Boolean) {}

    /**
     * Removes specified app components from assignments and quick action slots (e.g. upon uninstallation).
     */
    suspend fun removeApps(keys: Set<ComponentKey>)

    /**
     * Updates the full ordered list of [CustomProfile]s.
     */
    suspend fun updateCustomProfiles(profiles: List<CustomProfile>)

    /**
     * Adds or updates a single [CustomProfile].
     */
    suspend fun saveCustomProfile(profile: CustomProfile)

    /**
     * Deletes a [CustomProfile] by its ID.
     */
    suspend fun deleteCustomProfile(profileId: String)

    /**
     * Adds or updates a reusable [SavedTrigger].
     */
    suspend fun saveTrigger(trigger: SavedTrigger) {}

    /**
     * Deletes a reusable [SavedTrigger] by its ID, stripping it from all profile activation rules
     * and disabling any profile left with zero triggers.
     */
    suspend fun deleteTrigger(triggerId: String) {}

    /**
     * Persists the active NFC trigger latch states ([NfcLatchState] keyed by [SavedTrigger.id])
     * and optional daily usage counters ([NfcDailyUsage] keyed by [SavedTrigger.id]).
     */
    suspend fun updateNfcLatches(latches: Map<String, NfcLatchState>, dailyUsage: Map<String, NfcDailyUsage>? = null) {}

    /**
     * Discards any staged shadow settings awaiting a reboot, reverting the editable configuration
     * back to the currently active live settings.
     */
    suspend fun discardPendingChanges() {}

    /**
     * Promotes any staged shadow settings to the active live settings upon device reboot.
     * Returns `true` if pending shadow settings were promoted.
     */
    suspend fun promotePendingSettingsOnBoot(): Boolean = false

    /**
     * Locks or unlocks all user configuration changes (controlled via ADB broadcast).
     * While locked, all user configuration mutations to both live and shadow settings are rejected.
     */
    suspend fun setSettingsLocked(locked: Boolean) {}

    /**
     * Persists the set of package names currently suspended at the OS level by Threshold's
     * Hardened Mode (Device Owner) controller so they can be unsuspended across process restarts.
     */
    suspend fun updateDpcSuspendedPackages(packages: Set<String>) {}
}
