# Current Task

- **Active Issue**: [#94](https://github.com/garanj/threshold/issues/94) — `feat(settings): require reboot for settings changes`
- **Branch**: `feat/94-require-reboot-for-settings-changes`
- **Status**: Ready for Review

## Checklist
- [x] File GitHub Issue #94 and checkout branch `feat/94-require-reboot-for-settings-changes`
- [x] `:core`: Add `requireRebootForSettingsChange` to `GeneralSettings`, `StagedSettingsSnapshot` and `stagedShadow` to `ThresholdSettings`, and `discardPendingChanges()` / `promotePendingSettingsOnBoot()` to `SettingsRepository`
- [x] `:data`: Implement staged shadow persistence, `Settings.Global.BOOT_COUNT` comparison, and `promotePendingSettingsOnBoot()` / `discardPendingChanges()` in `AndroidSettingsRepository`
- [x] `:app`: Wire `Intent.ACTION_BOOT_COMPLETED` promotion in `ScheduleAlarmReceiver`, update `SettingsViewModel` to read/mutate `editableSnapshot`, and add confirmation dialog + pending changes badge/discard banner in `ThresholdSettingsScreen`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
