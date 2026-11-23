package com.garan.drytoast.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChallengeResponseVerifierTest {

    @Test
    fun `timeBucket rounds epochMillis down to 5 minute intervals`() {
        assertEquals(0L, ChallengeResponseVerifier.timeBucket(0L))
        assertEquals(0L, ChallengeResponseVerifier.timeBucket(299_999L))
        assertEquals(1L, ChallengeResponseVerifier.timeBucket(300_000L))
        assertEquals(5_985_165L, ChallengeResponseVerifier.timeBucket(1_795_549_500_000L))
    }

    @Test
    fun `generateChallenge formats 3 digits and avoids repeating previous challenge`() {
        val fixedZeroRandom = object : Random() {
            private var callCount = 0
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(until: Int): Int {
                callCount++
                return if (callCount == 1) 42 else 43
            }
        }
        val first = ChallengeResponseVerifier.generateChallenge(previousChallenge = null, random = fixedZeroRandom)
        assertEquals("042", first)

        // When random returns 42 again on first try, generateChallenge must advance or re-sample
        val repeatRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(until: Int): Int = 42
        }
        val rotated = ChallengeResponseVerifier.generateChallenge(previousChallenge = "042", random = repeatRandom)
        assertNotEquals("042", rotated)
        assertEquals(3, rotated.length)
        assertTrue(rotated.all { it.isDigit() })
    }

    @Test
    fun `computeCodeForBucket produces deterministic 6 digit code and differs by domain and challenge`() {
        val bucket = 5_985_165L
        val codeDisable = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = "492",
            bucket = bucket,
        )
        val codeSettingsLock = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.SETTINGS_LOCK,
            challenge = "492",
            bucket = bucket,
        )
        val codeDifferentChallenge = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = "493",
            bucket = bucket,
        )

        assertEquals(6, codeDisable.length)
        assertTrue(codeDisable.all { it.isDigit() })
        assertNotEquals(codeDisable, codeSettingsLock)
        assertNotEquals(codeDisable, codeDifferentChallenge)
    }

    @Test
    fun `verify accepts current and adjacent 5 minute buckets and rejects outside window`() {
        val baseEpochMillis = 1_795_549_500_000L
        val currentBucket = ChallengeResponseVerifier.timeBucket(baseEpochMillis)
        val challenge = "492"

        val currentCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = challenge,
            bucket = currentBucket,
        )
        val previousBucketCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = challenge,
            bucket = currentBucket - 1,
        )
        val nextBucketCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = challenge,
            bucket = currentBucket + 1,
        )
        val expiredCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.HARDENED_DISABLE,
            challenge = challenge,
            bucket = currentBucket - 2,
        )

        assertTrue(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.HARDENED_DISABLE,
                challenge = challenge,
                responseCode = currentCode,
                nowEpochMillis = baseEpochMillis,
            ),
        )
        assertTrue(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.HARDENED_DISABLE,
                challenge = challenge,
                responseCode = previousBucketCode,
                nowEpochMillis = baseEpochMillis,
            ),
        )
        assertTrue(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.HARDENED_DISABLE,
                challenge = challenge,
                responseCode = nextBucketCode,
                nowEpochMillis = baseEpochMillis,
            ),
        )
        assertFalse(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.HARDENED_DISABLE,
                challenge = challenge,
                responseCode = expiredCode,
                nowEpochMillis = baseEpochMillis,
            ),
        )
    }

    @Test
    fun `verify rejects cross-domain codes and malformed inputs`() {
        val nowEpochMillis = 1_795_549_500_000L
        val bucket = ChallengeResponseVerifier.timeBucket(nowEpochMillis)
        val lockCode = ChallengeResponseVerifier.computeCodeForBucket(
            domain = VerificationDomain.SETTINGS_LOCK,
            challenge = "128",
            bucket = bucket,
        )

        // Valid for SETTINGS_LOCK
        assertTrue(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.SETTINGS_LOCK,
                challenge = "128",
                responseCode = lockCode,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        // Rejected for HARDENED_DISABLE
        assertFalse(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.HARDENED_DISABLE,
                challenge = "128",
                responseCode = lockCode,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        // Rejected when challenge or responseCode has wrong length or non-digits
        assertFalse(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.SETTINGS_LOCK,
                challenge = "12",
                responseCode = lockCode,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertFalse(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.SETTINGS_LOCK,
                challenge = "128",
                responseCode = "12345",
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertFalse(
            ChallengeResponseVerifier.verify(
                domain = VerificationDomain.SETTINGS_LOCK,
                challenge = "12a",
                responseCode = lockCode,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }
}
