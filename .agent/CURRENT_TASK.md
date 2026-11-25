# Current Task

- **Issue:** [#164](https://github.com/garanj/drytoast/issues/164) — `feat(settings): local JSON configuration export and import via Storage Access Framework (with optional encryption)`
- **Branch:** `feat/164-json-config-export-import`
- **Status:** Ready for Review

## Checklist
- [x] `:core` — Added `kotlinx-serialization-json` and implemented `ConfigBackupCodec` (versioned JSON envelope + optional PBKDF2-HMAC-SHA256 / AES-256-GCM encryption + exhaustive domain validation).
- [x] `:core` — Comprehensive unit tests covering round-trips and all failure modes (malformed JSON, format/version mismatch, missing payloads, bad crypto params, corrupt base64/IV/salt, wrong passphrase, bit-flip tampering, invalid domain values, dangling triggers).
- [x] `:data` — Added `SettingsRepository.importConfiguration(snapshot)` with Settings Lock & Reboot-to-Apply enforcement + unit tests.
- [x] `:app` (`src/main/`) — Added Backup & Restore UI in `DryToastSettingsScreen` (SAF `CreateDocument` / `OpenDocument`, M3 `ModalBottomSheet` for optional export passphrase & encrypted import passphrase, `Snackbar` feedback) + ViewModel & Compose UI tests.
- [x] `:app` (`src/debug/`) — Added debug-only `DebugConfigImportReceiver` (`com.garan.drytoast.debug.IMPORT_CONFIG`) returning `OK` / detailed `ERROR` results + Robolectric tests.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest`, `:app:compileReleaseKotlin`, and zero `android.*` imports in `:core`.
