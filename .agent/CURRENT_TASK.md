# Current Task

- **Issue:** [#212](https://github.com/garanj/drytoast/issues/212) — `fix(settings): preserve encrypted backup passphrase and import state across activity recreation`
- **Branch:** `fix/212-preserve-backup-state-across-recreation`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added unit & end-to-end `MainActivity` `ActivityScenario.recreate()` tests in `ConfigBackupViewModelAndUiTest` and `ConfigBackupRecreationTest` verifying encrypted SAF export preserves passphrase across `recreate()`, fails closed if no export request is staged, cancels cleanly on `RESULT_CANCELED`, and preserves `ImportPassphraseBottomSheet` across `recreate()`.
- [x] TDD Green: Moved `PendingExportRequest` and pending encrypted import state into `SettingsViewModel` and wired `MainActivity` (`DryToastRoute.Settings`) to fail closed rather than falling back to plaintext.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, and zero `android.*` imports in `:core`.
