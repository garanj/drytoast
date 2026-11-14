# Current Task

- **Active Issue**: [#96](https://github.com/garanj/threshold/issues/96) — `feat(settings): allow locking and unlocking settings via ADB broadcast`
- **Branch**: `feat/96-adb-lock-settings`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Add `isSettingsLocked: Boolean = false` to `ThresholdSettings` and `suspend fun setSettingsLocked(locked: Boolean)` to `SettingsRepository`
- [x] `:data`: Implement `Keys.SETTINGS_LOCKED`, `setSettingsLocked(locked)`, and guard `mutateConfiguration` and `discardPendingChanges()` in `AndroidSettingsRepository` when `isSettingsLocked` is `true`
- [x] `:app`: Create `SettingsLockReceiver` registered in `AndroidManifest.xml` with `android:permission="android.permission.DUMP"`, update `MainActivity` and `SettingsViewModel` so locked settings show `"Settings are locked"` toast on long-press / `:settings` and kick out of open settings routes
- [x] Add unit & Robolectric tests for `SettingsLockReceiver` ADB broadcasts, `AndroidSettingsRepository` double-protection, and UI entry-point locking
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
