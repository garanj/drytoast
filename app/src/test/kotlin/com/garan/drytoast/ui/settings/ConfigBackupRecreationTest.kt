package com.garan.drytoast.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.core.backup.ConfigBackupCodec
import com.garan.drytoast.core.backup.ConfigImportResult
import com.garan.drytoast.core.model.DryToastSettings
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.ui.home.HOME_CANVAS_TEST_TAG
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConfigBackupRecreationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var appContext: Context
    private lateinit var container: DryToastAppContainer

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        container = DryToastAppContainer.from(appContext)
        container.defaultLauncherOverrideForTesting = true
        runBlocking {
            container.settingsRepository.setSettingsLocked(false)
            container.settingsRepository.setHasCompletedOnboarding(true)
            container.settingsRepository.updateGeneralSettings(
                GeneralSettings(
                    hasCompletedOnboarding = true,
                    showUnlocks = false,
                    breakGlassTagUid = "04:DE:AD:BE:EF",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        container.defaultLauncherOverrideForTesting = null
    }

    private fun openSettingsFromHome() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag(HOME_CANVAS_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Type to launch...").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(":settings")
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Dry Toast Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dry Toast Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag(SettingsTestTags.SETTINGS_LIST).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun encryptedExport_activityRecreatedDuringSafPicker_writesEncryptedBackupNotPlaintext() {
        val outputFile = tempFolder.newFile("exported-backup.json")
        val outputUri = Uri.fromFile(outputFile)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsFromHome()

            composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
                .performScrollToNode(hasTestTag(SettingsTestTags.EXPORT_CONFIG_ACTION))
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIG_ACTION).performClick()

            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_PASSPHRASE_INPUT)
                .performTextInput("foldable-secret-key")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_PASSPHRASE_INPUT)
                .performTextInput("foldable-secret-key")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()

            var createDocIntent: Intent? = null
            scenario.onActivity { activity ->
                createDocIntent = checkNotNull(shadowOf(activity).nextStartedActivityForResult?.intent) {
                    "Expected CreateDocument SAF intent to be launched"
                }
            }

            // Simulate foldable cover <-> inner densityDpi configuration recreation while in SAF
            scenario.recreate()
            composeRule.waitForIdle()

            // Deliver SAF file URI result to the recreated MainActivity instance
            scenario.onActivity { recreatedActivity ->
                shadowOf(recreatedActivity).receiveResult(
                    checkNotNull(createDocIntent),
                    Activity.RESULT_OK,
                    Intent().setData(outputUri),
                )
            }

            composeRule.waitUntil(timeoutMillis = 10_000L) {
                outputFile.length() > 0L
            }

            val exportedJson = outputFile.readText(Charsets.UTF_8)
            val codec = ConfigBackupCodec()
            // Must require passphrase (NEVER silently fall back to plaintext!)
            assertThat(codec.decode(exportedJson, passphrase = null))
                .isEqualTo(ConfigImportResult.PassphraseRequired)

            val decrypted = codec.decode(exportedJson, passphrase = "foldable-secret-key")
            assertThat(decrypted).isInstanceOf(ConfigImportResult.Success::class.java)
            assertThat((decrypted as ConfigImportResult.Success).snapshot.general.breakGlassTagUid)
                .isEqualTo("04:DE:AD:BE:EF")
        }
    }

    @Test
    fun encryptedImport_activityRecreatedWhilePassphraseSheetOpen_keepsSheetAndImportsOnSubmit() {
        val codec = ConfigBackupCodec()
        val encryptedBackupJson = codec.encode(
            DryToastSettings(
                general = GeneralSettings(
                    hasCompletedOnboarding = true,
                    showRest = false,
                    breakGlassDurationMinutes = 60,
                ),
            ).editableSnapshot,
            passphrase = "import-fold-passphrase",
        )
        val inputFile = tempFolder.newFile("encrypted-input.json").apply {
            writeText(encryptedBackupJson, Charsets.UTF_8)
        }
        val inputUri = Uri.fromFile(inputFile)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsFromHome()

            composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
                .performScrollToNode(hasTestTag(SettingsTestTags.IMPORT_CONFIG_ACTION))
            composeRule.onNodeWithTag(SettingsTestTags.IMPORT_CONFIG_ACTION).performClick()
            composeRule.waitForIdle()

            var openDocIntent: Intent? = null
            scenario.onActivity { activity ->
                openDocIntent = checkNotNull(shadowOf(activity).nextStartedActivityForResult?.intent) {
                    "Expected OpenDocument SAF intent to be launched"
                }
            }

            scenario.onActivity { activity ->
                shadowOf(activity).receiveResult(
                    checkNotNull(openDocIntent),
                    Activity.RESULT_OK,
                    Intent().setData(inputUri),
                )
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag(SettingsTestTags.IMPORT_PASSPHRASE_SHEET)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_SHEET).assertIsDisplayed()

            // Simulate foldable densityDpi recreation while the passphrase sheet is open
            scenario.recreate()
            composeRule.waitForIdle()

            // Passphrase sheet must remain displayed on recreated MainActivity
            composeRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_SHEET).assertIsDisplayed()
            composeRule.onNodeWithTag(SettingsTestTags.IMPORT_PASSPHRASE_INPUT)
                .performTextInput("import-fold-passphrase")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SettingsTestTags.IMPORT_UNLOCK_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)

            composeRule.waitUntil(timeoutMillis = 10_000L) {
                runBlocking {
                    container.settingsRepository.getSettings().general.breakGlassDurationMinutes == 60
                }
            }

            val updated = runBlocking { container.settingsRepository.getSettings() }
            assertThat(updated.general.showRest).isFalse()
            assertThat(updated.general.breakGlassDurationMinutes).isEqualTo(60)
        }
    }

    @Test
    fun exportCallback_whenStagedRequestCleared_failsClosedWithoutWritingPlaintext() {
        val outputFile = tempFolder.newFile("unsolicited-export.json")
        val outputUri = Uri.fromFile(outputFile)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsFromHome()

            composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
                .performScrollToNode(hasTestTag(SettingsTestTags.EXPORT_CONFIG_ACTION))
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIG_ACTION).performClick()
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_PASSPHRASE_INPUT)
                .performTextInput("secret-pass")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_PASSPHRASE_INPUT)
                .performTextInput("secret-pass")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()

            var createDocIntent: Intent? = null
            scenario.onActivity { activity ->
                createDocIntent = checkNotNull(shadowOf(activity).nextStartedActivityForResult?.intent)
                // Simulate process-death loss of in-memory ViewModel request before SAF callback arrives
                activity.settingsViewModel.cancelPendingExportRequest()
                shadowOf(activity).receiveResult(
                    checkNotNull(createDocIntent),
                    Activity.RESULT_OK,
                    Intent().setData(outputUri),
                )
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Could not save backup file").fetchSemanticsNodes().isNotEmpty()
            }
            assertThat(outputFile.length()).isEqualTo(0L)
        }
    }
}
