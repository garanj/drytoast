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
import com.garan.drytoast.core.model.AppAccessState
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.FrictionPolicy
import com.garan.drytoast.core.search.SearchItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryToastFrictionRotationInstrumentedTest {

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
                    requireFullTypingOfGuardedApps = false,
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
    fun typedIntentionCheckpoint_preservesPhraseAndTypedProgressAcrossPortraitToLandscapeRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val launcherApps = targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != targetContext.packageName }
        val candidate = activities.firstOrNull { info ->
            val lbl = info.label.toString()
            lbl.isNotBlank() && lbl.all { it.isLetterOrDigit() || it == ' ' }
        } ?: activities.firstOrNull()
            ?: error("No launchable third-party/system activity found for test")

        val component = ComponentKey(
            packageName = candidate.componentName.packageName,
            activityName = candidate.componentName.className,
        )
        val label = candidate.label.toString()

        val homeIntent = Intent(targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        scenario = ActivityScenario.launch(homeIntent)
        scenario?.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.searchViewModel.openFriction(
                app = SearchItem.App(
                    component = component,
                    label = label,
                    accessState = AppAccessState.Guarded(FrictionPolicy.TypedIntention),
                ),
                policy = FrictionPolicy.TypedIntention,
            )
        }

        assertThat(waitForActivityWindowFocus()).isTrue()

        val phraseNode = checkNotNull(
            device.waitForFresh(By.res("typed_intention_phrase"), 10_000L),
        ) { "Expected typed_intention_phrase node on screen" }
        val targetPhrase = phraseNode.text
        assertThat(targetPhrase.length).isGreaterThan(5)

        // Tap phrase surface once to guarantee Compose focus is bound after window focus settles
        phraseNode.click()
        device.waitForIdle()

        val splitIndex = targetPhrase.length / 2
        val firstHalf = targetPhrase.substring(0, splitIndex)
        val secondHalf = targetPhrase.substring(splitIndex)
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

        typeSynchronously(keyMap, firstHalf)

        // Rotate device Portrait -> Landscape mid-phrase and wait for landscape geometry + window focus
        device.setOrientationLeft()
        assertThat(
            pollFresh(5_000L) { device.displayWidth > device.displayHeight },
        ).isTrue()
        assertThat(waitForActivityWindowFocus()).isTrue()
        SystemClock.sleep(250L)

        val rotatedPhraseNode = checkNotNull(
            device.waitForFresh(By.res("typed_intention_phrase"), 5_000L),
        ) { "Expected typed_intention_phrase node after landscape rotation" }
        assertThat(rotatedPhraseNode.text).isEqualTo(targetPhrase)

        // Re-confirm focus on the rotated surface, then type only the remaining characters
        rotatedPhraseNode.click()
        device.waitForIdle()

        typeSynchronously(keyMap, secondHalf)

        val openButton = device.waitForFresh(By.text("Open"), 10_000L)
        assertThat(openButton).isNotNull()
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
