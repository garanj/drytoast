# Current Task

- **Active Issue**: [#116](https://github.com/garanj/threshold/issues/116) — `feat(security): add focus-locked 3-digit challenge + 5-min TOTP verification for Hardened Mode & Settings Lock QR flows`
- **Branch**: `feat/116-qr-challenge-response-lock`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Implement `ChallengeResponseVerifier` (3-digit challenge + 5-min UTC bucket HMAC-SHA256 -> 6-digit code, ±1 bucket skew tolerance) and unit tests (`ChallengeResponseVerifierTest`)
- [x] `:app`: Add `threshold://settings/lock?key=...` deep link (`HardenedModeDeepLink`) and `android:resizeableActivity="false"` in `AndroidManifest.xml`
- [x] `:app`: Update `DisableHardenedModeScreen` and add `SettingsLockScreen` with focus-locked 3-digit challenge rotation (`ON_PAUSE` / window focus loss / multi-window guard) and in-screen tactile 0–9 keypad
- [x] `:app`: Wire `ThresholdRoute.SettingsLock` in `MainActivity` (exempt from `isSettingsLocked` redirect)
- [x] `:app`: Add unit, Compose UI, and Roborazzi golden screenshot tests
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
