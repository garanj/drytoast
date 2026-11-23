package com.garan.drytoast.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.core.model.NfcTagEntry

/**
 * Zero-UI (`Theme.NoDisplay`) activity registered for `android.nfc.action.NDEF_DISCOVERED`
 * (`drytoast://tag`).
 *
 * When a provisioned Dry Toast NFC tag is tapped anywhere in the OS (on the home screen or while
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
        val container = DryToastAppContainer.from(this)
        val readerSource = container.nfcReaderSource
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val rawUid: String? = when {
            tag?.id != null && tag.id.isNotEmpty() -> NfcTagEntry.formatUidBytes(tag.id)
            else -> intent.getStringExtra(EXTRA_TEST_TAG_UID)
        }
        if (!rawUid.isNullOrBlank()) {
            if (readerSource.hasActiveRegistrationListener || readerSource.hasActiveScanCollectors) {
                readerSource.dispatchScannedUid(rawUid)
            } else {
                container.processBackgroundNfcTagScan(rawUid)
            }
        }
    }

    companion object {
        const val EXTRA_TEST_TAG_UID = "com.garan.drytoast.extra.TEST_TAG_UID"
    }
}
