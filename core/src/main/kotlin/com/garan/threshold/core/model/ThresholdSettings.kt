package com.garan.threshold.core.model

/**
 * Snapshot of staged user configuration changes awaiting a device reboot when
 * [GeneralSettings.requireRebootForSettingsChange] is active on the live settings.
 */
data class StagedSettingsSnapshot(
    val general: GeneralSettings = GeneralSettings(),
    val baseProfile: BaseProfileSettings = BaseProfileSettings(),
    val customProfiles: List<CustomProfile> = emptyList(),
    val savedTriggers: List<SavedTrigger> = emptyList(),
)

/**
 * Top-level application settings encapsulating general preferences, base profile configurations,
 * reusable triggers, and user-defined custom profiles.
 *
 * When [GeneralSettings.requireRebootForSettingsChange] is enabled on the active live settings,
 * user configuration edits are staged in [stagedShadow] until the next device reboot, while
 * runtime enforcement continues reading [general], [baseProfile], [customProfiles], and [savedTriggers].
 */
data class ThresholdSettings(
    val general: GeneralSettings = GeneralSettings(),
    val baseProfile: BaseProfileSettings = BaseProfileSettings(),
    val customProfiles: List<CustomProfile> = emptyList(),
    val savedTriggers: List<SavedTrigger> = emptyList(),
    val nfcLatches: Map<String, NfcLatchState> = emptyMap(),
    val nfcDailyUsage: Map<String, NfcDailyUsage> = emptyMap(),
    val stagedShadow: StagedSettingsSnapshot? = null,
) {
    val savedTriggersById: Map<String, SavedTrigger> =
        savedTriggers.associateBy { it.id }

    /**
     * Extracts the active live configuration as a [StagedSettingsSnapshot].
     */
    fun toStagedSnapshot(): StagedSettingsSnapshot = StagedSettingsSnapshot(
        general = general,
        baseProfile = baseProfile,
        customProfiles = customProfiles,
        savedTriggers = savedTriggers,
    )

    /**
     * Returns `true` if [snapshot] has the exact same user-configurable settings as the active live configuration.
     */
    fun hasSameConfigurationAs(snapshot: StagedSettingsSnapshot): Boolean =
        general.hasSameConfigurationAs(snapshot.general) &&
            baseProfile == snapshot.baseProfile &&
            customProfiles == snapshot.customProfiles &&
            savedTriggers == snapshot.savedTriggers

    /**
     * `true` when [stagedShadow] is present and differs from the active live configuration.
     */
    val hasPendingRebootChanges: Boolean =
        stagedShadow != null && !hasSameConfigurationAs(stagedShadow)

    /**
     * The configuration snapshot displayed and edited in the Settings UI
     * ([stagedShadow] when pending changes exist, otherwise the active live configuration).
     */
    val editableSnapshot: StagedSettingsSnapshot
        get() = if (hasPendingRebootChanges && stagedShadow != null) stagedShadow else toStagedSnapshot()
}
