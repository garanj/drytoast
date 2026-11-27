package com.garan.drytoast.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
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
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val launcherApps = targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val candidate = launcherApps.getActivityList(null, Process.myUserHandle())
            .firstOrNull { it.applicationInfo.packageName != targetContext.packageName }
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

        val phraseNode = checkNotNull(
            device.waitForFresh(By.res("typed_intention_phrase"), 10_000L),
        ) { "Expected typed_intention_phrase node on screen" }
        val targetPhrase = phraseNode.text
        assertThat(targetPhrase.length).isGreaterThan(5)

        val splitIndex = targetPhrase.length / 2
        val firstHalf = targetPhrase.substring(0, splitIndex)
        val secondHalf = targetPhrase.substring(splitIndex)

        for (ch in firstHalf) {
            device.executeShellCommand("input text ${shellEscapeSingleChar(ch)}")
        }

        // Rotate device Portrait -> Landscape mid-phrase
        device.setOrientationLeft()
        device.waitForIdle()

        val rotatedPhraseNode = checkNotNull(
            device.waitForFresh(By.res("typed_intention_phrase"), 5_000L),
        ) { "Expected typed_intention_phrase node after landscape rotation" }
        assertThat(rotatedPhraseNode.text).isEqualTo(targetPhrase)

        // Type only the second half after rotation -> must complete and reveal "Open"
        for (ch in secondHalf) {
            device.executeShellCommand("input text ${shellEscapeSingleChar(ch)}")
        }

        val openButton = device.waitForFresh(By.text("Open"), 5_000L)
        assertThat(openButton).isNotNull()
    }

    private fun shellEscapeSingleChar(ch: Char): String = when (ch) {
        ' ' -> "%s"
        '\'' -> "\\'"
        '"' -> "\\\""
        else -> ch.toString()
    }
}
