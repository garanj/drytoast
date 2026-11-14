# Current Task

- **Active Issue**: [#100](https://github.com/garanj/threshold/issues/100) — `[UI] Task 3.6: 6-Page Onboarding Wizard & Mandatory Default Launcher Role Prompt`
- **Branch**: `feat/100-onboarding-wizard`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Add `hasCompletedOnboarding: Boolean = false` to `GeneralSettings` and `setHasCompletedOnboarding(completed: Boolean)` to `SettingsRepository`, plus `LauncherRoleController` port
- [x] `:data`: Persist `HAS_COMPLETED_ONBOARDING` in `AndroidSettingsRepository` and add unit tests
- [x] `:app`: Update `R.string.hint_long_press_to_configure` to `"Press and hold to configure"`
- [x] `:app`: Implement `AndroidLauncherRoleController` (`RoleManager.ROLE_HOME`) and wire into `ThresholdAppContainer`
- [x] `:app`: Implement 6-page `OnboardingScreen` with interactive `[ ALLOW | GUARD | BLOCK ]` toggle, micro-haptic detents, and mandatory Default Launcher (`ROLE_HOME`) completion gate
- [x] `:app`: Wire `ThresholdRoute.Onboarding` in `MainActivity` and add unit/Compose tests
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
