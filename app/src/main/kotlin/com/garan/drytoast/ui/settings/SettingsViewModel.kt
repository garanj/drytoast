package com.garan.drytoast.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garan.drytoast.core.backup.ConfigBackupCodec
import com.garan.drytoast.core.backup.ConfigImportError
import com.garan.drytoast.core.backup.ConfigImportResult
import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.BaseProfileSettings
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.InstalledApp
import com.garan.drytoast.core.model.SavedTrigger
import com.garan.drytoast.core.port.AppAccessController
import com.garan.drytoast.core.port.NoOpAppAccessController
import com.garan.drytoast.core.port.PackageIndexSource
import com.garan.drytoast.core.port.SettingsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface ConfigImportOutcome {
    data class Success(val stagedForReboot: Boolean) : ConfigImportOutcome
    data object PassphraseRequired : ConfigImportOutcome
    data object SettingsLocked : ConfigImportOutcome
    data class Error(val reason: ConfigImportError, val detail: String) : ConfigImportOutcome
}

@Immutable
data class SettingsUiState(
    val general: GeneralSettings = GeneralSettings(),
    val baseProfile: BaseProfileSettings = BaseProfileSettings(),
    val customProfiles: ImmutableList<com.garan.drytoast.core.model.CustomProfile> = persistentListOf(),
    val savedTriggers: ImmutableList<SavedTrigger> = persistentListOf(),
    val availableApps: ImmutableList<InstalledApp> = persistentListOf(),
    val isLoading: Boolean = true,
    val isSettingsLoaded: Boolean = false,
    val hasPendingRebootChanges: Boolean = false,
    val isRequireRebootActiveOnLive: Boolean = false,
    val isSettingsLocked: Boolean = false,
    val showRoleRequiredWarning: Boolean = false,
    val isHardenedModeActive: Boolean = false,
    val breakGlassUntilEpochMillis: Long? = null,
    val showImportPassphraseSheet: Boolean = false,
    val hasImportPassphraseError: Boolean = false,
    val isImportWorking: Boolean = false,
)

