package com.garan.drytoast.core.backup

import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.BaseProfileSettings
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.CustomProfile
import com.garan.drytoast.core.model.DayOfWeek
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.GeoTrigger
import com.garan.drytoast.core.model.GeoTriggerMode
import com.garan.drytoast.core.model.InstalledApp
import com.garan.drytoast.core.model.NfcTagEntry
import com.garan.drytoast.core.model.NfcTrigger
import com.garan.drytoast.core.model.NfcTriggerMode
import com.garan.drytoast.core.model.ProfileDefaultRule
import com.garan.drytoast.core.model.ProfileTrigger
import com.garan.drytoast.core.model.SavedTrigger
import com.garan.drytoast.core.model.ScheduleTrigger
import com.garan.drytoast.core.model.StagedSettingsSnapshot
import com.garan.drytoast.core.model.TimeOfDay
import com.garan.drytoast.core.model.TimeWindow
import com.garan.drytoast.core.model.TriggerRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Categorized failure reasons returned by [ConfigBackupCodec.decode].
 */
enum class ConfigImportError {
    /** Input string is empty or blank. */
    EMPTY_INPUT,

    /** Input exceeds the maximum permitted backup size. */
    INPUT_TOO_LARGE,

    /** Input is not valid JSON or does not match the expected JSON structure. */
    INVALID_JSON,

    /** The `"format"` header is missing or does not equal `"drytoast-config"`. */
    UNSUPPORTED_FORMAT,

    /** The `"version"` header is missing or not supported by this version of Dry Toast. */
    UNSUPPORTED_VERSION,

    /** Plaintext envelope (`encrypted = false`) is missing the `"config"` object. */
    MISSING_CONFIG_PAYLOAD,

    /** Encrypted envelope (`encrypted = true`) is missing the `"crypto"` object. */
    MISSING_CRYPTO_PAYLOAD,

    /** Encrypted envelope specifies an unsupported KDF, cipher, or iteration count. */
    UNSUPPORTED_CRYPTO_PARAMETERS,

    /** Base64 decoding failed or salt/IV/ciphertext byte lengths are invalid. */
    CORRUPT_CRYPTO_DATA,

    /** AES-GCM authentication failed due to an incorrect passphrase or modified ciphertext/IV/salt. */
    INVALID_PASSPHRASE_OR_TAMPERED,

    /** Decoded configuration contains invalid enum names, out-of-range values, duplicates, or dangling references. */
    INVALID_CONFIG_VALUES,
}

/**
 * Outcome of decoding a Dry Toast JSON configuration backup via [ConfigBackupCodec.decode].
 */
sealed interface ConfigImportResult {
    /** Configuration decoded and validated cleanly. */
    data class Success(val snapshot: StagedSettingsSnapshot) : ConfigImportResult

    /** Valid encrypted backup envelope detected; caller must prompt for a passphrase and retry. */
    data object PassphraseRequired : ConfigImportResult

    /** Backup could not be decoded or validated. */
    data class Error(val reason: ConfigImportError, val detail: String) : ConfigImportResult
}

/**
 * Pure-Kotlin versioned JSON serializer and optional AES-256-GCM encryptor for Dry Toast
 * configuration backups ([StagedSettingsSnapshot]).
 *
 * Uses zero `android.*` imports and zero external cryptography dependencies:
 * - KDF: `PBKDF2WithHmacSHA256` with 16-byte random salt and 256-bit derived key.
 * - Cipher: `AES/GCM/NoPadding` with 12-byte random IV, 128-bit auth tag, and version-bound AAD.
 */
