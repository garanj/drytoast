# Current Task

- **Issue:** [#211](https://github.com/garanj/drytoast/issues/211) — `fix(security): enable activity resizability and reactive multi-window lock on QR challenge screens`
- **Branch:** `fix/211-resizable-reactive-multiwindow-challenge`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added manifest resizability & orientation guard in `ArchitectureAndConfigGuardTest`, live `OnMultiWindowModeChangedListener` (`false <-> true`), `StateRestorationTester`, and hardware keyboard (`0–9` / `NumPad0–9` / `Backspace` / `Delete` / `Enter`) tests in `HardenedModeScreenTest`, split-screen Roborazzi golden in `DryToastScreenshotTest`, and on-device `DryToastChallengeMultiWindowInstrumentedTest`.
- [x] TDD Green: Set `android:resizeableActivity="true"` on `MainActivity` in `AndroidManifest.xml`, tracked multi-window state reactively via `OnMultiWindowModeChangedProvider`, rotated challenge + cleared input on every multi-window transition, persisted `challenge` & `enteredCode` via `rememberSaveable` (ignoring config-change teardown pauses), and added hardware keyboard key handling on `ChallengeVerificationSection`.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, and zero `android.*` imports in `:core`.
