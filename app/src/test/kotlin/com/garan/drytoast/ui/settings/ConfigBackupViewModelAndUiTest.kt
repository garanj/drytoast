package com.garan.drytoast.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.garan.drytoast.core.backup.ConfigBackupCodec
import com.garan.drytoast.core.backup.ConfigImportError
import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.BaseProfileSettings
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.core.model.DryToastSettings
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.InstalledApp
import com.garan.drytoast.core.model.ProfileDefaultRule
import com.garan.drytoast.core.port.PackageChangeEvent
import com.garan.drytoast.core.port.PackageIndexSource
import com.garan.drytoast.ui.theme.DryToastTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConfigBackupViewModelAndUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fastCodec = ConfigBackupCodec(pbkdf2Iterations = 1_000)

    private val emptyPackageSource = object : PackageIndexSource {
        override suspend fun getInstalledApps(): List<InstalledApp> = emptyList()
        override fun observePackageChanges(): Flow<PackageChangeEvent> = emptyFlow()
    }

    // -------------------------------------------------------------------------
    // SettingsViewModel backup/restore tests
    // -------------------------------------------------------------------------

    @Test
    fun viewModel_exportAndImport_plaintextAndEncryptedRoundTripsAndNotifiesCallback() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val social = ComponentKey("com.social.app", "com.social.app.Main")
        val sourceRepo = FakeSettingsRepository(
            DryToastSettings(
                general = GeneralSettings(showUnlocks = false, breakGlassTagUid = "04:AA:BB:CC"),
                baseProfile = BaseProfileSettings(
                    defaultFriction = FrictionChoice.CARET_TRACING,
                    appAssignments = mapOf(social to AppAssignment.Blocked),
                ),
                customProfiles = listOf(
                    CustomProfile(
                        id = "deep",
                        name = "Deep Work",
                        enabled = true,
                        priority = 0,
                        trigger = null,
                        defaultRule = ProfileDefaultRule.BLOCK_ALL,
                    ),
                ),
            ),
        )
        val sourceVm = SettingsViewModel(
            packageIndexSource = emptyPackageSource,
            settingsRepository = sourceRepo,
            dialerPackageResolver = { "com.android.dialer" },
            ioDispatcher = testDispatcher,
            configBackupCodec = fastCodec,
        )

        val exportedPlain = sourceVm.exportConfigurationJson(passphrase = null)
        val exportedEncrypted = sourceVm.exportConfigurationJson(passphrase = "my-secret")

        var importCallbackCount = 0
        val targetRepo = FakeSettingsRepository(DryToastSettings())
        val targetVm = SettingsViewModel(
            packageIndexSource = emptyPackageSource,
            settingsRepository = targetRepo,
            dialerPackageResolver = { "com.android.dialer" },
            ioDispatcher = testDispatcher,
            configBackupCodec = fastCodec,
            onConfigurationImported = { importCallbackCount++ },
        )

        // Plaintext import
        val plainOutcome = targetVm.importConfigurationJson(exportedPlain, null)
        assertThat(plainOutcome).isEqualTo(ConfigImportOutcome.Success(stagedForReboot = false))
        assertThat(importCallbackCount).isEqualTo(1)
        assertThat(targetRepo.getSettings().general.breakGlassTagUid).isEqualTo("04:AA:BB:CC")
        assertThat(targetRepo.getSettings().baseProfile.appAssignments).containsEntry(social, AppAssignment.Blocked)

        // Encrypted import without passphrase -> PassphraseRequired
        assertThat(targetVm.importConfigurationJson(exportedEncrypted, null))
            .isEqualTo(ConfigImportOutcome.PassphraseRequired)
        assertThat(importCallbackCount).isEqualTo(1)

        // Encrypted import with wrong passphrase -> Error
        val wrongPassOutcome = targetVm.importConfigurationJson(exportedEncrypted, "wrong")
        assertThat(wrongPassOutcome).isInstanceOf(ConfigImportOutcome.Error::class.java)
        assertThat((wrongPassOutcome as ConfigImportOutcome.Error).reason)
            .isEqualTo(ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED)
        assertThat(importCallbackCount).isEqualTo(1)

        // Encrypted import with right passphrase -> Success
        assertThat(targetVm.importConfigurationJson(exportedEncrypted, "my-secret"))
            .isEqualTo(ConfigImportOutcome.Success(stagedForReboot = false))
        assertThat(importCallbackCount).isEqualTo(2)
    }

    @Test
    fun viewModel_importRejectedWhenSettingsLockedAndStagedWhenRebootRequired() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FakeSettingsRepository(
            DryToastSettings(
                general = GeneralSettings(showUnlocks = true),
                isSettingsLocked = true,
            ),
        )
        var callbackInvoked = false
        val vm = SettingsViewModel(
            packageIndexSource = emptyPackageSource,
            settingsRepository = repo,
            dialerPackageResolver = { "com.android.dialer" },
            ioDispatcher = testDispatcher,
            configBackupCodec = fastCodec,
            onConfigurationImported = { callbackInvoked = true },
        )

        val validJson = fastCodec.encode(
            DryToastSettings(general = GeneralSettings(showUnlocks = false)).toStagedSnapshot(),
        )
        assertThat(vm.importConfigurationJson(validJson)).isEqualTo(ConfigImportOutcome.SettingsLocked)
        assertThat(callbackInvoked).isFalse()
        assertThat(repo.getSettings().general.showUnlocks).isTrue()

        // Unlock and enable requireRebootForSettingsChange
        repo.setSettingsLocked(false)
        repo.updateGeneralSettings(GeneralSettings(showUnlocks = true, requireRebootForSettingsChange = true))

        val stagedOutcome = vm.importConfigurationJson(validJson)
        assertThat(stagedOutcome).isEqualTo(ConfigImportOutcome.Success(stagedForReboot = true))
        assertThat(callbackInvoked).isTrue()
        assertThat(repo.getSettings().hasPendingRebootChanges).isTrue()
    }

    // -------------------------------------------------------------------------
    // DryToastSettingsScreen Backup & Restore Compose UI tests
    // -------------------------------------------------------------------------

    @Test
    fun settingsScreen_exportSheet_supportsPlaintextAndValidatesPassphraseMatch() {
        var requestedPassphrase: String? = "UNSET"
        var exportCallCount = 0

        composeTestRule.setContent {
            DryToastTheme {
                DryToastSettingsScreen(
                    uiState = SettingsUiState(isSettingsLoaded = true),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onRequestExport = { pass ->
                        exportCallCount++
                        requestedPassphrase = pass
                    },
                )
            }
        }

        // Scroll to and click Export configuration
        composeTestRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.EXPORT_CONFIG_ACTION))
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIG_ACTION).performClick()

        // Blank passphrase -> Save enabled, exports plaintext (null)
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIG_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertThat(exportCallCount).isEqualTo(1)
        assertThat(requestedPassphrase).isNull()

        // Re-open, enter passphrase -> confirm field appears, mismatch disables Save
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIG_ACTION).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_PASSPHRASE_INPUT).performTextInput("toast-key")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_PASSPHRASE_INPUT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON).assertIsNotEnabled()

        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_PASSPHRASE_INPUT).performTextInput("toast-key")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertThat(exportCallCount).isEqualTo(2)
        assertThat(requestedPassphrase).isEqualTo("toast-key")
    }

    @Test
    fun settingsScreen_importRowDisabledWhenLockedAndPassphraseSheetShowsErrors() {
        var importClicked = false
        var submittedPassphrase: String? = null

        composeTestRule.setContent {
            DryToastTheme {
                DryToastSettingsScreen(
                    uiState = SettingsUiState(isSettingsLoaded = true, isSettingsLocked = true),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onRequestImport = { importClicked = true },
                    showImportPassphraseSheet = true,
                    importPassphraseError = "Incorrect passphrase or modified backup file",
                    onSubmitImportPassphrase = { submittedPassphrase = it },
                )
            }
        }

        // Passphrase sheet shows inline error and disables button when empty
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_ERROR, useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_UNLOCK_BUTTON).assertIsNotEnabled()

        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_INPUT).performTextInput("secret-123")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_UNLOCK_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_UNLOCK_BUTTON)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertThat(submittedPassphrase).isEqualTo("secret-123")
        assertThat(importClicked).isFalse()
    }

    @Test
    fun settingsScreen_importRowShowsLockedStateWhenSettingsLocked() {
        var importClicked = false

        composeTestRule.setContent {
            DryToastTheme {
                DryToastSettingsScreen(
                    uiState = SettingsUiState(isSettingsLoaded = true, isSettingsLocked = true),
                    onBack = {},
                    onNavigateToConfigureApps = {},
                    onUpdateShowUnlocks = {},
                    onUpdateShowRest = {},
                    onUpdateRequireFullTyping = {},
                    onUpdateDefaultFriction = {},
                    onAddQuickAction = {},
                    onRemoveQuickAction = {},
                    onRequestImport = { importClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SettingsTestTags.IMPORT_CONFIG_ACTION))
        composeTestRule.onNodeWithTag(SettingsTestTags.IMPORT_CONFIG_ACTION).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Locked while Settings Lock is active").assertIsDisplayed()
        assertThat(importClicked).isFalse()
    }
}
