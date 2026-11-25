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
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ConfigBackupCodecTest {

    // Fast iterations for unit test speed; real 210_000 default is also explicitly tested below.
    private val fastCodec = ConfigBackupCodec(pbkdf2Iterations = 1_000)

    private fun richSnapshot(): StagedSettingsSnapshot {
        val camera = ComponentKey("com.android.camera", "com.android.camera.CameraActivity", 0L)
        val gmail = ComponentKey("com.google.android.gm", "com.google.android.gm.MainActivity", 0L)
        val reddit = ComponentKey("com.reddit.frontpage", "com.reddit.frontpage.MainActivity", 0L)
        val workSlack = ComponentKey("com.slack", "com.slack.MainActivity", 10L)

        val scheduleTrigger = SavedTrigger(
            id = "trig-work-hours",
            name = "Work Hours",
            trigger = ProfileTrigger.Schedule(
                ScheduleTrigger(
                    dailyWindows = mapOf(
                        DayOfWeek.MONDAY to TimeWindow(TimeOfDay(9, 0), TimeOfDay(17, 30)),
                        DayOfWeek.FRIDAY to TimeWindow(TimeOfDay(22, 0), TimeOfDay(6, 0)),
                    ),
                    sameScheduleEveryDay = false,
                ),
            ),
        )
        val geoTrigger = SavedTrigger(
            id = "trig-office",
            name = "At Office",
            trigger = ProfileTrigger.Geo(
                GeoTrigger(
                    latitude = 51.5074,
                    longitude = -0.1278,
                    radiusMeters = 150,
                    mode = GeoTriggerMode.INSIDE,
                ),
            ),
        )
        val nfcTrigger = SavedTrigger(
            id = "trig-desk-nfc",
            name = "Desk Card",
            trigger = ProfileTrigger.Nfc(
                NfcTrigger(
                    tags = listOf(NfcTagEntry(uid = "04:A2:3F:8B", label = "Desk")),
                    mode = NfcTriggerMode.TIMED_SESSION,
                    durationMinutes = 45,
                    maxDailyUses = 3,
                ),
            ),
        )

        return StagedSettingsSnapshot(
            general = GeneralSettings(
                showUnlocks = false,
                showRest = true,
                showDialerIcon = false,
                requireFullTypingOfGuardedApps = true,
                requireRebootForSettingsChange = true,
                guardedSessionGraceMinutes = 15,
                blockNewAppInstalls = true,
                hardenedOwnerName = "Studio Phone",
                hardenedLockScreenInfo = "Call +44 7700 900000 if found",
                breakGlassTagUid = "AA:BB:CC:DD",
                breakGlassDurationMinutes = 60,
            ),
            baseProfile = BaseProfileSettings(
                defaultFriction = FrictionChoice.CARET_TRACING,
                appAssignments = mapOf(
                    camera to AppAssignment.Standard,
                    gmail to AppAssignment.Guarded(frictionOverride = FrictionChoice.DRAG_SORT),
                    reddit to AppAssignment.Blocked,
                    workSlack to AppAssignment.Guarded(frictionOverride = null),
                ),
                quickActions = listOf(
                    InstalledApp(camera, "Camera"),
                    InstalledApp(gmail, "Gmail"),
                ),
            ),
            customProfiles = listOf(
                CustomProfile(
                    id = "prof-deep-work",
                    name = "Deep Work",
                    enabled = true,
                    priority = 0,
                    trigger = null,
                    activationRules = listOf(
                        TriggerRule(listOf("trig-work-hours", "trig-office")),
                        TriggerRule(listOf("trig-desk-nfc")),
                    ),
                    appAssignments = mapOf(
                        workSlack to AppAssignment.Standard,
                        gmail to AppAssignment.Blocked,
                    ),
                    defaultRule = ProfileDefaultRule.BLOCK_ALL,
                    frictionOverride = FrictionChoice.TYPED_INTENTION,
                    colorIndex = 2,
                ),
                CustomProfile(
                    id = "prof-legacy",
                    name = "Legacy Inline",
                    enabled = false,
                    priority = 1,
                    trigger = ProfileTrigger.Geo(
                        GeoTrigger(
                            latitude = 37.7749,
                            longitude = -122.4194,
                            radiusMeters = 250,
                            mode = GeoTriggerMode.OUTSIDE,
                        ),
                    ),
                    activationRules = emptyList(),
                    appAssignments = mapOf(
                        reddit to AppAssignment.Guarded(FrictionChoice.RANDOM),
                    ),
                    defaultRule = ProfileDefaultRule.GUARD_ALL,
                    frictionOverride = null,
                    colorIndex = 4,
                ),
            ),
            savedTriggers = listOf(scheduleTrigger, geoTrigger, nfcTrigger),
        )
    }

    // -------------------------------------------------------------------------
    // Happy-path round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `plaintext round-trip preserves all settings, profiles, triggers, and rules`() {
        val original = richSnapshot()
        val encoded = fastCodec.encode(original, passphrase = null)

        val root = Json.parseToJsonElement(encoded).jsonObject
        assertThat(root["format"]?.jsonPrimitive?.content).isEqualTo(ConfigBackupCodec.FORMAT_ID)
        assertThat(root["version"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(root["encrypted"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(root.containsKey("crypto")).isFalse()

        val decoded = fastCodec.decode(encoded)
        assertThat(decoded).isEqualTo(ConfigImportResult.Success(original))
    }

    @Test
    fun `default empty snapshot round-trips cleanly`() {
        val empty = StagedSettingsSnapshot()
        val encoded = fastCodec.encode(empty)
        val decoded = fastCodec.decode(encoded)
        assertThat(decoded).isEqualTo(ConfigImportResult.Success(empty))
    }

    @Test
    fun `encrypted round-trip with default OWASP iterations preserves full snapshot`() {
        val defaultCodec = ConfigBackupCodec()
        val original = richSnapshot()
        val encoded = defaultCodec.encode(original, passphrase = "correct horse battery staple")

        val root = Json.parseToJsonElement(encoded).jsonObject
        assertThat(root["encrypted"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(root.containsKey("config")).isFalse()
        assertThat(encoded).doesNotContain("Studio Phone")
        assertThat(encoded).doesNotContain("com.reddit.frontpage")
        assertThat(encoded).doesNotContain("51.5074")

        assertThat(defaultCodec.decode(encoded, passphrase = null))
            .isEqualTo(ConfigImportResult.PassphraseRequired)
        assertThat(defaultCodec.decode(encoded, passphrase = ""))
            .isEqualTo(ConfigImportResult.PassphraseRequired)

        val decoded = defaultCodec.decode(encoded, passphrase = "correct horse battery staple")
        assertThat(decoded).isEqualTo(ConfigImportResult.Success(original))
    }

    @Test
    fun `encrypting same snapshot twice produces distinct salt, iv, and ciphertext`() {
        val snapshot = richSnapshot()
        val first = Json.parseToJsonElement(fastCodec.encode(snapshot, "pass123")).jsonObject["crypto"]!!.jsonObject
        val second = Json.parseToJsonElement(fastCodec.encode(snapshot, "pass123")).jsonObject["crypto"]!!.jsonObject

        assertThat(first["salt"]).isNotEqualTo(second["salt"])
        assertThat(first["iv"]).isNotEqualTo(second["iv"])
        assertThat(first["ciphertext"]).isNotEqualTo(second["ciphertext"])
    }

    @Test
    fun `hand-authored plaintext json without userSerial suffix and with unknown extra fields decodes cleanly`() {
        val handAuthored = """
            {
              "format": "drytoast-config",
              "version": 1,
              "encrypted": false,
              "extraMetadata": "ignored-safely",
              "config": {
                "general": {
                  "showUnlocks": false,
                  "futureFlag": true
                },
                "baseProfile": {
                  "defaultFriction": "caret_tracing",
                  "appAssignments": {
                    "com.example.app/com.example.app.MainActivity": {
                      "type": "blocked"
                    }
                  },
                  "quickActions": [
                    { "component": "com.android.camera/com.android.camera.Camera", "label": "Camera" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = fastCodec.decode(handAuthored)
        assertThat(result).isInstanceOf(ConfigImportResult.Success::class.java)
        val snapshot = (result as ConfigImportResult.Success).snapshot
        assertThat(snapshot.general.showUnlocks).isFalse()
        assertThat(snapshot.baseProfile.defaultFriction).isEqualTo(FrictionChoice.CARET_TRACING)
        assertThat(snapshot.baseProfile.appAssignments)
            .containsEntry(
                ComponentKey("com.example.app", "com.example.app.MainActivity", 0L),
                AppAssignment.Blocked,
            )
    }

    // -------------------------------------------------------------------------
    // Envelope & structural failures
    // -------------------------------------------------------------------------

    @Test
    fun `empty and blank input returns EMPTY_INPUT`() {
        assertError(fastCodec.decode(""), ConfigImportError.EMPTY_INPUT)
        assertError(fastCodec.decode("   \n\t  "), ConfigImportError.EMPTY_INPUT)
    }

    @Test
    fun `oversized input returns INPUT_TOO_LARGE`() {
        val huge = " ".repeat(ConfigBackupCodec.MAX_INPUT_CHARS + 1)
        // Non-blank oversized string
        val nonBlankHuge = "{" + " ".repeat(ConfigBackupCodec.MAX_INPUT_CHARS) + "}"
        assertError(fastCodec.decode(huge), ConfigImportError.EMPTY_INPUT)
        assertError(fastCodec.decode(nonBlankHuge), ConfigImportError.INPUT_TOO_LARGE)
    }

    @Test
    fun `malformed or non-object json returns INVALID_JSON`() {
        assertError(fastCodec.decode("{not valid json"), ConfigImportError.INVALID_JSON)
        assertError(fastCodec.decode("[]"), ConfigImportError.INVALID_JSON)
        assertError(fastCodec.decode("\"just a string\""), ConfigImportError.INVALID_JSON)
        assertError(fastCodec.decode("12345"), ConfigImportError.INVALID_JSON)
    }

    @Test
    fun `missing or wrong format returns UNSUPPORTED_FORMAT`() {
        assertError(
            fastCodec.decode("""{"version": 1, "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_FORMAT,
        )
        assertError(
            fastCodec.decode("""{"format": "other-launcher", "version": 1, "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_FORMAT,
        )
    }

    @Test
    fun `missing, zero, negative, or future version returns UNSUPPORTED_VERSION`() {
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_VERSION,
        )
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "version": 0, "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_VERSION,
        )
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "version": -1, "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_VERSION,
        )
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "version": 2, "encrypted": false, "config": {}}"""),
            ConfigImportError.UNSUPPORTED_VERSION,
        )
    }

    @Test
    fun `missing config payload in plaintext envelope returns MISSING_CONFIG_PAYLOAD`() {
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "version": 1, "encrypted": false}"""),
            ConfigImportError.MISSING_CONFIG_PAYLOAD,
        )
    }

    @Test
    fun `missing crypto payload in encrypted envelope returns MISSING_CRYPTO_PAYLOAD`() {
        assertError(
            fastCodec.decode("""{"format": "drytoast-config", "version": 1, "encrypted": true}""", "secret"),
            ConfigImportError.MISSING_CRYPTO_PAYLOAD,
        )
    }

    // -------------------------------------------------------------------------
    // Cryptography & tampering failures
    // -------------------------------------------------------------------------

    @Test
    fun `unsupported crypto kdf, cipher, or iteration bounds return UNSUPPORTED_CRYPTO_PARAMETERS`() {
        val validEncrypted = fastCodec.encode(StagedSettingsSnapshot(), "secret")

        val badKdf = replaceCryptoField(validEncrypted, "kdf", JsonPrimitive("PBKDF2WithHmacSHA1"))
        assertError(fastCodec.decode(badKdf, "secret"), ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS)

        val badCipher = replaceCryptoField(validEncrypted, "cipher", JsonPrimitive("AES/ECB/PKCS5Padding"))
        assertError(fastCodec.decode(badCipher, "secret"), ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS)

        val tooFewIters = replaceCryptoField(validEncrypted, "iterations", JsonPrimitive(100))
        assertError(fastCodec.decode(tooFewIters, "secret"), ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS)

        val tooManyIters = replaceCryptoField(validEncrypted, "iterations", JsonPrimitive(5_000_000))
        assertError(fastCodec.decode(tooManyIters, "secret"), ConfigImportError.UNSUPPORTED_CRYPTO_PARAMETERS)
    }

    @Test
    fun `corrupt base64 and invalid salt, iv, or ciphertext lengths return CORRUPT_CRYPTO_DATA`() {
        val validEncrypted = fastCodec.encode(StagedSettingsSnapshot(), "secret")
        val b64 = Base64.getEncoder()

        assertError(
            fastCodec.decode(replaceCryptoField(validEncrypted, "salt", JsonPrimitive("!!!not-base64!!!")), "secret"),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(replaceCryptoField(validEncrypted, "iv", JsonPrimitive("!!!not-base64!!!")), "secret"),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(
                replaceCryptoField(validEncrypted, "ciphertext", JsonPrimitive("!!!not-base64!!!")),
                "secret",
            ),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(
                replaceCryptoField(validEncrypted, "salt", JsonPrimitive(b64.encodeToString(ByteArray(8)))),
                "secret",
            ),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(
                replaceCryptoField(validEncrypted, "iv", JsonPrimitive(b64.encodeToString(ByteArray(8)))),
                "secret",
            ),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(
                replaceCryptoField(validEncrypted, "iv", JsonPrimitive(b64.encodeToString(ByteArray(16)))),
                "secret",
            ),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
        assertError(
            fastCodec.decode(
                replaceCryptoField(validEncrypted, "ciphertext", JsonPrimitive(b64.encodeToString(ByteArray(10)))),
                "secret",
            ),
            ConfigImportError.CORRUPT_CRYPTO_DATA,
        )
    }

    @Test
    fun `wrong passphrase returns INVALID_PASSPHRASE_OR_TAMPERED`() {
        val encoded = fastCodec.encode(richSnapshot(), "right-passphrase")
        assertError(fastCodec.decode(encoded, "wrong-passphrase"), ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED)
    }

    @Test
    fun `bit-flip tampering in ciphertext, iv, or salt returns INVALID_PASSPHRASE_OR_TAMPERED`() {
        val encoded = fastCodec.encode(richSnapshot(), "secret")

        assertError(
            fastCodec.decode(flipBitInCryptoField(encoded, "ciphertext"), "secret"),
            ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED,
        )
        assertError(
            fastCodec.decode(flipBitInCryptoField(encoded, "iv"), "secret"),
            ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED,
        )
        assertError(
            fastCodec.decode(flipBitInCryptoField(encoded, "salt"), "secret"),
            ConfigImportError.INVALID_PASSPHRASE_OR_TAMPERED,
        )
    }

    @Test
    fun `validly encrypted non-json plaintext returns INVALID_JSON`() {
        val salt = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val factory = SecretKeyFactory.getInstance(ConfigBackupCodec.KDF_ALGORITHM)
        val keyBytes = factory.generateSecret(PBEKeySpec("secret".toCharArray(), salt, 1_000, 256)).encoded
        val cipher = Cipher.getInstance(ConfigBackupCodec.CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("drytoast-config:v1".toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal("not valid json payload".toByteArray(Charsets.UTF_8))

        val b64 = Base64.getEncoder()
        val raw = """
            {
              "format": "drytoast-config",
              "version": 1,
              "encrypted": true,
              "crypto": {
                "kdf": "PBKDF2WithHmacSHA256",
                "iterations": 1000,
                "cipher": "AES/GCM/NoPadding",
                "salt": "${b64.encodeToString(salt)}",
                "iv": "${b64.encodeToString(iv)}",
                "ciphertext": "${b64.encodeToString(ciphertext)}"
              }
            }
        """.trimIndent()

        assertError(fastCodec.decode(raw, "secret"), ConfigImportError.INVALID_JSON)
    }

    // -------------------------------------------------------------------------
    // Domain validation failures
    // -------------------------------------------------------------------------

    @Test
    fun `invalid component keys in appAssignments and quickActions return INVALID_CONFIG_VALUES`() {
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "appAssignments": { "missing-slash": { "type": "BLOCKED" } }
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "appAssignments": { "com.a/com.a.Main#notanumber": { "type": "BLOCKED" } }
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "appAssignments": { "com.a/com.a.Main#-1": { "type": "BLOCKED" } }
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
    }

    @Test
    fun `invalid quickActions count, duplicates, and blank label return INVALID_CONFIG_VALUES`() {
        val eightActions = (1..8).joinToString(",") {
            """{"component": "com.a$it/com.a$it.Main", "label": "App $it"}"""
        }
        assertError(
            fastCodec.decode(wrapConfig(""" "baseProfile": { "quickActions": [$eightActions] } """)),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "quickActions": [
                        {"component": "com.a/com.a.Main", "label": "A"},
                        {"component": "com.a/com.a.Main", "label": "A dup"}
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "quickActions": [{"component": "com.a/com.a.Main", "label": "   "}]
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
    }

    @Test
    fun `invalid enums and general settings durations return INVALID_CONFIG_VALUES`() {
        assertError(
            fastCodec.decode(wrapConfig(""" "baseProfile": { "defaultFriction": "UNKNOWN_MODE" } """)),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "baseProfile": {
                      "appAssignments": { "com.a/com.a.Main": { "type": "SUSPENDED" } }
                    }
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(wrapConfig(""" "general": { "guardedSessionGraceMinutes": 7 } """)),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(wrapConfig(""" "general": { "breakGlassDurationMinutes": 45 } """)),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
    }

    @Test
    fun `invalid trigger fields return INVALID_CONFIG_VALUES`() {
        // Invalid TimeOfDay
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Bad Time",
                      "trigger": {
                        "type": "SCHEDULE",
                        "dailyWindows": { "MONDAY": { "startHour": 25, "startMinute": 0, "endHour": 17, "endMinute": 0 } }
                      }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Invalid Geo latitude / radius / missing longitude
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Bad Geo",
                      "trigger": { "type": "GEO", "latitude": 95.0, "longitude": 0.0, "radiusMeters": 100 }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Missing Lon",
                      "trigger": { "type": "GEO", "latitude": 51.5 }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Zero Radius",
                      "trigger": { "type": "GEO", "latitude": 51.5, "longitude": -0.1, "radiusMeters": 0 }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Invalid NFC duration / maxDailyUses / blank UID
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Bad NFC Duration",
                      "trigger": { "type": "NFC", "durationMinutes": 999 }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Blank NFC UID",
                      "trigger": { "type": "NFC", "tags": [{ "uid": "   " }] }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Unknown trigger type
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [{
                      "id": "t1", "name": "Unknown",
                      "trigger": { "type": "BLUETOOTH" }
                    }]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
    }

    @Test
    fun `duplicate ids, dangling triggers, and bad profile fields return INVALID_CONFIG_VALUES`() {
        // Duplicate trigger ID
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "savedTriggers": [
                      {"id": "dup", "name": "A", "trigger": {"type": "SCHEDULE"}},
                      {"id": "dup", "name": "B", "trigger": {"type": "SCHEDULE"}}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Duplicate profile ID
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "customProfiles": [
                      {"id": "p1", "name": "A"},
                      {"id": "p1", "name": "B"}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Dangling trigger ID in activationRules
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "customProfiles": [
                      {"id": "p1", "name": "Work", "activationRules": [["non-existent-trigger"]]}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Empty conjunction in activationRules
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "customProfiles": [
                      {"id": "p1", "name": "Work", "activationRules": [[]]}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Invalid defaultRule
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "customProfiles": [
                      {"id": "p1", "name": "Work", "defaultRule": "DENY_EVERYTHING"}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
        // Negative priority
        assertError(
            fastCodec.decode(
                wrapConfig(
                    """
                    "customProfiles": [
                      {"id": "p1", "name": "Work", "priority": -1}
                    ]
                    """.trimIndent(),
                ),
            ),
            ConfigImportError.INVALID_CONFIG_VALUES,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun wrapConfig(configBody: String): String = """
        {
          "format": "drytoast-config",
          "version": 1,
          "encrypted": false,
          "config": {
            $configBody
          }
        }
    """.trimIndent()

    private fun assertError(result: ConfigImportResult, expectedReason: ConfigImportError) {
        assertThat(result).isInstanceOf(ConfigImportResult.Error::class.java)
        val err = result as ConfigImportResult.Error
        assertThat(err.reason).isEqualTo(expectedReason)
        assertThat(err.detail).isNotEmpty()
    }

    private fun replaceCryptoField(rawJson: String, key: String, replacement: JsonPrimitive): String {
        val root = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()
        val crypto = root["crypto"]!!.jsonObject.toMutableMap()
        crypto[key] = replacement
        root["crypto"] = JsonObject(crypto)
        return JsonObject(root).toString()
    }

    private fun flipBitInCryptoField(rawJson: String, key: String): String {
        val root = Json.parseToJsonElement(rawJson).jsonObject
        val originalB64 = root["crypto"]!!.jsonObject[key]!!.jsonPrimitive.content
        val bytes = Base64.getDecoder().decode(originalB64)
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        return replaceCryptoField(rawJson, key, JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
    }
}
