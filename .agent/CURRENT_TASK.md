# Current Task

- **Active Issue**: [#104](https://github.com/garanj/threshold/issues/104) — `feat(settings): add 'Feedback is a gift!' email button and :feedback shortcut`
- **Branch**: `feat/104-feedback-action`
- **Status**: Ready for Review

## Checklist
- [x] `:app`: Add `FeedbackEmailIntentFactory` creating an `ACTION_SENDTO` (`mailto:`) intent to `plop@plop.com` with subject `Threshold feedback` and pre-populated `App version`, `Android version`, and `Device` lines
- [x] `:app`: Add `"Feedback is a gift!"` button at the bottom of `ThresholdSettingsScreen`
- [x] `:app`: Register `:feedback` action shortcut in `MainActivity` and handle `"feedback"` in `ThresholdHomeScreen`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
