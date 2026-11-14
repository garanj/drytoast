package com.garan.threshold.ui.settings

import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.GeneralSettings
import com.garan.threshold.core.model.NfcDailyUsage
import com.garan.threshold.core.model.NfcLatchState
import com.garan.threshold.core.model.ProfileTrigger
import com.garan.threshold.core.model.SavedTrigger
import com.garan.threshold.core.model.StagedSettingsSnapshot
import com.garan.threshold.core.model.ThresholdSettings
import com.garan.threshold.core.port.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeSettingsRepository(initialSettings: ThresholdSettings = ThresholdSettings()) : SettingsRepository {

    private val _settings = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<ThresholdSettings> = _settings.asStateFlow()

    override suspend fun getSettings(): ThresholdSettings = _settings.value

    private fun applyLiveSnapshot(current: ThresholdSettings, updated: StagedSettingsSnapshot): ThresholdSettings {
        val updatedTriggersById = updated.savedTriggers.associateBy { it.id }
        val reconciledLatches = if (current.nfcLatches.isNotEmpty()) {
            val map = LinkedHashMap<String, NfcLatchState>()
            for ((triggerId, existingLatch) in current.nfcLatches) {
                val nfcSpec = (updatedTriggersById[triggerId]?.trigger as? ProfileTrigger.Nfc)?.nfc ?: continue
                val reconciled = existingLatch.reconcileWith(nfcSpec, nowEpochMillis = 0L) ?: continue
                map[triggerId] = reconciled
            }
            map
        } else {
            current.nfcLatches
        }
        val prunedUsage = if (current.nfcDailyUsage.isNotEmpty()) {
            current.nfcDailyUsage.filterKeys { it in updatedTriggersById }
        } else {
            current.nfcDailyUsage
        }
        return current.copy(
            general = updated.general.copy(hasSeenFirstRunHint = current.general.hasSeenFirstRunHint),
            baseProfile = updated.baseProfile,
            customProfiles = updated.customProfiles,
            savedTriggers = updated.savedTriggers,
            nfcLatches = reconciledLatches,
            nfcDailyUsage = prunedUsage,
            stagedShadow = null,
        )
    }

    private fun mutateConfiguration(transform: (StagedSettingsSnapshot) -> StagedSettingsSnapshot) {
        _settings.update { current ->
            if (!current.general.requireRebootForSettingsChange) {
                val updated = transform(current.toStagedSnapshot())
                applyLiveSnapshot(current, updated)
            } else {
                val updatedShadow = transform(current.editableSnapshot)
                if (current.hasSameConfigurationAs(updatedShadow)) {
                    current.copy(stagedShadow = null)
                } else {
                    current.copy(stagedShadow = updatedShadow)
                }
            }
        }
    }

    override suspend fun updateGeneralSettings(general: GeneralSettings) {
        _settings.update { it.copy(general = it.general.copy(hasSeenFirstRunHint = general.hasSeenFirstRunHint)) }
        mutateConfiguration { snapshot ->
            snapshot.copy(general = general)
        }
    }

    override suspend fun updateBaseProfile(baseProfile: BaseProfileSettings) {
        mutateConfiguration { snapshot ->
            snapshot.copy(baseProfile = baseProfile)
        }
    }

    override suspend fun setHasSeenFirstRunHint(hasSeen: Boolean) {
        _settings.update { it.copy(general = it.general.copy(hasSeenFirstRunHint = hasSeen)) }
    }

    override suspend fun removeApps(keys: Set<ComponentKey>) {
        _settings.update { current ->
            val pkgNames = keys.map { it.packageName }.toSet()
            val newQuick = current.baseProfile.quickActions.filter { it !in keys && it.packageName !in pkgNames }
            val newAssignments = current.baseProfile.appAssignments.filter { (comp, _) ->
                comp !in keys && comp.packageName !in pkgNames
            }
            val newProfiles = current.customProfiles.map { profile ->
                val pruned = profile.appAssignments.filter { (comp, _) ->
                    comp !in keys && comp.packageName !in pkgNames
                }
                profile.copy(
                    appAssignments = pruned,
                    enabled = if (pruned.isEmpty() && profile.appAssignments.isNotEmpty()) false else profile.enabled,
                )
            }
            val prunedLive = current.copy(
                baseProfile = current.baseProfile.copy(
                    quickActions = newQuick,
                    appAssignments = newAssignments,
                ),
                customProfiles = newProfiles,
            )
            val existingShadow = current.stagedShadow
            if (existingShadow == null) {
                prunedLive
            } else {
                val prunedShadow = existingShadow.copy(
                    baseProfile = existingShadow.baseProfile.copy(
                        quickActions = existingShadow.baseProfile.quickActions.filter {
                            it !in keys && it.packageName !in pkgNames
                        },
                        appAssignments = existingShadow.baseProfile.appAssignments.filter { (comp, _) ->
                            comp !in keys && comp.packageName !in pkgNames
                        },
                    ),
                    customProfiles = existingShadow.customProfiles.map { profile ->
                        val pruned = profile.appAssignments.filter { (comp, _) ->
                            comp !in keys && comp.packageName !in pkgNames
                        }
                        profile.copy(
                            appAssignments = pruned,
                            enabled = if (pruned.isEmpty() &&
                                profile.appAssignments.isNotEmpty()
                            ) {
                                false
                            } else {
                                profile.enabled
                            },
                        )
                    },
                )
                if (prunedLive.hasSameConfigurationAs(prunedShadow)) {
                    prunedLive.copy(stagedShadow = null)
                } else {
                    prunedLive.copy(stagedShadow = prunedShadow)
                }
            }
        }
    }

    override suspend fun updateCustomProfiles(profiles: List<com.garan.threshold.core.model.CustomProfile>) {
        mutateConfiguration { snapshot ->
            snapshot.copy(customProfiles = profiles)
        }
    }

    override suspend fun saveCustomProfile(profile: com.garan.threshold.core.model.CustomProfile) {
        mutateConfiguration { current ->
            val existingIndex = current.customProfiles.indexOfFirst { it.id == profile.id }
            val updated = if (existingIndex >= 0) {
                current.customProfiles.toMutableList().apply { set(existingIndex, profile) }
            } else {
                current.customProfiles + profile
            }
            current.copy(customProfiles = updated)
        }
    }

    override suspend fun deleteCustomProfile(profileId: String) {
        mutateConfiguration { current ->
            current.copy(customProfiles = current.customProfiles.filter { it.id != profileId })
        }
    }

    override suspend fun saveTrigger(trigger: SavedTrigger) {
        mutateConfiguration { current ->
            val existingIndex = current.savedTriggers.indexOfFirst { it.id == trigger.id }
            val updated = if (existingIndex >= 0) {
                current.savedTriggers.toMutableList().apply { set(existingIndex, trigger) }
            } else {
                current.savedTriggers + trigger
            }
            current.copy(savedTriggers = updated)
        }
    }

    override suspend fun deleteTrigger(triggerId: String) {
        mutateConfiguration { current ->
            val updatedTriggers = current.savedTriggers.filter { it.id != triggerId }
            val updatedProfiles = current.customProfiles.map { profile ->
                if (triggerId in profile.referencedTriggerIds) {
                    profile.withoutTrigger(triggerId)
                } else {
                    profile
                }
            }
            current.copy(
                savedTriggers = updatedTriggers,
                customProfiles = updatedProfiles,
            )
        }
    }

    override suspend fun discardPendingChanges() {
        _settings.update { it.copy(stagedShadow = null) }
    }

    override suspend fun promotePendingSettingsOnBoot(): Boolean {
        var promoted = false
        _settings.update { current ->
            val shadow = current.stagedShadow
            if (shadow != null) {
                promoted = true
                applyLiveSnapshot(current, shadow)
            } else {
                current
            }
        }
        return promoted
    }

    override suspend fun updateNfcLatches(
        latches: Map<String, NfcLatchState>,
        dailyUsage: Map<String, NfcDailyUsage>?,
    ) {
        _settings.update {
            it.copy(
                nfcLatches = latches,
                nfcDailyUsage = dailyUsage ?: it.nfcDailyUsage,
            )
        }
    }
}
