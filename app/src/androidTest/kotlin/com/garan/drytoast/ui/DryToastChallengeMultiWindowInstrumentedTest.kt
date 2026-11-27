package com.garan.drytoast.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.ui.settings.HardenedModeDeepLink
import com.garan.drytoast.ui.settings.HardenedModeTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryToastChallengeMultiWindowInstrumentedTest {

    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")

        val container = DryToastAppContainer.from(instrumentation.targetContext)
        container.defaultLauncherOverrideForTesting = true
        runBlocking {
            container.settingsRepository.setHasCompletedOnboarding(true)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        device.pressHome()
    }

    @Test
    fun settingsLockDeepLink_multiWindowTransitionHidesChallengeAndRotatesOnExit() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HardenedModeDeepLink.FULL_SETTINGS_LOCK_URI)).apply {
            setClass(targetContext, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        scenario = ActivityScenario.launch(intent)
        scenario?.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        val challengeSelector = By.res(HardenedModeTestTags.CHALLENGE_CODE_VALUE)
        val noticeSelector = By.res(HardenedModeTestTags.CHALLENGE_MULTI_WINDOW_NOTICE)

        val initialNode = checkNotNull(
            device.waitForFresh(challengeSelector, 10_000L),
        ) { "Expected 3-digit challenge code to be visible in full-screen" }
        val initialChallenge = initialNode.text
        assertThat(initialChallenge).hasLength(3)

        // Transition MainActivity into multi-window mode and verify challenge is hidden behind notice
        scenario?.onActivity { activity ->
            activity.onMultiWindowModeChanged(true, Configuration(activity.resources.configuration))
        }

        val blockedNotice = device.waitForFresh(noticeSelector, 5_000L)
        assertThat(blockedNotice).isNotNull()
        assertThat(device.waitUntilGoneFresh(challengeSelector, 5_000L)).isTrue()

        // Exit multi-window mode back to full-screen and verify challenge reappears with a rotated code
        scenario?.onActivity { activity ->
            activity.onMultiWindowModeChanged(false, Configuration(activity.resources.configuration))
        }

        assertThat(device.waitUntilGoneFresh(noticeSelector, 5_000L)).isTrue()
        val rotatedNode = checkNotNull(
            device.waitForFresh(challengeSelector, 5_000L),
        ) { "Expected rotated 3-digit challenge code after returning to full-screen" }
        assertThat(rotatedNode.text).hasLength(3)
        assertThat(rotatedNode.text).isNotEqualTo(initialChallenge)
    }
}
