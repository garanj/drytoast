# Current Task

- **Issue:** [#215](https://github.com/garanj/drytoast/issues/215) — `fix(ui): use compact TimeInput in TriggerEditorSheet on short viewports so dialog actions never clip`
- **Branch:** `fix/215-adaptive-trigger-time-input`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added landscape (`w891dp-h390dp-land`) compact `TimeInput` & visible-action bounds test and portrait (`w411dp-h891dp`) dial-to-keyboard mode toggle test in `ProfilesScreenTest`.
- [x] TDD Green: Added `DryToastIcons.Keyboard` vector icon, defaulted `DryToastTimeInputDialog` (`TriggerEditorSheet.kt`) to `TimeInput` when `screenHeightDp < 500`, added dial/keyboard mode toggle `IconButton` on taller viewports, wrapped dialog content in `verticalScroll` safety, and recorded `trigger_time_picker_phone_landscape.png` Roborazzi golden.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, and zero `android.*` imports in `:core`.
