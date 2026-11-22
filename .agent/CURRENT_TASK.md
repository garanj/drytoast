# Current Task

- **Active Issue**: [#133](https://github.com/garanj/threshold/issues/133) — `refactor(settings): hide or relocate 'Guarded app grace period' when Hardened Mode is inactive`
- **Branch**: `refactor/133-hide-guarded-grace-when-not-hardened`
- **Status**: Ready for Review

## Checklist
- [x] Hide `setting_guarded_grace_period` row (and sheet) in `ThresholdSettingsScreen` when `uiState.isHardenedModeActive` is `false`
- [x] Add unit test verifying hidden state when Hardened Mode is inactive and update existing test to pass `isHardenedModeActive = true`
- [x] Verify `./gradlew spotlessCheck test` passes
- [x] Open PR assigned to `garanj`
