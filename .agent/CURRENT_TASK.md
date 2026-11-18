# Current Task

- **Active Issue**: [#124](https://github.com/garanj/threshold/issues/124) — `Implement guarded apps in Device Owner mode`
- **Branch**: `feat/124-guarded-apps-device-owner`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Add `guardedSessionGraceMinutes` to `GeneralSettings`, `guardedComponents` to `ResolvedWorkingSet`, and `guardedComponents`/`grantGuardedSession`/`revokeGuardedSession` to `AppAccessController` + `SettingsRepository`
- [x] `:data`: Persist `guardedSessionGraceMinutes` (live + shadow) in `AndroidSettingsRepository` and add `onScreenOffListener` to `AndroidAntiMetricsRepository`
- [x] `:app`: Implement guarded app resting-state suspension, lease management, and O(1) fast-path short-circuiting in `AndroidAppAccessController`
- [x] `:app`: Wire `authorizeGuardedLaunch` in `SearchViewModel` + `ThresholdHomeScreen`, deduplicate `ON_START`/`ON_RESUME`, and add Guarded app grace period setting in `ThresholdSettingsScreen` + `SettingsViewModel`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
