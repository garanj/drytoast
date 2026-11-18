package com.garan.threshold.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class NfcTriggerTest {

    @Test
    fun normalizeUid_andFormatUidBytes_normalizesHexBytePairsConsistently() {
        assertThat(NfcTagEntry.normalizeUid("04a23f8b1c9080"))
            .isEqualTo("04:A2:3F:8B:1C:90:80")
        assertThat(NfcTagEntry.normalizeUid(" 04-a2-3f-8b "))
            .isEqualTo("04:A2:3F:8B")

        val bytes = byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0x8B.toByte())
        assertThat(NfcTagEntry.formatUidBytes(bytes)).isEqualTo("04:A2:3F:8B")
    }

    @Test
    fun nfcTrigger_matchesTagUid_isCaseAndDelimiterInsensitive() {
        val trigger = NfcTrigger(
            tags = listOf(
                NfcTagEntry(uid = "04:A2:3F:8B", label = "Desk Sticker"),
                NfcTagEntry(uid = "04:B1:22:99", label = "Keycard"),
            ),
            mode = NfcTriggerMode.TIMED_SESSION,
            durationMinutes = 30,
        )

        assertThat(trigger.matchesTagUid("04a23f8b")).isTrue()
        assertThat(trigger.matchesTagUid("04:B1:22:99")).isTrue()
        assertThat(trigger.matchesTagUid("04:FF:FF:FF")).isFalse()
        assertThat(trigger.matchesTagUid("   ")).isFalse()
    }

    @Test
    fun formatDuration_formatsPresetsCorrectlyAndRejects8h() {
        assertThat(NfcTrigger.DURATION_PRESETS_MINUTES).containsExactly(15, 30, 60, 120, 240).inOrder()
        assertThat(NfcTrigger.formatDuration(15)).isEqualTo("15m")
        assertThat(NfcTrigger.formatDuration(30)).isEqualTo("30m")
        assertThat(NfcTrigger.formatDuration(60)).isEqualTo("1h")
        assertThat(NfcTrigger.formatDuration(120)).isEqualTo("2h")
        assertThat(NfcTrigger.formatDuration(240)).isEqualTo("4h")

        assertThrows(IllegalArgumentException::class.java) {
            NfcTrigger(durationMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcTrigger(durationMinutes = 480)
        }
    }

    @Test
    fun profileTriggerNfc_requiresNfcHardwareCapability() {
        val nfcProfileTrigger = ProfileTrigger.Nfc(
            NfcTrigger(tags = listOf(NfcTagEntry("04:A2:3F:8B"))),
        )
        assertThat(nfcProfileTrigger.requiredCapabilities)
            .containsExactly(ProfileCapability.NFC_HARDWARE_ENABLED)
    }

    @Test
    fun nfcTagRegistrationResult_preservesNormalizedUidAndMetadataAcrossVariants() {
        val success: NfcTagRegistrationResult = NfcTagRegistrationResult.Success("04:A2:3F:8B")
        val unsupported: NfcTagRegistrationResult = NfcTagRegistrationResult.UnsupportedCard("04:B1:22:99")
        val readOnly: NfcTagRegistrationResult = NfcTagRegistrationResult.ReadOnlyTag("04:C1:D2:E3")
        val small: NfcTagRegistrationResult = NfcTagRegistrationResult.InsufficientCapacity(
            uid = "04:D4:E5:F6",
            maxSizeBytes = 8,
            requiredBytes = 19,
        )
        val interrupted: NfcTagRegistrationResult = NfcTagRegistrationResult.WriteInterrupted("04:11:22:33")

        assertThat(success.uid).isEqualTo("04:A2:3F:8B")
        assertThat(unsupported.uid).isEqualTo("04:B1:22:99")
        assertThat(readOnly.uid).isEqualTo("04:C1:D2:E3")
        assertThat((small as NfcTagRegistrationResult.InsufficientCapacity).maxSizeBytes).isEqualTo(8)
        assertThat(small.requiredBytes).isEqualTo(19)
        assertThat(interrupted.uid).isEqualTo("04:11:22:33")
        assertThat(NfcTagEntry.THRESHOLD_NDEF_SCHEME).isEqualTo("threshold")
        assertThat(NfcTagEntry.THRESHOLD_NDEF_HOST).isEqualTo("tag")
        assertThat(NfcTagEntry.THRESHOLD_NDEF_URI).isEqualTo("threshold://tag")
    }
}
