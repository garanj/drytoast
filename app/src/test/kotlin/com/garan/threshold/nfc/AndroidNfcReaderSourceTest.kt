package com.garan.threshold.nfc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.garan.threshold.ThresholdApplication
import com.garan.threshold.core.model.NfcTagEntry
import com.garan.threshold.core.model.NfcTagRegistrationResult
import com.garan.threshold.core.port.HapticEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNfcReaderSourceTest {

    @Test
    fun onActivityResumed_withoutRegistrationListener_doesNotEnableReaderMode_preservingWalletHce() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            adapterGateway = fakeGateway,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        source.onActivityResumed(activity)
        assertThat(fakeGateway.enableCount).isEqualTo(0)

        source.onActivityPaused(activity)
        assertThat(fakeGateway.disableCount).isEqualTo(0)
    }

    @Test
    fun setRegistrationResultListener_whileResumed_enablesReaderModeAndDisablesWhenCleared() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            adapterGateway = fakeGateway,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        source.onActivityResumed(activity)
        assertThat(fakeGateway.enableCount).isEqualTo(0)

        // Opening the NFC Trigger Editor sheet registers a listener -> enables reader mode immediately
        source.setRegistrationResultListener(activity) { }
        assertThat(fakeGateway.enableCount).isEqualTo(1)
        assertThat(fakeGateway.lastEnabledActivity).isSameInstanceAs(activity)
        assertThat(fakeGateway.lastFlags).isEqualTo(AndroidNfcReaderSource.REGISTRATION_READER_FLAGS)
        assertThat(fakeGateway.lastFlags and NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK).isEqualTo(0)

        // Closing the NFC Trigger Editor sheet clears the listener -> disables reader mode immediately
        source.setRegistrationResultListener(activity, null)
        assertThat(fakeGateway.disableCount).isEqualTo(1)
        assertThat(fakeGateway.lastDisabledActivity).isSameInstanceAs(activity)
    }

    @Test
    fun onActivityResumedAndPaused_withActiveRegistrationListener_enablesAndDisablesReaderMode() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            adapterGateway = fakeGateway,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        source.activeRegistrationResultListener = { }
        source.onActivityResumed(activity)
        assertThat(fakeGateway.enableCount).isEqualTo(1)

        source.onActivityPaused(activity)
        assertThat(fakeGateway.disableCount).isEqualTo(1)
    }

    @Test
    fun enableForegroundReader_whenAdapterDisabled_doesNotEnableReaderMode() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = false)
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            adapterGateway = fakeGateway,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        source.enableForegroundReader(activity)
        assertThat(fakeGateway.enableCount).isEqualTo(0)
    }

    @Test
    fun onTagIdDiscovered_formatsHardwareBytesAndRoutesToTagScansOrRegistrationListener() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
            var relaySnapCount = 0
            val fakeHaptic = object : HapticEngine {
                override fun rotaryDetent() = Unit
                override fun targetSnap() = Unit
                override fun resistanceDetent() = Unit
                override fun relaySnap() {
                    relaySnapCount++
                }
            }
            val source = AndroidNfcReaderSource(
                context = ApplicationProvider.getApplicationContext(),
                hapticEngine = fakeHaptic,
                adapterGateway = fakeGateway,
            )

            // 1. Without activeRegistrationListener -> emits normalized UID to tagScans flow
            var emittedUid: String? = null
            val collectJob = launch {
                emittedUid = source.tagScans.first()
            }
            source.onTagIdDiscovered(byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0x8B.toByte()))
            assertThat(emittedUid).isEqualTo("04:A2:3F:8B")
            assertThat(relaySnapCount).isEqualTo(0)
            collectJob.cancel()

            // 2. With activeRegistrationListener -> intercepts UID and fires relaySnap
            var registeredUid: String? = null
            source.activeRegistrationListener = { uid -> registeredUid = uid }
            source.onTagIdDiscovered(byteArrayOf(0x04, 0xB1.toByte(), 0x22, 0x99.toByte()))
            assertThat(registeredUid).isEqualTo("04:B1:22:99")
            assertThat(relaySnapCount).isEqualTo(1)
        }

    @Test
    fun dispatchRegistrationResult_handlesSuccessAndFailureVariantsWithDistinctHaptics() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
        var relaySnapCount = 0
        var resistanceDetentCount = 0
        val fakeHaptic = object : HapticEngine {
            override fun rotaryDetent() = Unit
            override fun targetSnap() = Unit
            override fun resistanceDetent() {
                resistanceDetentCount++
            }
            override fun relaySnap() {
                relaySnapCount++
            }
        }
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            hapticEngine = fakeHaptic,
            adapterGateway = fakeGateway,
        )

        val receivedResults = mutableListOf<NfcTagRegistrationResult>()
        val receivedSuccessUids = mutableListOf<String>()
        source.activeRegistrationResultListener = { receivedResults.add(it) }
        source.activeRegistrationListener = { receivedSuccessUids.add(it) }

        source.dispatchRegistrationResult(NfcTagRegistrationResult.UnsupportedCard("04:B1:22:99"))
        assertThat(receivedResults).containsExactly(NfcTagRegistrationResult.UnsupportedCard("04:B1:22:99"))
        assertThat(receivedSuccessUids).isEmpty()
        assertThat(resistanceDetentCount).isEqualTo(1)
        assertThat(relaySnapCount).isEqualTo(0)

        source.dispatchRegistrationResult(NfcTagRegistrationResult.ReadOnlyTag("04:C1:D2:E3"))
        assertThat(resistanceDetentCount).isEqualTo(2)
        assertThat(receivedSuccessUids).isEmpty()

        source.dispatchRegistrationResult(NfcTagRegistrationResult.Success("04:A2:3F:8B"))
        assertThat(relaySnapCount).isEqualTo(1)
        assertThat(receivedSuccessUids).containsExactly("04:A2:3F:8B")
    }

    @Test
    fun onTagIdDiscovered_fromBackgroundBinderThread_marshalsRegistrationListenerOntoMainThread() {
        val fakeGateway = FakeNfcAdapterGateway(isEnabled = true)
        var relaySnapCount = 0
        val fakeHaptic = object : HapticEngine {
            override fun rotaryDetent() = Unit
            override fun targetSnap() = Unit
            override fun resistanceDetent() = Unit
            override fun relaySnap() {
                relaySnapCount++
            }
        }
        val source = AndroidNfcReaderSource(
            context = ApplicationProvider.getApplicationContext(),
            hapticEngine = fakeHaptic,
            adapterGateway = fakeGateway,
        )

        var callbackThread: Thread? = null
        var registeredUid: String? = null
        source.activeRegistrationListener = { uid ->
            callbackThread = Thread.currentThread()
            registeredUid = uid
        }

        val binderThread = Thread {
            source.onTagIdDiscovered(byteArrayOf(0x04, 0xC1.toByte(), 0xD2.toByte(), 0xE3.toByte()))
        }
        binderThread.start()
        binderThread.join()

        shadowOf(Looper.getMainLooper()).idle()

        assertThat(registeredUid).isEqualTo("04:C1:D2:E3")
        assertThat(callbackThread).isSameInstanceAs(Looper.getMainLooper().thread)
        assertThat(relaySnapCount).isEqualTo(1)
    }

    @Test
    fun systemNfcTagProvisioner_buildsAndRecognizesThresholdNdefMessages() {
        val fullMessage = SystemNfcTagProvisioner.buildFullNdefMessage("com.garan.threshold")
        val compactMessage = SystemNfcTagProvisioner.buildCompactNdefMessage()

        assertThat(SystemNfcTagProvisioner.hasThresholdNdefRecord(fullMessage)).isTrue()
        assertThat(SystemNfcTagProvisioner.hasThresholdNdefRecord(compactMessage)).isTrue()

        val otherUriMessage = NdefMessage(arrayOf(NdefRecord.createUri("https://example.com")))
        assertThat(SystemNfcTagProvisioner.hasThresholdNdefRecord(otherUriMessage)).isFalse()
        assertThat(SystemNfcTagProvisioner.hasThresholdNdefRecord(null)).isFalse()
    }

    @Test
    fun nfcTagDispatchActivity_dispatchesScannedTagUidToAppContainerAndFinishesImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            val app = ApplicationProvider.getApplicationContext<ThresholdApplication>()
            val activityInfo = app.packageManager.getActivityInfo(
                android.content.ComponentName(app, NfcTagDispatchActivity::class.java),
                0,
            )
            assertThat(activityInfo.permission).isEqualTo("android.permission.DISPATCH_NFC_MESSAGE")
            assertThat(activityInfo.taskAffinity).isNull()

            var emittedUid: String? = null
            val job = launch {
                emittedUid = app.container.nfcReaderSource.tagScans.first()
            }

            val intent = Intent(app, NfcTagDispatchActivity::class.java).apply {
                action = NfcAdapter.ACTION_NDEF_DISCOVERED
                data = Uri.parse(NfcTagEntry.THRESHOLD_NDEF_URI)
                putExtra(NfcTagDispatchActivity.EXTRA_TEST_TAG_UID, "04:a2:3f:8b:1c:90:80")
            }

            val controller = Robolectric.buildActivity(NfcTagDispatchActivity::class.java, intent).create()
            val activity = controller.get()

            assertThat(emittedUid).isEqualTo("04:A2:3F:8B:1C:90:80")
            assertThat(activity.isFinishing).isTrue()
            job.cancel()
        }

    private class FakeNfcAdapterGateway(override var isEnabled: Boolean) : NfcAdapterGateway {
        var enableCount = 0
            private set
        var disableCount = 0
            private set
        var lastEnabledActivity: Activity? = null
            private set
        var lastDisabledActivity: Activity? = null
            private set
        var lastCallback: NfcAdapter.ReaderCallback? = null
            private set
        var lastFlags: Int = 0
            private set

        override fun enableReaderMode(
            activity: Activity,
            callback: NfcAdapter.ReaderCallback,
            flags: Int,
            extras: Bundle?,
        ) {
            enableCount++
            lastEnabledActivity = activity
            lastCallback = callback
            lastFlags = flags
        }

        override fun disableReaderMode(activity: Activity) {
            disableCount++
            lastDisabledActivity = activity
        }
    }
}
