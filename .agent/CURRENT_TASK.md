# Current Task

- **Issue:** [#217](https://github.com/garanj/drytoast/issues/217) — `fix(settings): preserve open editor sheets, filter states, and IME insets across foldable recreation`
- **Branch:** `fix/217-settings-state-restoration-ime`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added `StateRestorationTester` tests across `DryToastSettingsScreenTest`, `ConfigureAppsScreenTest`, `ProfilesScreenTest`, `HardenedModeScreenTest`, and `BreakGlassScreenTest`.
- [x] TDD Green: Migrated transient UI flags, tabs/filters, and non-secret draft fields to `rememberSaveable` (with `Bundle`-safe custom `Saver`s for domain types) and added `.imePadding()` across Settings & editor sheets; added `DryToastFoldableRecreationInstrumentedTest` (`androidTest`).
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, and zero `android.*` imports in `:core`.
