package com.garan.drytoast.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.garan.drytoast.core.backup.ConfigBackupCodec
import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.BaseProfileSettings
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.DryToastSettings
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.StagedSettingsSnapshot
import com.garan.drytoast.ui.settings.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugConfigImportReceiverTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val fastCodec = ConfigBackupCodec(pbkdf2Iterations = 1_000)

    private fun newReceiver(
        repo: FakeSettingsRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        onImported: suspend () -> Unit = {},
    ): DebugConfigImportReceiver = DebugConfigImportReceiver(
        settingsRepositoryOverride = repo,
        configBackupCodec = fastCodec,
        ioDispatcher = dispatcher,
        onImportedOverride = onImported,
    )

    @Test
    fun handleImport_validPlaintextFile_importsSuccessfullyAndInvokesCallback() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FakeSettingsRepository(DryToastSettings())
        var importedCount = 0
        val receiver = newReceiver(repo, dispatcher) { importedCount++ }
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        val social = ComponentKey("com.reddit.frontpage", "com.reddit.frontpage.Main")
        val snapshot = StagedSettingsSnapshot(
            general = GeneralSettings(showUnlocks = false),
            baseProfile = BaseProfileSettings(
                defaultFriction = FrictionChoice.CARET_TRACING,
                appAssignments = mapOf(social to AppAssignment.Blocked),
            ),
        )
        val file = tmpFolder.newFile("batch_a.json")
        file.writeText(fastCodec.encode(snapshot))

        val (code, message) = receiver.handleImport(
            appContext = appContext,
            path = file.absolutePath,
            inlineJson = null,
            passphrase = null,
        )

        assertThat(code).isEqualTo(DebugConfigImportReceiver.RESULT_OK)
        assertThat(message).isEqualTo("OK")
        assertThat(importedCount).isEqualTo(1)
        assertThat(repo.getSettings().general.showUnlocks).isFalse()
        assertThat(repo.getSettings().baseProfile.defaultFriction).isEqualTo(FrictionChoice.CARET_TRACING)
        assertThat(repo.getSettings().baseProfile.appAssignments).containsEntry(social, AppAssignment.Blocked)
    }

    @Test
    fun handleImport_validInlineJsonAndEncryptedFile_succeedWithPassphraseAndFailWithout() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FakeSettingsRepository(DryToastSettings())
        val receiver = newReceiver(repo, dispatcher)
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        val snapshot = StagedSettingsSnapshot(general = GeneralSettings(showRest = false))

        // Inline plaintext JSON
        val (inlineCode, inlineMsg) = receiver.handleImport(
            appContext = appContext,
            path = null,
            inlineJson = fastCodec.encode(snapshot),
            passphrase = null,
        )
        assertThat(inlineCode).isEqualTo(DebugConfigImportReceiver.RESULT_OK)
        assertThat(inlineMsg).isEqualTo("OK")
        assertThat(repo.getSettings().general.showRest).isFalse()

        // Encrypted file
        val encFile = tmpFolder.newFile("encrypted.json")
        encFile.writeText(fastCodec.encode(snapshot, passphrase = "secret"))

        // Missing passphrase
        val (noPassCode, noPassMsg) = receiver.handleImport(appContext, encFile.absolutePath, null, null)
        assertThat(noPassCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(noPassMsg).contains("PASSPHRASE_REQUIRED")

        // Wrong passphrase
        val (badPassCode, badPassMsg) = receiver.handleImport(appContext, encFile.absolutePath, null, "nope")
        assertThat(badPassCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(badPassMsg).contains("INVALID_PASSPHRASE_OR_TAMPERED")

        // Correct passphrase
        val (okCode, okMsg) = receiver.handleImport(appContext, encFile.absolutePath, null, "secret")
        assertThat(okCode).isEqualTo(DebugConfigImportReceiver.RESULT_OK)
        assertThat(okMsg).isEqualTo("OK")
    }

    @Test
    fun handleImport_failureModes_returnDescriptiveErrorMessages() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FakeSettingsRepository(DryToastSettings())
        val receiver = newReceiver(repo, dispatcher)
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        // Missing both path and json
        val (missingCode, missingMsg) = receiver.handleImport(appContext, null, null, null)
        assertThat(missingCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(missingMsg).contains("MISSING_INPUT")

        // File not found
        val (notFoundCode, notFoundMsg) = receiver.handleImport(appContext, "/nonexistent/drytoast.json", null, null)
        assertThat(notFoundCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(notFoundMsg).contains("FILE_NOT_FOUND")

        // Directory instead of file
        val dir = tmpFolder.newFolder("not_a_file")
        val (dirCode, dirMsg) = receiver.handleImport(appContext, dir.absolutePath, null, null)
        assertThat(dirCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(dirMsg).contains("FILE_UNREADABLE")

        // Malformed JSON
        val badJson = tmpFolder.newFile("bad.json").apply { writeText("{broken") }
        val (badJsonCode, badJsonMsg) = receiver.handleImport(appContext, badJson.absolutePath, null, null)
        assertThat(badJsonCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(badJsonMsg).contains("INVALID_JSON")

        // Invalid domain value
        val badDomain = tmpFolder.newFile("bad_domain.json").apply {
            writeText(
                """
                {
                  "format": "drytoast-config",
                  "version": 1,
                  "encrypted": false,
                  "config": {
                    "customProfiles": [
                      {"id": "p1", "name": "Work", "defaultRule": "INVALID_RULE"}
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
        val (domainCode, domainMsg) = receiver.handleImport(appContext, badDomain.absolutePath, null, null)
        assertThat(domainCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(domainMsg).contains("INVALID_CONFIG_VALUES")
        assertThat(domainMsg).contains("INVALID_RULE")
    }

    @Test
    fun handleImport_respectsSettingsLockAndRebootStaging() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = FakeSettingsRepository(DryToastSettings(isSettingsLocked = true))
        val receiver = newReceiver(repo, dispatcher)
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        val validJson = fastCodec.encode(StagedSettingsSnapshot(general = GeneralSettings(showUnlocks = false)))
        val (lockedCode, lockedMsg) = receiver.handleImport(appContext, null, validJson, null)
        assertThat(lockedCode).isEqualTo(DebugConfigImportReceiver.RESULT_ERROR)
        assertThat(lockedMsg).contains("SETTINGS_LOCKED")
        assertThat(repo.getSettings().general.showUnlocks).isTrue()

        // Unlock and enable Reboot-to-Apply
        repo.setSettingsLocked(false)
        repo.updateGeneralSettings(GeneralSettings(showUnlocks = true, requireRebootForSettingsChange = true))

        val (stagedCode, stagedMsg) = receiver.handleImport(appContext, null, validJson, null)
        assertThat(stagedCode).isEqualTo(DebugConfigImportReceiver.RESULT_OK)
        assertThat(stagedMsg).isEqualTo("OK (staged for reboot)")
        assertThat(repo.getSettings().hasPendingRebootChanges).isTrue()
    }
}
