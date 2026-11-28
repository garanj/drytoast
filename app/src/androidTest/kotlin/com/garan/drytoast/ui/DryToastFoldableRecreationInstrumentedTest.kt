package com.garan.drytoast.ui

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.ui.settings.ProfilesTestTags
import com.garan.drytoast.ui.settings.SettingsTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryToastFoldableRecreationInstrumentedTest {

    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.QUERY_ALL_PACKAGES")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.setOrientationNatural()

        val container = DryToastAppContainer.from(instrumentation.targetContext)
        container.defaultLauncherOverrideForTesting = true
        runBlocking {
            val currentGeneral = container.settingsRepository.getSettings().editableSnapshot.general
            container.settingsRepository.updateGeneralSettings(
                currentGeneral.copy(hasCompletedOnboarding = true),
            )
            container.settingsRepository.setHasCompletedOnboarding(true)
        }
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        runCatching { device.executeShellCommand("wm size reset") }
        runCatching { device.executeShellCommand("wm density reset") }
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
        device.pressHome()
    }

    @Test
    fun foldableCoverToInnerDensityTransition_recreatesActivityAndPreservesOpenTriggerEditorDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        try {
            // 1. Start on simulated foldable cover display (1080x2400 @ 420dpi)
            device.executeShellCommand("wm size 1080x2400")
            device.executeShellCommand("wm density 420")
            assertThat(
                pollFresh(5_000L) {
                    device.displayWidth == 1080 && device.displayHeight == 2400
                },
            ).isTrue()

            val homeIntent = Intent(targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            scenario = ActivityScenario.launch(homeIntent)
            var initialActivityIdentity = 0
            scenario?.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                initialActivityIdentity = System.identityHashCode(activity)
                activity.searchViewModel.activateSearch()
                activity.searchViewModel.onQueryChanged(":settings")
            }
            assertThat(waitForActivityWindowFocus()).isTrue()

            // 2. Navigate Home (:settings) -> Settings -> Profiles -> Triggers -> Add Trigger
            checkNotNull(
                device.waitForFresh(By.text("Dry Toast Settings"), 10_000L),
            ) { "Expected ':settings' action row on Home" }.click()
            device.waitForIdle()

            checkNotNull(
                device.waitForFresh(By.res(SettingsTestTags.NAV_PROFILES), 10_000L),
            ) { "Expected Custom profiles row in Settings" }.click()
            device.waitForIdle()

            checkNotNull(
                device.waitForFresh(By.res(ProfilesTestTags.TAB_TRIGGERS), 10_000L),
            ) { "Expected Triggers tab on Profiles screen" }.click()
            device.waitForIdle()

            checkNotNull(
                device.waitForFresh(By.res(ProfilesTestTags.ADD_TRIGGER_BUTTON), 10_000L),
            ) { "Expected Add Trigger button on Triggers tab" }.click()
            device.waitForIdle()

            val nameField = checkNotNull(
                device.waitForFresh(By.res(ProfilesTestTags.TRIGGER_EDITOR_NAME_INPUT), 10_000L),
            ) { "Expected TriggerEditorSheet name input on cover display" }
            nameField.text = "Studio Fold"
            device.waitForIdle()
            assertThat(device.waitForFresh(By.textContains("Studio Fold"), 5_000L)).isNotNull()

            // Switch trigger kind to Geo so both text draft and selected kind are verified
            checkNotNull(
                device.waitForFresh(By.res(ProfilesTestTags.TRIGGER_EDITOR_TYPE_GEO), 5_000L),
            ) { "Expected Geo trigger type button" }.click()
            device.waitForIdle()

            assertThat(device.waitForFresh(By.text("Inside zone"), 5_000L)).isNotNull()

            // 3. Unfold to simulated inner foldable display (1812x2176 @ 360dpi), triggering Activity recreation
            device.executeShellCommand("wm size 1812x2176")
            device.executeShellCommand("wm density 360")

            assertThat(
                pollFresh(10_000L) {
                    device.displayWidth == 1812 && device.displayHeight == 2176
                },
            ).isTrue()

            // Note: While ModalBottomSheet is open, its dialog sub-window holds window focus above MainActivity,
            // so check RESUMED lifecycle state + new Activity instance identity rather than activity.hasWindowFocus().
            assertThat(
                pollFresh(10_000L) {
                    var recreatedAndResumed = false
                    runCatching {
                        scenario?.onActivity { activity ->
                            recreatedAndResumed =
                                System.identityHashCode(activity) != initialActivityIdentity &&
                                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        }
                    }
                    recreatedAndResumed
                },
            ).isTrue()
            SystemClock.sleep(250L)

            // 4. Verify TriggerEditorSheet remained open across recreation with draft name & Geo kind intact
            assertThat(
                device.waitForFresh(By.res(ProfilesTestTags.TRIGGER_EDITOR_NAME_INPUT), 10_000L),
            ).isNotNull()
            assertThat(
                device.waitForFresh(By.textContains("Studio Fold"), 5_000L),
            ).isNotNull()
            assertThat(
                device.waitForFresh(By.text("Inside zone"), 5_000L),
            ).isNotNull()
        } finally {
            runCatching { device.executeShellCommand("wm size reset") }
            runCatching { device.executeShellCommand("wm density reset") }
        }
    }

    private fun waitForActivityWindowFocus(): Boolean = pollFresh(5_000L) {
        var focused = false
        runCatching {
            scenario?.onActivity { activity ->
                focused = activity.hasWindowFocus()
            }
        }
        focused
    }
}
