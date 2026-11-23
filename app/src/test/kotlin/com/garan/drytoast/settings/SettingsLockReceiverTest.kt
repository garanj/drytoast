package com.garan.drytoast.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.security.ChallengeResponseVerifier
import com.garan.drytoast.core.security.VerificationDomain
import com.garan.drytoast.ui.home.HOME_CANVAS_TEST_TAG
import com.garan.drytoast.ui.settings.FakeSettingsRepository
import com.garan.drytoast.ui.settings.HardenedModeDeepLink
import com.garan.drytoast.ui.settings.HardenedModeTestTags
import com.garan.drytoast.ui.settings.SettingsTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLockReceiverTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowToast.reset()
        val container = DryToastAppContainer.from(context)
        container.defaultLauncherOverrideForTesting = true
        container.deviceOwnerOverrideForTesting = null
        runBlocking {
            container.settingsRepository.setHasCompletedOnboarding(true)
            container.settingsRepository.setSettingsLocked(false)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.activity.settingsViewModel.uiState.value.general.hasCompletedOnboarding
        }
        composeRule.waitForIdle()
    }

    @Test
    fun manifestRegistration_exportsSettingsLockReceiverWithDumpPermission() {
        val pm = context.packageManager
        val component = ComponentName(context, SettingsLockReceiver::class.java)
        val receiverInfo = pm.getReceiverInfo(component, PackageManager.GET_META_DATA)

        assertThat(receiverInfo.exported).isTrue()
        assertThat(receiverInfo.permission).isEqualTo(Manifest.permission.DUMP)
    }

    @Test
    fun adbBroadcast_lockAndUnlockActions_toggleRepositoryLockAndGuardMutations() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeRepository = FakeSettingsRepository()
        val receiver = SettingsLockReceiver(
            settingsRepositoryOverride = fakeRepository,
            ioDispatcher = testDispatcher,
        )

        // Simulate `adb shell am broadcast -a com.garan.drytoast.action.LOCK_SETTINGS`
        receiver.onReceive(context, Intent(SettingsLockReceiver.ACTION_LOCK_SETTINGS))
        assertThat(fakeRepository.getSettings().isSettingsLocked).isTrue()

        // Attempt to mutate settings while locked -> rejected
        fakeRepository.updateGeneralSettings(GeneralSettings(showUnlocks = false))
        assertThat(fakeRepository.getSettings().general.showUnlocks).isTrue()

        // Simulate `adb shell am broadcast -a com.garan.drytoast.action.UNLOCK_SETTINGS`
        receiver.onReceive(context, Intent(SettingsLockReceiver.ACTION_UNLOCK_SETTINGS))
        assertThat(fakeRepository.getSettings().isSettingsLocked).isFalse()

        // Mutations now succeed again
        fakeRepository.updateGeneralSettings(GeneralSettings(showUnlocks = false))
        assertThat(fakeRepository.getSettings().general.showUnlocks).isFalse()
    }

    @Test
    fun mainActivity_whenSettingsLockedViaAdb_blocksLongPressAndSearchEntryWithToast_andKicksOutIfOpen() {
        val container = DryToastAppContainer.from(context)

        // 1. Unlocked initially: 3-second long-press on home canvas opens Settings
        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .performTouchInput { longClick(position = Offset(centerX, 20f), durationMillis = 3_100L) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .assertIsDisplayed()

        // 2. While Settings is open, send ADB LOCK_SETTINGS broadcast through SettingsLockReceiver ->
        //    kicks user back to Home with Toast
        SettingsLockReceiver().onReceive(context, Intent(SettingsLockReceiver.ACTION_LOCK_SETTINGS))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.activity.settingsViewModel.uiState.value.isSettingsLocked
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .assertIsDisplayed()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Settings are locked")

        // 3. Long-pressing home canvas while locked does NOT open Settings and shows Toast
        ShadowToast.reset()
        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .performTouchInput { longClick(position = Offset(centerX, 20f), durationMillis = 3_100L) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .assertIsDisplayed()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Settings are locked")

        // 4. Tapping "Dry Toast Settings" in Search while locked does NOT open Settings and shows Toast
        ShadowToast.reset()
        composeRule.onNodeWithText("Type to launch...")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction())
            .performTextInput(":settings")
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.activity.searchViewModel.uiState.value.results.isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dry Toast Settings")
            .performClick()
        composeRule.waitForIdle()

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Settings are locked")

        // 5. Delivering the QR-code disable deep link while settings are locked IS allowed ->
        //    opens DisableHardenedModeScreen without being kicked out by the settings lock guard
        ShadowToast.reset()
        container.deviceOwnerOverrideForTesting = true
        val deepLinkIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(HardenedModeDeepLink.FULL_DISABLE_URI),
        )
        composeRule.runOnUiThread {
            composeRule.activity.onNewIntent(deepLinkIntent)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HardenedModeTestTags.DISABLE_SCREEN_ROOT)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON)
            .assertIsDisplayed()
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()

        composeRule.onNodeWithTag(HardenedModeTestTags.CANCEL_DISABLE_BUTTON)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .assertIsDisplayed()

        // 5b. Delivering the Settings Lock QR deep link while settings are locked opens SettingsLockScreen
        //     and entering the 6-digit verification code for the displayed 3-digit challenge unlocks Settings
        val fixedNowMillis = 1_795_549_500_000L
        composeRule.activity.setNowEpochMillisForTesting(fixedNowMillis)
        val settingsLockIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(HardenedModeDeepLink.FULL_SETTINGS_LOCK_URI),
        )
        composeRule.runOnUiThread {
            composeRule.activity.onNewIntent(settingsLockIntent)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HardenedModeTestTags.SETTINGS_LOCK_SCREEN_ROOT)
            .assertIsDisplayed()

        val displayedChallenge = composeRule.onNodeWithTag(HardenedModeTestTags.CHALLENGE_CODE_VALUE)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .text
        val unlockCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.SETTINGS_LOCK,
            challenge = displayedChallenge,
            bucket = ChallengeResponseVerifier.timeBucket(fixedNowMillis),
        )
        for (ch in unlockCode) {
            composeRule.onNodeWithTag(HardenedModeTestTags.keypadDigitTag(ch.digitToInt())).performClick()
        }
        composeRule.onNodeWithTag(HardenedModeTestTags.CONFIRM_SETTINGS_LOCK_BUTTON)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !composeRule.activity.settingsViewModel.uiState.value.isSettingsLocked
        }
        composeRule.waitForIdle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Settings unlocked")

        // Re-lock via ADB so step 6 can also verify ADB UNLOCK_SETTINGS
        SettingsLockReceiver().onReceive(context, Intent(SettingsLockReceiver.ACTION_LOCK_SETTINGS))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.activity.settingsViewModel.uiState.value.isSettingsLocked
        }
        composeRule.waitForIdle()

        // 6. Unlock via ADB UNLOCK_SETTINGS broadcast through SettingsLockReceiver ->
        //    long-pressing home canvas opens Settings again
        SettingsLockReceiver().onReceive(context, Intent(SettingsLockReceiver.ACTION_UNLOCK_SETTINGS))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !composeRule.activity.settingsViewModel.uiState.value.isSettingsLocked
        }
        composeRule.activity.searchViewModel.dismissSearch()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_CANVAS_TEST_TAG)
            .performTouchInput { longClick(position = Offset(centerX, 20f), durationMillis = 3_100L) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SettingsTestTags.SETTINGS_LIST)
            .assertIsDisplayed()
    }
}
