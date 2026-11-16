# Current Task

- **Active Issue**: [#108](https://github.com/garanj/threshold/issues/108) — `feat(settings): persist quick action display labels with component keys to eliminate startup flicker`
- **Branch**: `feat/108-persist-quick-action-labels`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Store `InstalledApp` (`(component, label)`) in `BaseProfileSettings.quickActions`
- [x] `:data`: Serialize and deserialize quick action `(component, label)` pairs in `AndroidSettingsRepository`
- [x] `:app`: Update `SearchViewModel`, `SettingsViewModel`, and `ThresholdSettingsScreen` to use persisted quick action labels directly
- [x] Uninstall and redeploy cleanly to the connected device
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
