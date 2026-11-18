package com.garan.threshold.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.garan.threshold.core.model.NfcTagEntry
import com.garan.threshold.core.model.NfcTagRegistrationResult
import com.garan.threshold.core.port.HapticEngine
import com.garan.threshold.core.port.NfcReaderSource
import com.garan.threshold.core.port.NoOpHapticEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thin abstraction over [NfcAdapter.enableReaderMode] and [NfcAdapter.disableReaderMode] so
 * foreground reader registration, flags, and [NfcAdapter.ReaderCallback] tag byte extraction can
 * be verified in unit/Robolectric tests without physical NFC controller hardware.
 */
interface NfcAdapterGateway {
    val isEnabled: Boolean

    fun enableReaderMode(activity: Activity, callback: NfcAdapter.ReaderCallback, flags: Int, extras: Bundle?)

    fun disableReaderMode(activity: Activity)
}

/**
 * Production [NfcAdapterGateway] backed by the system [NfcAdapter].
 */
class SystemNfcAdapterGateway(context: Context) : NfcAdapterGateway {
    private val appContext: Context = context.applicationContext ?: context
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(appContext) }

    override val isEnabled: Boolean
        get() = nfcAdapter?.isEnabled == true

    override fun enableReaderMode(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        flags: Int,
        extras: Bundle?,
    ) {
        nfcAdapter?.enableReaderMode(activity, callback, flags, extras)
    }

    override fun disableReaderMode(activity: Activity) {
        nfcAdapter?.disableReaderMode(activity)
    }
}

/**
 * Abstraction for inspecting and formatting/writing a physical [Tag] with Threshold's
 * NDEF URI record (`threshold://tag`) during tag registration.
 */
interface NfcTagProvisioner {
    fun provisionTag(tag: Tag, normalizedUid: String): NfcTagRegistrationResult
}

/**
 * Production [NfcTagProvisioner] backed by Android's [Ndef] and [NdefFormatable] technologies.
 */
class SystemNfcTagProvisioner(private val packageName: String) : NfcTagProvisioner {

    override fun provisionTag(tag: Tag, normalizedUid: String): NfcTagRegistrationResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            if (hasThresholdNdefRecord(ndef.cachedNdefMessage)) {
                return NfcTagRegistrationResult.Success(normalizedUid)
            }
            if (!ndef.isWritable) {
                return NfcTagRegistrationResult.ReadOnlyTag(normalizedUid)
            }
            val fullMessage = buildFullNdefMessage(packageName)
            val compactMessage = buildCompactNdefMessage()
            val fullBytes = fullMessage.toByteArray().size
            val compactBytes = compactMessage.toByteArray().size
            val messageToWrite = when {
                ndef.maxSize >= fullBytes -> fullMessage
                ndef.maxSize >= compactBytes -> compactMessage
                else -> {
                    return NfcTagRegistrationResult.InsufficientCapacity(
                        uid = normalizedUid,
                        maxSizeBytes = ndef.maxSize,
                        requiredBytes = compactBytes,
                    )
                }
            }
            return try {
                ndef.connect()
                ndef.writeNdefMessage(messageToWrite)
                NfcTagRegistrationResult.Success(normalizedUid)
            } catch (_: Exception) {
                NfcTagRegistrationResult.WriteInterrupted(normalizedUid)
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            val fullMessage = buildFullNdefMessage(packageName)
            val compactMessage = buildCompactNdefMessage()
            return try {
                formatable.connect()
                try {
                    formatable.format(fullMessage)
                } catch (_: Exception) {
                    formatable.format(compactMessage)
                }
                NfcTagRegistrationResult.Success(normalizedUid)
            } catch (_: Exception) {
                NfcTagRegistrationResult.WriteInterrupted(normalizedUid)
            } finally {
                runCatching { formatable.close() }
            }
        }

        return NfcTagRegistrationResult.UnsupportedCard(normalizedUid)
    }

    companion object {
        fun buildCompactNdefMessage(): NdefMessage = NdefMessage(
            arrayOf(NdefRecord.createUri(NfcTagEntry.THRESHOLD_NDEF_URI)),
        )

        fun buildFullNdefMessage(packageName: String): NdefMessage = NdefMessage(
            arrayOf(
                NdefRecord.createUri(NfcTagEntry.THRESHOLD_NDEF_URI),
                NdefRecord.createApplicationRecord(packageName),
            ),
        )

        fun hasThresholdNdefRecord(message: NdefMessage?): Boolean {
            val records = message?.records ?: return false
            return records.any { record ->
                runCatching { record.toUri()?.toString() }
                    .getOrNull()
                    ?.startsWith(NfcTagEntry.THRESHOLD_NDEF_URI) == true
            }
        }
    }
}