class ConfigBackupCodec(
    private val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    init {
        require(pbkdf2Iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "pbkdf2Iterations must be in $MIN_PBKDF2_ITERATIONS..$MAX_PBKDF2_ITERATIONS, was $pbkdf2Iterations"
        }
    }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Encodes [snapshot] into a versioned JSON backup string.
     *
     * When [passphrase] is `null` or empty, exports plaintext (`encrypted = false`).
     * When [passphrase] is non-empty, encrypts the serialized config JSON with AES-256-GCM
     * derived via PBKDF2-HMAC-SHA256 (`encrypted = true`).
     */
    fun encode(snapshot: StagedSettingsSnapshot, passphrase: String? = null): String {
        val configDto = snapshot.toDto()
        if (passphrase.isNullOrEmpty()) {
            val envelope = ConfigBackupEnvelopeDto(
                format = FORMAT_ID,
                version = CURRENT_VERSION,
                encrypted = false,
                config = configDto,
            )
            return json.encodeToString(ConfigBackupEnvelopeDto.serializer(), envelope)
        }

        val plaintextBytes = json.encodeToString(ConfigPayloadDto.serializer(), configDto)
            .toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val ciphertext = encryptAesGcm(
            plaintext = plaintextBytes,
            passphrase = passphrase,
            salt = salt,
            iv = iv,
            iterations = pbkdf2Iterations,
        )

        val encoder = Base64.getEncoder()
        val envelope = ConfigBackupEnvelopeDto(
            format = FORMAT_ID,
            version = CURRENT_VERSION,
            encrypted = true,
            crypto = CryptoEnvelopeDto(
                kdf = KDF_ALGORITHM,
                iterations = pbkdf2Iterations,
                cipher = CIPHER_TRANSFORMATION,
                salt = encoder.encodeToString(salt),
                iv = encoder.encodeToString(iv),
                ciphertext = encoder.encodeToString(ciphertext),
            ),
        )
        return json.encodeToString(ConfigBackupEnvelopeDto.serializer(), envelope)
    }

    /**
     * Decodes and validates a Dry Toast JSON backup string.
     *
     * Returns [ConfigImportResult.PassphraseRequired] when the envelope is valid and encrypted
     * (`encrypted = true`) but [passphrase] is `null` or empty.
     */
    fun decode(rawJson: String, passphrase: String? = null): ConfigImportResult {
        if (rawJson.isBlank()) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.EMPTY_INPUT,
                detail = "Configuration file is empty.",
            )
        }
        if (rawJson.length > MAX_INPUT_CHARS) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.INPUT_TOO_LARGE,
                detail = "Configuration file exceeds maximum size (${MAX_INPUT_CHARS / 1024} KB).",
            )
        }

        val envelope = try {
            json.decodeFromString(ConfigBackupEnvelopeDto.serializer(), rawJson)
        } catch (e: SerializationException) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.INVALID_JSON,
                detail = "Malformed JSON: ${e.message ?: "unexpected syntax"}",
            )
        } catch (e: IllegalArgumentException) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.INVALID_JSON,
                detail = "Malformed JSON: ${e.message ?: "invalid structure"}",
            )
        }

        if (envelope.format != FORMAT_ID) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.UNSUPPORTED_FORMAT,
                detail = "Unsupported format '${envelope.format ?: "<missing>"}'. Expected '$FORMAT_ID'.",
            )
        }
        val version = envelope.version
        if (version == null || version != CURRENT_VERSION) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.UNSUPPORTED_VERSION,
                detail = "Unsupported backup version '${version ?: "<missing>"}'. Expected $CURRENT_VERSION.",
            )
        }

        val configDto: ConfigPayloadDto = if (envelope.encrypted) {
            val crypto = envelope.crypto ?: return ConfigImportResult.Error(
                reason = ConfigImportError.MISSING_CRYPTO_PAYLOAD,
                detail = "Encrypted backup is missing the 'crypto' block.",
            )
            val cryptoValidationError = validateCryptoParams(crypto)
            if (cryptoValidationError != null) return cryptoValidationError

            if (passphrase.isNullOrEmpty()) {
                return ConfigImportResult.PassphraseRequired
            }

            val salt = decodeBase64(crypto.salt, "salt")
                ?: return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "Invalid Base64 in 'crypto.salt'.",
                )
            val iv = decodeBase64(crypto.iv, "iv")
                ?: return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "Invalid Base64 in 'crypto.iv'.",
                )
            val ciphertext = decodeBase64(crypto.ciphertext, "ciphertext")
                ?: return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "Invalid Base64 in 'crypto.ciphertext'.",
                )

            if (salt.size < MIN_SALT_LENGTH_BYTES) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "Salt must be at least $MIN_SALT_LENGTH_BYTES bytes, was ${salt.size}.",
                )
            }
            if (iv.size != IV_LENGTH_BYTES) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "IV must be exactly $IV_LENGTH_BYTES bytes, was ${iv.size}.",
                )
            }
            if (ciphertext.size < GCM_TAG_LENGTH_BYTES) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.CORRUPT_CRYPTO_DATA,
                    detail = "Ciphertext is truncated (fewer than $GCM_TAG_LENGTH_BYTES auth tag bytes).",
                )
            }

            val decryptedBytes = try {
                decryptAesGcm(
                    ciphertext = ciphertext,
                    passphrase = passphrase,
                    salt = salt,
                    iv = iv,
                    iterations = crypto.iterations,
                )
            } catch (_: AEADBadTagException) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED,
                    detail = "Incorrect passphrase or backup file has been tampered with.",
                )
            } catch (e: GeneralSecurityException) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED,
                    detail = "Decryption failed: ${e.message ?: "security error"}",
                )
            }

            val decryptedText = decryptedBytes.toString(Charsets.UTF_8)
            try {
                json.decodeFromString(ConfigPayloadDto.serializer(), decryptedText)
            } catch (e: SerializationException) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.INVALID_JSON,
                    detail = "Decrypted payload is not valid configuration JSON: ${e.message ?: "syntax error"}",
                )
            } catch (e: IllegalArgumentException) {
                return ConfigImportResult.Error(
                    reason = ConfigImportError.INVALID_JSON,
                    detail = "Decrypted payload is not valid configuration JSON: ${e.message ?: "invalid structure"}",
                )
            }
        } else {
            envelope.config ?: return ConfigImportResult.Error(
                reason = ConfigImportError.MISSING_CONFIG_PAYLOAD,
                detail = "Unencrypted backup is missing the 'config' block.",
            )
        }

        return try {
            ConfigImportResult.Success(configDto.toDomain())
        } catch (e: ConfigValidationException) {
            ConfigImportResult.Error(
                reason = ConfigImportError.INVALID_CONFIG_VALUES,
                detail = e.message ?: "Invalid configuration values.",
            )
        } catch (e: IllegalArgumentException) {
            ConfigImportResult.Error(
                reason = ConfigImportError.INVALID_CONFIG_VALUES,
                detail = e.message ?: "Invalid configuration values.",
            )
        }
    }

    private fun validateCryptoParams(crypto: CryptoEnvelopeDto): ConfigImportResult.Error? {
        if (crypto.kdf != KDF_ALGORITHM) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS,
                detail = "Unsupported KDF '${crypto.kdf}'. Expected '$KDF_ALGORITHM'.",
            )
        }
        if (crypto.cipher != CIPHER_TRANSFORMATION) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS,
                detail = "Unsupported cipher '${crypto.cipher}'. Expected '$CIPHER_TRANSFORMATION'.",
            )
        }
        if (crypto.iterations !in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            return ConfigImportResult.Error(
                reason = ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS,
                detail = "Unsupported PBKDF2 iterations ${crypto.iterations} " +
                    "(allowed: $MIN_PBKDF2_ITERATIONS..$MAX_PBKDF2_ITERATIONS).",
            )
        }
        return null
    }

    private fun decodeBase64(value: String, @Suppress("UNUSED_PARAMETER") fieldName: String): ByteArray? {
        if (value.isBlank()) return null
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun deriveKeyBytes(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encryptAesGcm(
        plaintext: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int,
    ): ByteArray {
        val keyBytes = deriveKeyBytes(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            cipher.updateAAD(AAD_BYTES)
            cipher.doFinal(plaintext)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decryptAesGcm(
        ciphertext: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int,
    ): ByteArray {
        val keyBytes = deriveKeyBytes(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            cipher.updateAAD(AAD_BYTES)
            cipher.doFinal(ciphertext)
        } finally {
            keyBytes.fill(0)
        }
    }

    companion object {
        const val FORMAT_ID = "drytoast-config"
        const val CURRENT_VERSION = 1
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_PBKDF2_ITERATIONS = 210_000
        const val MIN_PBKDF2_ITERATIONS = 1_000
        const val MAX_PBKDF2_ITERATIONS = 1_000_000
        const val SALT_LENGTH_BYTES = 16
        const val MIN_SALT_LENGTH_BYTES = 16
        const val IV_LENGTH_BYTES = 12
        const val KEY_LENGTH_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        const val MAX_INPUT_CHARS = 2 * 1024 * 1024

        private val AAD_BYTES = "$FORMAT_ID:v$CURRENT_VERSION".toByteArray(Charsets.UTF_8)
    }
}

private class ConfigValidationException(message: String) : IllegalArgumentException(message)

@Serializable
private data class ConfigBackupEnvelopeDto(
    val format: String? = null,
    val version: Int? = null,
    val encrypted: Boolean = false,
    val config: ConfigPayloadDto? = null,
    val crypto: CryptoEnvelopeDto? = null,
)

@Serializable
private data class CryptoEnvelopeDto(
    val kdf: String = "",
    val iterations: Int = 0,
    val cipher: String = "",
    val salt: String = "",
    val iv: String = "",
    val ciphertext: String = "",
)

@Serializable
private data class ConfigPayloadDto(
    val general: GeneralSettingsDto = GeneralSettingsDto(),
    val baseProfile: BaseProfileDto = BaseProfileDto(),
    val customProfiles: List<CustomProfileDto> = emptyList(),
    val savedTriggers: List<SavedTriggerDto> = emptyList(),
)

@Serializable
private data class GeneralSettingsDto(
    val showUnlocks: Boolean = true,
    val showRest: Boolean = true,
    val showDialerIcon: Boolean = true,
    val requireFullTypingOfGuardedApps: Boolean = false,
    val requireRebootForSettingsChange: Boolean = false,
    val guardedSessionGraceMinutes: Int = GeneralSettings.DEFAULT_GUARDED_SESSION_GRACE_MINUTES,
    val blockNewAppInstalls: Boolean = false,
    val hardenedOwnerName: String = GeneralSettings.DEFAULT_HARDENED_OWNER_NAME,
    val hardenedLockScreenInfo: String = "",
    val breakGlassTagUid: String? = null,
    val breakGlassDurationMinutes: Int = GeneralSettings.DEFAULT_BREAK_GLASS_DURATION_MINUTES,
)

@Serializable
private data class BaseProfileDto(
    val defaultFriction: String = FrictionChoice.TYPED_INTENTION.name,
    val appAssignments: Map<String, AppAssignmentDto> = emptyMap(),
    val quickActions: List<QuickActionDto> = emptyList(),
)

@Serializable
private data class QuickActionDto(val component: String, val label: String)

@Serializable
private data class AppAssignmentDto(val type: String, val frictionOverride: String? = null)

@Serializable
private data class CustomProfileDto(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val trigger: ProfileTriggerDto? = null,
    val activationRules: List<List<String>> = emptyList(),
    val appAssignments: Map<String, AppAssignmentDto> = emptyMap(),
    val defaultRule: String = ProfileDefaultRule.FALLTHROUGH.name,
    val frictionOverride: String? = null,
    val colorIndex: Int = 0,
)

@Serializable
private data class SavedTriggerDto(val id: String, val name: String, val trigger: ProfileTriggerDto)

@Serializable
private data class ProfileTriggerDto(
    val type: String,
    val dailyWindows: Map<String, TimeWindowDto>? = null,
    val sameScheduleEveryDay: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val geoMode: String? = null,
    val tags: List<NfcTagDto>? = null,
    val nfcMode: String? = null,
    val durationMinutes: Int? = null,
    val maxDailyUses: Int? = null,
)

@Serializable
private data class TimeWindowDto(val startHour: Int, val startMinute: Int, val endHour: Int, val endMinute: Int)

@Serializable
private data class NfcTagDto(val uid: String, val label: String = "")

private fun StagedSettingsSnapshot.toDto(): ConfigPayloadDto = ConfigPayloadDto(
    general = GeneralSettingsDto(
        showUnlocks = general.showUnlocks,
        showRest = general.showRest,
        showDialerIcon = general.showDialerIcon,
        requireFullTypingOfGuardedApps = general.requireFullTypingOfGuardedApps,
        requireRebootForSettingsChange = general.requireRebootForSettingsChange,
        guardedSessionGraceMinutes = general.guardedSessionGraceMinutes,
        blockNewAppInstalls = general.blockNewAppInstalls,
        hardenedOwnerName = general.effectiveHardenedOwnerName(),
        hardenedLockScreenInfo = general.hardenedLockScreenInfo.trim(),
        breakGlassTagUid = general.normalizedBreakGlassTagUid(),
        breakGlassDurationMinutes = general.breakGlassDurationMinutes,
    ),
    baseProfile = BaseProfileDto(
        defaultFriction = baseProfile.defaultFriction.name,
        appAssignments = baseProfile.appAssignments.entries.associate { (key, value) ->
            key.flattenToString() to value.toDto()
        },
        quickActions = baseProfile.quickActions.map { app ->
            QuickActionDto(component = app.component.flattenToString(), label = app.label)
        },
    ),
    customProfiles = customProfiles.map { profile ->
        CustomProfileDto(
            id = profile.id,
            name = profile.name,
            enabled = profile.enabled,
            priority = profile.priority,
            trigger = profile.trigger?.toDto(),
            activationRules = profile.activationRules.map { it.triggerIds },
            appAssignments = profile.appAssignments.entries.associate { (key, value) ->
                key.flattenToString() to value.toDto()
            },
            defaultRule = profile.defaultRule.name,
            frictionOverride = profile.frictionOverride?.name,
            colorIndex = profile.colorIndex,
        )
    },
    savedTriggers = savedTriggers.map { saved ->
        SavedTriggerDto(
            id = saved.id,
            name = saved.name,
            trigger = saved.trigger.toDto(),
        )
    },
)

private fun AppAssignment.toDto(): AppAssignmentDto = when (this) {
    is AppAssignment.Standard -> AppAssignmentDto(type = "STANDARD")
    is AppAssignment.Blocked -> AppAssignmentDto(type = "BLOCKED")
    is AppAssignment.Guarded -> AppAssignmentDto(
        type = "GUARDED",
        frictionOverride = frictionOverride?.name,
    )
}

private fun ProfileTrigger.toDto(): ProfileTriggerDto = when (this) {
    is ProfileTrigger.Schedule -> ProfileTriggerDto(
        type = "SCHEDULE",
        dailyWindows = schedule.dailyWindows.entries.associate { (day, window) ->
            day.name to TimeWindowDto(
                startHour = window.startTime.hour,
                startMinute = window.startTime.minute,
                endHour = window.endTime.hour,
                endMinute = window.endTime.minute,
            )
        },
        sameScheduleEveryDay = schedule.sameScheduleEveryDay,
    )
    is ProfileTrigger.Geo -> ProfileTriggerDto(
        type = "GEO",
        latitude = geo.latitude,
        longitude = geo.longitude,
        radiusMeters = geo.radiusMeters,
        geoMode = geo.mode.name,
    )
    is ProfileTrigger.Nfc -> ProfileTriggerDto(
        type = "NFC",
        tags = nfc.tags.map { NfcTagDto(uid = it.normalizedUid, label = it.label) },
        nfcMode = nfc.mode.name,
        durationMinutes = nfc.durationMinutes,
        maxDailyUses = nfc.maxDailyUses,
    )
}

private fun ConfigPayloadDto.toDomain(): StagedSettingsSnapshot {
    val domainGeneral = general.toDomain()
    val domainBase = baseProfile.toDomain()

    val seenTriggerIds = hashSetOf<String>()
    val domainTriggers = savedTriggers.map { dto ->
        if (!seenTriggerIds.add(dto.id)) {
            throw ConfigValidationException("Duplicate saved trigger ID '${dto.id}'.")
        }
        SavedTrigger(
            id = dto.id,
            name = dto.name,
            trigger = dto.trigger.toDomain("savedTrigger '${dto.id}'"),
        )
    }

    val seenProfileIds = hashSetOf<String>()
    val domainProfiles = customProfiles.map { dto ->
        if (!seenProfileIds.add(dto.id)) {
            throw ConfigValidationException("Duplicate custom profile ID '${dto.id}'.")
        }
        val rules = dto.activationRules.mapIndexed { ruleIdx, triggerIds ->
            if (triggerIds.isEmpty()) {
                throw ConfigValidationException(
                    "Profile '${dto.id}' activationRules[$ruleIdx] must not be empty.",
                )
            }
            for (triggerId in triggerIds) {
                if (triggerId !in seenTriggerIds) {
                    throw ConfigValidationException(
                        "Profile '${dto.id}' references unknown saved trigger ID '$triggerId'.",
                    )
                }
            }
            TriggerRule(triggerIds)
        }
        val assignments = parseAssignments(dto.appAssignments, "profile '${dto.id}'")
        val defaultRule = parseEnum<ProfileDefaultRule>(
            raw = dto.defaultRule,
            field = "profile '${dto.id}'.defaultRule",
        )
        val frictionOverride = dto.frictionOverride?.let {
            parseEnum<FrictionChoice>(it, "profile '${dto.id}'.frictionOverride")
        }
        CustomProfile(
            id = dto.id,
            name = dto.name,
            enabled = dto.enabled,
            priority = dto.priority,
            trigger = dto.trigger?.toDomain("profile '${dto.id}'.trigger"),
            activationRules = rules,
            appAssignments = assignments,
            defaultRule = defaultRule,
            frictionOverride = frictionOverride,
            colorIndex = dto.colorIndex,
        )
    }

    return StagedSettingsSnapshot(
        general = domainGeneral,
        baseProfile = domainBase,
        customProfiles = domainProfiles,
        savedTriggers = domainTriggers,
    )
}

private fun GeneralSettingsDto.toDomain(): GeneralSettings {
    if (guardedSessionGraceMinutes !in GeneralSettings.GUARDED_SESSION_GRACE_OPTIONS_MINUTES) {
        throw ConfigValidationException(
            "Invalid general.guardedSessionGraceMinutes: $guardedSessionGraceMinutes. " +
                "Allowed: ${GeneralSettings.GUARDED_SESSION_GRACE_OPTIONS_MINUTES}.",
        )
    }
    if (breakGlassDurationMinutes !in GeneralSettings.BREAK_GLASS_DURATION_OPTIONS_MINUTES) {
        throw ConfigValidationException(
            "Invalid general.breakGlassDurationMinutes: $breakGlassDurationMinutes. " +
                "Allowed: ${GeneralSettings.BREAK_GLASS_DURATION_OPTIONS_MINUTES}.",
        )
    }
    val normalizedBreakGlass = breakGlassTagUid?.let { raw ->
        NfcTagEntry.normalizeUid(raw).takeIf { it.isNotBlank() }
    }
    return GeneralSettings(
        showUnlocks = showUnlocks,
        showRest = showRest,
        showDialerIcon = showDialerIcon,
        requireFullTypingOfGuardedApps = requireFullTypingOfGuardedApps,
        requireRebootForSettingsChange = requireRebootForSettingsChange,
        guardedSessionGraceMinutes = guardedSessionGraceMinutes,
        blockNewAppInstalls = blockNewAppInstalls,
        hardenedOwnerName = hardenedOwnerName.trim().ifEmpty { GeneralSettings.DEFAULT_HARDENED_OWNER_NAME },
        hardenedLockScreenInfo = hardenedLockScreenInfo.trim(),
        breakGlassTagUid = normalizedBreakGlass,
        breakGlassDurationMinutes = breakGlassDurationMinutes,
    )
}

private fun BaseProfileDto.toDomain(): BaseProfileSettings {
    val friction = parseEnum<FrictionChoice>(defaultFriction, "baseProfile.defaultFriction")
    val assignments = parseAssignments(appAssignments, "baseProfile")
    val actions = quickActions.mapIndexed { idx, action ->
        if (action.label.isBlank()) {
            throw ConfigValidationException("baseProfile.quickActions[$idx].label must not be blank.")
        }
        InstalledApp(
            component = ComponentKey.fromFlatString(action.component),
            label = action.label,
        )
    }
    return BaseProfileSettings(
        defaultFriction = friction,
        appAssignments = assignments,
        quickActions = actions,
    )
}

private fun parseAssignments(raw: Map<String, AppAssignmentDto>, context: String): Map<ComponentKey, AppAssignment> =
    raw.entries.associate { (flatKey, dto) ->
        val component = ComponentKey.fromFlatString(flatKey)
        val assignment = when (dto.type.uppercase()) {
            "STANDARD" -> AppAssignment.Standard
            "BLOCKED" -> AppAssignment.Blocked
            "GUARDED" -> {
                val override = dto.frictionOverride?.let {
                    parseEnum<FrictionChoice>(it, "$context.appAssignments['$flatKey'].frictionOverride")
                }
                AppAssignment.Guarded(frictionOverride = override)
            }
            else -> throw ConfigValidationException(
                "Unknown app assignment type '${dto.type}' for '$flatKey' in $context. " +
                    "Expected STANDARD, GUARDED, or BLOCKED.",
            )
        }
        component to assignment
    }

private fun ProfileTriggerDto.toDomain(context: String): ProfileTrigger = when (type.uppercase()) {
    "SCHEDULE" -> {
        val windows = (dailyWindows ?: emptyMap()).entries.associate { (dayName, windowDto) ->
            val day = parseEnum<DayOfWeek>(dayName, "$context.dailyWindows key")
            day to TimeWindow(
                startTime = TimeOfDay(windowDto.startHour, windowDto.startMinute),
                endTime = TimeOfDay(windowDto.endHour, windowDto.endMinute),
            )
        }
        ProfileTrigger.Schedule(
            ScheduleTrigger(
                dailyWindows = windows,
                sameScheduleEveryDay = sameScheduleEveryDay ?: true,
            ),
        )
    }
    "GEO" -> {
        val lat = latitude ?: throw ConfigValidationException("$context: GEO trigger missing 'latitude'.")
        val lon = longitude ?: throw ConfigValidationException("$context: GEO trigger missing 'longitude'.")
        val radius = radiusMeters ?: GeoTrigger.DEFAULT_RADIUS_METERS
        val mode = geoMode?.let { parseEnum<GeoTriggerMode>(it, "$context.geoMode") } ?: GeoTriggerMode.INSIDE
        ProfileTrigger.Geo(
            GeoTrigger(
                latitude = lat,
                longitude = lon,
                radiusMeters = radius,
                mode = mode,
            ),
        )
    }
    "NFC" -> {
        val parsedTags = (tags ?: emptyList()).map { NfcTagEntry(uid = it.uid, label = it.label) }
        val mode = nfcMode?.let { parseEnum<NfcTriggerMode>(it, "$context.nfcMode") }
            ?: NfcTriggerMode.TOGGLE_ON_TAP
        val duration = durationMinutes ?: NfcTrigger.DEFAULT_DURATION_MINUTES
        ProfileTrigger.Nfc(
            NfcTrigger(
                tags = parsedTags,
                mode = mode,
                durationMinutes = duration,
                maxDailyUses = maxDailyUses,
            ),
        )
    }
    else -> throw ConfigValidationException(
        "Unknown trigger type '$type' in $context. Expected SCHEDULE, GEO, or NFC.",
    )
}

private inline fun <reified T : Enum<T>> parseEnum(raw: String, field: String): T =
    enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ConfigValidationException(
            "Invalid value '$raw' for $field. Allowed: ${enumValues<T>().joinToString { it.name }}.",
        )
