package com.garan.drytoast.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.SystemClock
import android.view.KeyCharacterMap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.MainActivity
import com.garan.drytoast.ui.home.HOME_DIALER_BUTTON_TEST_TAG
import com.garan.drytoast.ui.home.HOME_HEADER_COMPACT_TEST_TAG
import com.garan.drytoast.ui.search.SEARCH_INPUT_TEST_TAG
import com.garan.drytoast.ui.search.SEARCH_PLACEHOLDER_TEST_TAG
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryToastSearchRotationInstrumentedTest {

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
                currentGeneral.copy(
                    hasCompletedOnboarding = true,
                    showDialerIcon = true,
                ),
            )
            container.settingsRepository.setHasCompletedOnboarding(true)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
        device.pressHome()
    }

    @Test
    fun activeSearchWithOpenKeyboard_preservesQueryAndKeepsTopResultVisibleAcrossLandscapeRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val launcherApps = targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != targetContext.packageName }

        // Prefer "Camera" ("cam") when installed on the emulator; fall back to an installed app's
        // 3-letter alphabetic prefix on minimal AOSP images so the test is deterministic everywhere.
        val cameraCandidate = activities.firstOrNull {
            it.label.toString().startsWith("Cam", ignoreCase = true)
        }
        val chosenActivity = cameraCandidate
            ?: activities.firstOrNull { info ->
                val lbl = info.label.toString()
                lbl.length >= 3 && lbl.take(3).all(Char::isLetter)
            }
            ?: error("No launchable alphabetic activity found for search rotation test")

        val expectedLabel = chosenActivity.label.toString()
        val searchQuery = if (cameraCandidate != null) {
            "cam"
        } else {
            expectedLabel.take(3).lowercase()
        }

        val homeIntent = Intent(targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        scenario = ActivityScenario.launch(homeIntent)
        scenario?.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        assertThat(waitForActivityWindowFocus()).isTrue()

        // 1. Tap Search placeholder on Home to open Search and bring up the soft keyboard
        val placeholderNode = checkNotNull(
            device.waitForFresh(By.res(SEARCH_PLACEHOLDER_TEST_TAG), 10_000L),
        ) { "Expected search_input_placeholder on Home" }
        placeholderNode.click()
        device.waitForIdle()

        val searchInput = checkNotNull(
            device.waitForFresh(By.res(SEARCH_INPUT_TEST_TAG), 10_000L),
        ) { "Expected search_input_field after activating search" }
        searchInput.click()
        device.waitForIdle()

        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        typeSynchronously(keyMap, searchQuery)

        val portraitTopResult = checkNotNull(
            device.waitForFresh(By.text(expectedLabel), 10_000L),
        ) { "Expected top search result '$expectedLabel' in portrait" }
        assertThat(portraitTopResult.visibleBounds.height()).isGreaterThan(0)

        // 2. Rotate device Portrait -> Landscape with soft keyboard active
        device.setOrientationLeft()
        assertThat(
            pollFresh(5_000L) { device.displayWidth > device.displayHeight },
        ).isTrue()
        assertThat(waitForActivityWindowFocus()).isTrue()
        SystemClock.sleep(250L)

        // 3. Verify compact header activates, dialer button stays hidden, query persists,
        // and the top result row remains visible with non-zero height and clickable
        val compactHeader = device.waitForFresh(By.res(HOME_HEADER_COMPACT_TEST_TAG), 5_000L)
        assertThat(compactHeader).isNotNull()
        assertThat(device.findObject(By.res(HOME_DIALER_BUTTON_TEST_TAG))).isNull()

        var persistedQuery = ""
        scenario?.onActivity { activity ->
            persistedQuery = activity.searchViewModel.uiState.value.query
        }
        assertThat(persistedQuery).isEqualTo(searchQuery)

        val rotatedTopResult = checkNotNull(
            device.waitForFresh(By.text(expectedLabel), 5_000L),
        ) { "Expected top search result '$expectedLabel' to remain visible in landscape" }
        val resultBounds = rotatedTopResult.visibleBounds
        assertThat(resultBounds.height()).isGreaterThan(0)
        assertThat(resultBounds.bottom).isAtMost(device.displayHeight)
        assertThat(rotatedTopResult.isClickable || rotatedTopResult.parent?.isClickable == true).isTrue()
    }

    private fun waitForActivityWindowFocus(): Boolean = pollFresh(5_000L) {
        var focused = false
        scenario?.onActivity { activity ->
            focused = activity.hasWindowFocus()
        }
        focused
    }

    private fun typeSynchronously(keyMap: KeyCharacterMap, text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        for (ch in text) {
            val normalized = ch.lowercaseChar()
            val events = keyMap.getEvents(charArrayOf(normalized))
            if (events != null) {
                for (event in events) {
                    uiAutomation.injectInputEvent(event, true)
                }
            } else {
                instrumentation.sendStringSync(normalized.toString())
            }
            instrumentation.waitForIdleSync()
        }
    }
}
