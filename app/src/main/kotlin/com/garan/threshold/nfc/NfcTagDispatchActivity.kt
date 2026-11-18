package com.garan.threshold.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import com.garan.threshold.ThresholdAppContainer

/**
 * Zero-UI (`Theme.NoDisplay`) activity registered for `android.nfc.action.NDEF_DISCOVERED`
 * (`threshold://tag`).
 *
 * When a provisioned Threshold NFC tag is tapped anywhere in the OS (on the home screen or while
 * another app is open), Android routes the tag intent here without needing `enableReaderMode()`
 * on `MainActivity`—preserving Google Wallet Host Card Emulation (HCE) and standard NDEF URL/key
 * tag discovery on the home screen.
 */
class NfcTagDispatchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val readerSource = ThresholdAppContainer.from(this).nfcReaderSource
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag?.id != null) {
            readerSource.onTagIdDiscovered(tag.id)
            return
        }
        val testUid = intent.getStringExtra(EXTRA_TEST_TAG_UID)
        if (!testUid.isNullOrBlank()) {
            readerSource.dispatchScannedUid(testUid)
        }
    }

    companion object {
        const val EXTRA_TEST_TAG_UID = "com.garan.threshold.extra.TEST_TAG_UID"
    }
}