class SettingsViewModel(
    private val packageIndexSource: PackageIndexSource,
    private val settingsRepository: SettingsRepository,
    private val dialerPackageResolver: () -> String,
    private val appAccessController: AppAccessController = NoOpAppAccessController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val configBackupCodec: ConfigBackupCodec = ConfigBackupCodec(),
    private val onConfigurationImported: suspend () -> Unit = {},
) : ViewModel() {

    private sealed interface PendingExportRequest {
        data object Plaintext : PendingExportRequest
        data class Encrypted(val passphrase: String) : PendingExportRequest
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val isIndexLoaded = AtomicBoolean(false)
    private val pendingExportRequest = AtomicReference<PendingExportRequest?>(null)
    private val pendingEncryptedImportJson = AtomicReference<String?>(null)

    @Volatile
    private var loadJob: Job? = null

    @Volatile
    private var onboardingCompletedOverride: Boolean? = null

    init {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.observeSettings().collect { settings ->
                val editable = settings.editableSnapshot
                val overrideVal = onboardingCompletedOverride
                if (overrideVal != null && editable.general.hasCompletedOnboarding == overrideVal) {
                    onboardingCompletedOverride = null
                }
                val resolvedGeneral = onboardingCompletedOverride?.let {
                    editable.general.copy(hasCompletedOnboarding = it)
                } ?: editable.general
                _uiState.update { current ->
                    current.copy(
                        general = resolvedGeneral,
                        baseProfile = editable.baseProfile,
                        customProfiles = editable.customProfiles.toImmutableList(),
                        savedTriggers = editable.savedTriggers.toImmutableList(),
                        isSettingsLoaded = true,
                        hasPendingRebootChanges = settings.hasPendingRebootChanges,
                        isRequireRebootActiveOnLive = settings.general.requireRebootForSettingsChange,
                        isSettingsLocked = settings.isSettingsLocked,
                        breakGlassUntilEpochMillis = settings.breakGlassUntilEpochMillis,
                    )
                }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            appAccessController.isDeviceOwnerFlow.collect { active ->
                _uiState.update { current ->
                    current.copy(isHardenedModeActive = active)
                }
            }
        }
    }

    fun refreshHardenedModeStatus() {
        appAccessController.isDeviceOwner()
    }

    /**
     * Lazily resolves installed applications when entering Settings or Configuration screens.
     * Preserves low ambient memory footprint (< 25 MB PSS) on the home canvas.
     */
    fun ensureLoaded() {
        if (!isIndexLoaded.compareAndSet(false, true)) return
        loadJob = viewModelScope.launch(ioDispatcher) {
            val dialerPkg = dialerPackageResolver()
            val apps = packageIndexSource.getInstalledApps()
                .filterNot { it.component.packageName == dialerPkg }
                .sortedBy { it.label.lowercase() }

            if (isIndexLoaded.get()) {
                _uiState.update {
                    it.copy(
                        availableApps = apps.toImmutableList(),
                        isLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Releases the resolved [InstalledApp] list when returning to the Home canvas so Settings
     * metadata does not remain resident in memory while idle.
     */
    fun releaseLoadedApps() {
        if (!isIndexLoaded.compareAndSet(true, false)) return
        loadJob?.cancel()
        loadJob = null
        _uiState.update {
            it.copy(
                availableApps = persistentListOf(),
                isLoading = true,
            )
        }
    }

    fun updateShowUnlocks(show: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(showUnlocks = show))
        }
    }

    fun updateShowRest(show: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(showRest = show))
        }
    }

    fun updateShowDialerIcon(show: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(showDialerIcon = show))
        }
    }

    fun updateRequireFullTyping(requireFull: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(requireFullTypingOfGuardedApps = requireFull))
        }
    }

    fun updateRequireRebootForSettingsChange(requireReboot: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(requireRebootForSettingsChange = requireReboot))
        }
    }

    fun updateGuardedSessionGraceMinutes(minutes: Int) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(guardedSessionGraceMinutes = minutes))
        }
    }

    fun updateBlockNewAppInstalls(block: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(blockNewAppInstalls = block))
            appAccessController.enforceHardenedSelfProtection()
        }
    }

    fun updateHardenedLockScreenText(ownerName: String, lockScreenInfo: String) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(
                current.copy(
                    hardenedOwnerName = ownerName.trim().ifEmpty {
                        com.garan.drytoast.core.model.GeneralSettings.DEFAULT_HARDENED_OWNER_NAME
                    },
                    hardenedLockScreenInfo = lockScreenInfo.trim(),
                ),
            )
            appAccessController.enforceHardenedSelfProtection()
        }
    }

    fun updateBreakGlassTagUid(uid: String?) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            val normalized = uid?.let(com.garan.drytoast.core.model.NfcTagEntry::normalizeUid)?.takeIf {
                it.isNotBlank()
            }
            settingsRepository.updateGeneralSettings(current.copy(breakGlassTagUid = normalized))
        }
    }

    fun updateBreakGlassDurationMinutes(minutes: Int) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.general
            settingsRepository.updateGeneralSettings(current.copy(breakGlassDurationMinutes = minutes))
        }
    }

    fun endBreakGlassEarly() {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.updateBreakGlassUntil(null)
            com.garan.drytoast.schedule.ScheduleTransitionNotifier.notifyTransition()
        }
    }

    fun updateCustomProfileDefaultRule(
        profileId: String,
        defaultRule: com.garan.drytoast.core.model.ProfileDefaultRule,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val editable = settingsRepository.getSettings().editableSnapshot
            val existing = editable.customProfiles.firstOrNull { it.id == profileId } ?: return@launch
            if (existing.defaultRule == defaultRule) return@launch
            settingsRepository.saveCustomProfile(existing.copy(defaultRule = defaultRule))
        }
    }

    fun discardPendingChanges() {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.discardPendingChanges()
        }
    }

    fun setShowRoleRequiredWarning(show: Boolean) {
        if (_uiState.value.showRoleRequiredWarning == show) return
        _uiState.update { current ->
            current.copy(showRoleRequiredWarning = show)
        }
    }

    fun completeOnboarding() {
        val currentState = _uiState.value
        if (currentState.isSettingsLoaded &&
            currentState.general.hasCompletedOnboarding &&
            onboardingCompletedOverride == null
        ) {
            return
        }
        onboardingCompletedOverride = true
        _uiState.update { current ->
            current.copy(
                general = current.general.copy(hasCompletedOnboarding = true),
                showRoleRequiredWarning = false,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.setHasCompletedOnboarding(true)
        }
    }

    fun resetOnboarding() {
        val currentState = _uiState.value
        if (currentState.isSettingsLoaded &&
            !currentState.general.hasCompletedOnboarding &&
            onboardingCompletedOverride == null
        ) {
            return
        }
        onboardingCompletedOverride = false
        _uiState.update { current ->
            current.copy(general = current.general.copy(hasCompletedOnboarding = false))
        }
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.setHasCompletedOnboarding(false)
        }
    }

    fun updateDefaultFriction(friction: FrictionChoice) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.baseProfile
            settingsRepository.updateBaseProfile(current.copy(defaultFriction = friction))
        }
    }

    fun addQuickAction(app: InstalledApp) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.baseProfile
            if (current.quickActions.size < BaseProfileSettings.MAX_QUICK_ACTIONS &&
                current.quickActions.none { it.component == app.component }
            ) {
                settingsRepository.updateBaseProfile(
                    current.copy(quickActions = current.quickActions + app),
                )
            }
        }
    }

    fun removeQuickAction(component: ComponentKey) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.baseProfile
            settingsRepository.updateBaseProfile(
                current.copy(quickActions = current.quickActions.filterNot { it.component == component }),
            )
        }
    }

    fun reorderQuickActions(reordered: List<InstalledApp>) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.baseProfile
            if (reordered.size <= BaseProfileSettings.MAX_QUICK_ACTIONS) {
                settingsRepository.updateBaseProfile(current.copy(quickActions = reordered))
            }
        }
    }

    fun saveAppAssignments(assignments: Map<ComponentKey, AppAssignment>) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.baseProfile
            settingsRepository.updateBaseProfile(current.copy(appAssignments = assignments))
        }
    }

    fun saveCustomProfile(profile: com.garan.drytoast.core.model.CustomProfile) {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.saveCustomProfile(profile)
        }
    }

    fun deleteCustomProfile(profileId: String) {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.deleteCustomProfile(profileId)
        }
    }

    fun reorderCustomProfiles(profiles: List<com.garan.drytoast.core.model.CustomProfile>) {
        viewModelScope.launch(ioDispatcher) {
            val updated = profiles.mapIndexed { index, profile ->
                profile.copy(priority = index)
            }
            settingsRepository.updateCustomProfiles(updated)
        }
    }

    fun saveCustomProfileAppAssignments(profileId: String, assignments: Map<ComponentKey, AppAssignment>) {
        viewModelScope.launch(ioDispatcher) {
            val current = settingsRepository.getSettings().editableSnapshot.customProfiles
            val target = current.firstOrNull { it.id == profileId } ?: return@launch
            val updated = target.copy(appAssignments = assignments)
            settingsRepository.saveCustomProfile(updated)
        }
    }

    fun saveTrigger(trigger: SavedTrigger) {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.saveTrigger(trigger)
        }
    }

    fun deleteTrigger(triggerId: String) {
        viewModelScope.launch(ioDispatcher) {
            settingsRepository.deleteTrigger(triggerId)
        }
    }

    /**
     * Stages a pending SAF export request inside the [ViewModel] so `densityDpi` or `fontScale`
     * Activity recreation while Android's system file picker is open never drops an encryption
     * passphrase and silently writes plaintext.
     */
    fun stageExportRequest(passphrase: String?) {
        val normalized = passphrase?.takeIf { it.isNotEmpty() }
        pendingExportRequest.set(
            if (normalized != null) {
                PendingExportRequest.Encrypted(normalized)
            } else {
                PendingExportRequest.Plaintext
            },
        )
    }

    fun cancelPendingExportRequest() {
        pendingExportRequest.set(null)
    }

    /**
     * Atomically consumes the staged export request and encodes the configuration JSON, or returns
     * `null` (fail-closed) if no export request is staged (e.g., after background process death).
     */
    suspend fun consumePendingExportJson(): String? {
        val request = pendingExportRequest.getAndSet(null) ?: return null
        return when (request) {
            is PendingExportRequest.Plaintext -> exportConfigurationJson(passphrase = null)
            is PendingExportRequest.Encrypted -> exportConfigurationJson(passphrase = request.passphrase)
        }
    }

    /**
     * Serializes the currently editable configuration snapshot to JSON (encrypted when
     * [passphrase] is non-empty, plaintext otherwise).
     */
    suspend fun exportConfigurationJson(passphrase: String?): String = withContext(ioDispatcher) {
        val snapshot = settingsRepository.getSettings().editableSnapshot
        configBackupCodec.encode(snapshot, passphrase?.takeIf { it.isNotEmpty() })
    }

    /**
     * Evaluates a newly picked backup JSON payload; if encrypted, retains the payload in the
     * [ViewModel] across Activity recreation and opens the passphrase bottom sheet.
     */
    suspend fun handlePickedImportJson(rawJson: String): ConfigImportOutcome {
        val outcome = importConfigurationJson(rawJson, passphrase = null)
        if (outcome is ConfigImportOutcome.PassphraseRequired) {
            pendingEncryptedImportJson.set(rawJson)
            _uiState.update { current ->
                current.copy(
                    showImportPassphraseSheet = true,
                    hasImportPassphraseError = false,
                    isImportWorking = false,
                )
            }
        } else {
            pendingEncryptedImportJson.set(null)
            _uiState.update { current ->
                current.copy(
                    showImportPassphraseSheet = false,
                    hasImportPassphraseError = false,
                    isImportWorking = false,
                )
            }
        }
        return outcome
    }

    /**
     * Decrypts and imports the pending encrypted JSON payload stored in this [ViewModel],
     * returning `null` if no encrypted payload is awaiting a passphrase.
     */
    suspend fun submitPendingImportPassphrase(passphrase: String): ConfigImportOutcome? {
        val rawJson = pendingEncryptedImportJson.get() ?: return null
        _uiState.update { current ->
            current.copy(isImportWorking = true, hasImportPassphraseError = false)
        }
        return try {
            val outcome = importConfigurationJson(rawJson, passphrase)
            when (outcome) {
                is ConfigImportOutcome.PassphraseRequired -> {
                    _uiState.update { current ->
                        current.copy(
                            showImportPassphraseSheet = true,
                            hasImportPassphraseError = true,
                            isImportWorking = false,
                        )
                    }
                }
                is ConfigImportOutcome.Error -> {
                    if (outcome.reason == ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED) {
                        _uiState.update { current ->
                            current.copy(
                                showImportPassphraseSheet = true,
                                hasImportPassphraseError = true,
                                isImportWorking = false,
                            )
                        }
                    } else {
                        pendingEncryptedImportJson.set(null)
                        _uiState.update { current ->
                            current.copy(
                                showImportPassphraseSheet = false,
                                hasImportPassphraseError = false,
                                isImportWorking = false,
                            )
                        }
                    }
                }
                is ConfigImportOutcome.Success,
                is ConfigImportOutcome.SettingsLocked,
                -> {
                    pendingEncryptedImportJson.set(null)
                    _uiState.update { current ->
                        current.copy(
                            showImportPassphraseSheet = false,
                            hasImportPassphraseError = false,
                            isImportWorking = false,
                        )
                    }
                }
            }
            outcome
        } catch (t: Throwable) {
            _uiState.update { current -> current.copy(isImportWorking = false) }
            throw t
        }
    }

    fun dismissImportPassphraseSheet() {
        pendingEncryptedImportJson.set(null)
        _uiState.update { current ->
            current.copy(
                showImportPassphraseSheet = false,
                hasImportPassphraseError = false,
                isImportWorking = false,
            )
        }
    }

    /**
     * Decodes and imports a Dry Toast JSON backup string.
     *
     * Respects Settings Lock and Reboot-to-Apply. Invokes [onConfigurationImported] on success
     * so schedule alarms and Hardened Mode suspension reconcile immediately.
     */
    suspend fun importConfigurationJson(rawJson: String, passphrase: String? = null): ConfigImportOutcome =
        withContext(ioDispatcher) {
            val current = settingsRepository.getSettings()
            if (current.isSettingsLocked) {
                return@withContext ConfigImportOutcome.SettingsLocked
            }
            when (val decoded = configBackupCodec.decode(rawJson, passphrase)) {
                is ConfigImportResult.PassphraseRequired -> ConfigImportOutcome.PassphraseRequired
                is ConfigImportResult.Error -> ConfigImportOutcome.Error(decoded.reason, decoded.detail)
                is ConfigImportResult.Success -> {
                    val applied = settingsRepository.importConfiguration(decoded.snapshot)
                    if (!applied) {
                        ConfigImportOutcome.SettingsLocked
                    } else {
                        val updated = settingsRepository.getSettings()
                        onConfigurationImported()
                        ConfigImportOutcome.Success(stagedForReboot = updated.hasPendingRebootChanges)
                    }
                }
            }
        }
}
