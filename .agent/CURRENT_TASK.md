# Current Task

- **Issue:** [#218](https://github.com/garanj/drytoast/issues/218) — `feat(ui): constrain ceremonial surfaces in centered max-width containers on foldables and tablets`
- **Branch:** `feat/218-ceremonial-centered-max-width`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added tablet (`1280x800dp`) centered max-width bounds & outer-margin gesture tests in `DryToastHomeScreenTest`, `OnboardingScreenTest`, and `HardenedModeScreenTest`.
- [x] TDD Green: Constrained `DryToastHomeCanvas`/`DryToastSearchSurface` (`<= 600.dp`), `OnboardingScreen` (`<= 600.dp` + `< 440dp` compact vertical rhythm), and `DisableHardenedModeScreen`/`SettingsLockScreen` (`<= 480.dp`) centered horizontally; recorded `home_canvas_tablet_expanded.png`, `onboarding_tablet_expanded.png`, and `settings_lock_tablet_expanded.png` Roborazzi goldens.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, and zero `android.*` imports in `:core`.
