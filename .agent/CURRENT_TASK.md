# Current Task

- **Active Issue**: [#106](https://github.com/garanj/threshold/issues/106) — `feat(home): add dialer keypad icon on launcher with settings toggle`
- **Branch**: `feat/106-dialer-keypad-icon`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Add `showDialerIcon: Boolean = true` to `GeneralSettings` and `hasSameConfigurationAs`
- [x] `:data`: Persist `show_dialer_icon` in `AndroidSettingsRepository` (live and staged shadow snapshots)
- [x] `:app`: Add `ThresholdIcons.Dialpad` vector icon and `AppLauncher.launchDialer()`
- [x] `:app`: Render the centered keypad icon near the bottom of `ThresholdHomeCanvas` / `ThresholdHomeScreen` when `showDialerIcon` is enabled
- [x] `:app`: Add `"Show dialer icon on launcher"` toggle in `ThresholdSettingsScreen` and `SettingsViewModel`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