/**
 * Android implementation of [NfcReaderSource].
 *
 * - **Everyday Home / Background Operation:** Does NOT hold [NfcAdapter.enableReaderMode] on the
 *   home screen, preserving Host Card Emulation (Google Wallet tap-to-pay) and standard OS NFC tag
 *   discovery (URLs, YubiKeys). Provisioned Threshold tags (`threshold://tag`) are routed by the OS
 *   via `ACTION_NDEF_DISCOVERED` to [NfcTagDispatchActivity], which calls [onTagIdDiscovered].
 * - **Tag Registration Sheet Operation:** While an NFC Trigger Editor registration sheet is open
 *   ([hasActiveRegistrationListener] is `true`) and the host [Activity] is resumed, enables
 *   [NfcAdapter.enableReaderMode] with [REGISTRATION_READER_FLAGS] (omitting
 *   `FLAG_READER_SKIP_NDEF_CHECK` so `Ndef` and `NdefFormatable` technologies are populated),
 *   provisions the tag via [NfcTagProvisioner], and reports [NfcTagRegistrationResult].
 *
 * Never retains an [Activity] reference in fields.
 */
class AndroidNfcReaderSource(
    context: Context,
    private val hapticEngine: HapticEngine = NoOpHapticEngine,
    private val adapterGateway: NfcAdapterGateway = SystemNfcAdapterGateway(context),
    private val tagProvisioner: NfcTagProvisioner = SystemNfcTagProvisioner(
        context.applicationContext?.packageName ?: context.packageName,
    ),
    private val mainThreadPoster: (() -> Unit) -> Unit = { block ->
        val mainLooper = Looper.getMainLooper()
        if (mainLooper == null || Looper.myLooper() == mainLooper) {
            block()
        } else {
            Handler(mainLooper).post(block)
        }
    },
) : NfcReaderSource {

    private val _tagScans = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val tagScans: SharedFlow<String> = _tagScans.asSharedFlow()

    @Volatile
    private var isActivityResumed: Boolean = false

    @Volatile
    private var isReaderModeActive: Boolean = false

    /**
     * Optional string-UID interceptor registered while the NFC Trigger Editor sheet is open on screen.
     */
    @Volatile
    var activeRegistrationListener: ((String) -> Unit)? = null

    /**
     * Optional full [NfcTagRegistrationResult] interceptor registered while the NFC Trigger Editor
     * sheet is open on screen so non-NDEF or write-locked cards surface diagnostic feedback.
     */
    @Volatile
    var activeRegistrationResultListener: ((NfcTagRegistrationResult) -> Unit)? = null

    private val hasActiveRegistrationListener: Boolean
        get() = activeRegistrationListener != null || activeRegistrationResultListener != null

    private val readerCallback = NfcAdapter.ReaderCallback { tag: Tag? ->
        onTagDiscovered(tag)
    }

    internal fun onTagDiscovered(tag: Tag?) {
        val idBytes = tag?.id
        if (idBytes == null || idBytes.isEmpty()) return
        val formattedUid = NfcTagEntry.formatUidBytes(idBytes)
        if (formattedUid.isBlank()) return
        if (hasActiveRegistrationListener) {
            val result = tagProvisioner.provisionTag(tag, formattedUid)
            dispatchRegistrationResult(result)
        } else {
            dispatchScannedUid(formattedUid)
        }
    }

    /**
     * Formats raw hardware tag UID bytes and dispatches them.
     */
    internal fun onTagIdDiscovered(idBytes: ByteArray?) {
        if (idBytes == null || idBytes.isEmpty()) return
        val formattedUid = NfcTagEntry.formatUidBytes(idBytes)
        if (formattedUid.isNotBlank()) {
            dispatchScannedUid(formattedUid)
        }
    }

    /**
     * Called from `MainActivity.onResume()`. Only enables [NfcAdapter.enableReaderMode] if the
     * NFC Trigger Editor registration sheet is actively open; otherwise leaves HCE (Google Wallet)
     * and OS NFC tag discovery unblocked.
     */
    fun onActivityResumed(activity: Activity) {
        isActivityResumed = true
        if (hasActiveRegistrationListener) {
            enableForegroundReader(activity, REGISTRATION_READER_FLAGS)
        }
    }

    /**
     * Called from `MainActivity.onPause()`. Disables foreground reader mode if it was active.
     */
    fun onActivityPaused(activity: Activity) {
        isActivityResumed = false
        if (isReaderModeActive) {
            disableForegroundReader(activity)
        }
    }

    /**
     * Registers or clears the active [NfcTagRegistrationResult] listener from the NFC Trigger Editor
     * sheet and immediately toggles [NfcAdapter.enableReaderMode] on [activity] if resumed.
     */
    fun setRegistrationResultListener(activity: Activity, listener: ((NfcTagRegistrationResult) -> Unit)?) {
        activeRegistrationResultListener = listener
        syncReaderModeForRegistrationState(activity)
    }

    /**
     * Registers or clears the legacy UID-only listener and syncs [NfcAdapter.enableReaderMode].
     */
    fun setRegistrationListener(activity: Activity, listener: ((String) -> Unit)?) {
        activeRegistrationListener = listener
        syncReaderModeForRegistrationState(activity)
    }

    private fun syncReaderModeForRegistrationState(activity: Activity) {
        if (hasActiveRegistrationListener) {
            if (isActivityResumed && !isReaderModeActive) {
                enableForegroundReader(activity, REGISTRATION_READER_FLAGS)
            }
        } else if (isReaderModeActive) {
            disableForegroundReader(activity)
        }
    }

    /**
     * Enables foreground NFC reader mode for [activity].
     */
    fun enableForegroundReader(activity: Activity, flags: Int = REGISTRATION_READER_FLAGS) {
        if (!adapterGateway.isEnabled) return
        runCatching {
            adapterGateway.enableReaderMode(
                activity,
                readerCallback,
                flags,
                null,
            )
            isReaderModeActive = true
        }
    }

    /**
     * Disables foreground NFC reader mode when [activity] pauses or exits registration.
     */
    fun disableForegroundReader(activity: Activity) {
        isReaderModeActive = false
        runCatching {
            adapterGateway.disableReaderMode(activity)
        }
    }

    /**
     * Dispatches a [NfcTagRegistrationResult] onto the main thread with tactile feedback
     * ([HapticEngine.relaySnap] on [NfcTagRegistrationResult.Success],
     * [HapticEngine.resistanceDetent] on failure).
     */
    fun dispatchRegistrationResult(result: NfcTagRegistrationResult) {
        mainThreadPoster {
            val resultListener = activeRegistrationResultListener
            val uidListener = activeRegistrationListener
            if (resultListener != null || uidListener != null) {
                when (result) {
                    is NfcTagRegistrationResult.Success -> {
                        val normalized = NfcTagEntry.normalizeUid(result.uid)
                        if (normalized.isNotBlank()) {
                            hapticEngine.relaySnap()
                            resultListener?.invoke(NfcTagRegistrationResult.Success(normalized))
                            uidListener?.invoke(normalized)
                        }
                    }
                    is NfcTagRegistrationResult.UnsupportedCard,
                    is NfcTagRegistrationResult.ReadOnlyTag,
                    is NfcTagRegistrationResult.InsufficientCapacity,
                    is NfcTagRegistrationResult.WriteInterrupted,
                    -> {
                        hapticEngine.resistanceDetent()
                        resultListener?.invoke(result)
                    }
                }
            }
        }
    }

    /**
     * Dispatches a normalized hardware tag UID either to the active Trigger Editor registration
     * listener or to [tagScans]. Exposed for deterministic testing without physical NFC hardware.
     */
    fun dispatchScannedUid(rawUid: String) {
        val normalized = NfcTagEntry.normalizeUid(rawUid)
        if (normalized.isBlank()) return
        if (hasActiveRegistrationListener) {
            dispatchRegistrationResult(NfcTagRegistrationResult.Success(normalized))
        } else {
            _tagScans.tryEmit(normalized)
        }
    }

    companion object {
        /**
         * Reader mode flags used during NFC tag registration. Intentionally omits
         * `FLAG_READER_SKIP_NDEF_CHECK` so Android's NFC stack populates `Ndef` and
         * `NdefFormatable` technologies on discovered tags for NDEF provisioning.
         */
        const val REGISTRATION_READER_FLAGS: Int =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        const val READER_FLAGS: Int = REGISTRATION_READER_FLAGS
    }
}
