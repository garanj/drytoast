package com.garan.drytoast.core.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Distinct administrative actions protected by the QR + focus-locked 3-digit challenge ceremony.
 */
enum class VerificationDomain(val wireId: String) {
    HARDENED_DISABLE("hardened-disable"),
    SETTINGS_LOCK("settings-lock"),
}

/**
 * Pure Kotlin challenge-response TOTP verifier combining:
 * 1. A 5-minute UTC time bucket (`epochMillis / 300_000L`),
 * 2. A [VerificationDomain] wire identifier (`hardened-disable` or `settings-lock`), and
 * 3. A 3-digit foreground challenge (`000`–`999`) that rotates immediately if Dry Toast loses focus.
 *
 * ### Companion Website JavaScript Implementation (Web Crypto API)
 * ```javascript
 * const SECRET = "drytoast-v1-shared-totp-key-9f4b2c8e1d7a6053c8b4e2f1a9d07635";
 * async function computeUnlockCode(domain, challenge, epochMillis = Date.now()) {
 *   const bucket = Math.floor(epochMillis / 300000);
 *   const msg = new TextEncoder().encode(`${bucket}:${domain}:${challenge}`);
 *   const keyData = new TextEncoder().encode(SECRET);
 *   const key = await crypto.subtle.importKey("raw", keyData, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
 *   const sig = await crypto.subtle.sign("HMAC", key, msg);
 *   const uint31 = new DataView(sig).getUint32(0, false) & 0x7fffffff;
 *   return String(uint31 % 1000000).padStart(6, "0");
 * }
 * ```
 */
object ChallengeResponseVerifier {

    const val BUCKET_DURATION_MILLIS: Long = 5L * 60L * 1_000L
    const val CHALLENGE_LENGTH: Int = 3
    const val RESPONSE_CODE_LENGTH: Int = 6
    const val SHARED_SECRET: String = "drytoast-v1-shared-totp-key-9f4b2c8e1d7a6053c8b4e2f1a9d07635"

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val CHALLENGE_MODULO = 1_000
    private const val RESPONSE_MODULO = 1_000_000

    fun timeBucket(epochMillis: Long): Long = epochMillis / BUCKET_DURATION_MILLIS

    /**
     * Generates a 3-digit zero-padded challenge (`"000"`..`"999"`), guaranteed not to equal
     * [previousChallenge] when rotating on focus loss.
     */
    fun generateChallenge(previousChallenge: String? = null, random: Random = Random.Default): String {
        val candidateInt = random.nextInt(CHALLENGE_MODULO)
        val candidate = formatThreeDigits(candidateInt)
        if (previousChallenge == null || candidate != previousChallenge) {
            return candidate
        }
        val offset = 1 + random.nextInt(CHALLENGE_MODULO - 1)
        return formatThreeDigits((candidateInt + offset) % CHALLENGE_MODULO)
    }

    /**
     * Computes the deterministic 6-digit response code for a specific 5-minute UTC [bucket].
     */
    fun computeCodeForBucket(
        domain: VerificationDomain,
        challenge: String,
        bucket: Long,
        secret: String = SHARED_SECRET,
    ): String {
        require(isValidChallenge(challenge)) { "Challenge must be exactly 3 digits" }
        val payload = "$bucket:${domain.wireId}:$challenge"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.encodeToByteArray(), HMAC_ALGORITHM))
        val digest = mac.doFinal(payload.encodeToByteArray())
        val truncatedInt =
            ((digest[0].toInt() and 0x7F) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
        return formatSixDigits(truncatedInt % RESPONSE_MODULO)
    }

    /**
     * Verifies [responseCode] against the current and adjacent (`-1`, `0`, `+1`) 5-minute UTC
     * time buckets to tolerate minor clock skew or bucket boundary rollover.
     */
    fun verify(
        domain: VerificationDomain,
        challenge: String,
        responseCode: String,
        nowEpochMillis: Long,
        secret: String = SHARED_SECRET,
    ): Boolean {
        if (!isValidChallenge(challenge) || !isValidResponseCode(responseCode)) {
            return false
        }
        val currentBucket = timeBucket(nowEpochMillis)
        for (delta in -1L..1L) {
            val expected = computeCodeForBucket(
                domain = domain,
                challenge = challenge,
                bucket = currentBucket + delta,
                secret = secret,
            )
            if (constantTimeEquals(expected, responseCode)) {
                return true
            }
        }
        return false
    }

    fun isValidChallenge(challenge: String): Boolean =
        challenge.length == CHALLENGE_LENGTH && challenge.all { it in '0'..'9' }

    fun isValidResponseCode(code: String): Boolean = code.length == RESPONSE_CODE_LENGTH && code.all { it in '0'..'9' }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun formatThreeDigits(value: Int): String =
        value.coerceIn(0, 999).toString().padStart(CHALLENGE_LENGTH, '0')

    private fun formatSixDigits(value: Int): String =
        value.coerceIn(0, 999_999).toString().padStart(RESPONSE_CODE_LENGTH, '0')
}
