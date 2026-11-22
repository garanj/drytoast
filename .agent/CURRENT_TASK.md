# Current Task

- **Active Issue**: [#144](https://github.com/garanj/threshold/issues/144) — `fix(home): exit search mode and restore dialer icon when IME is dismissed with empty query`
- **Branch**: `fix/144-ime-dismiss-exit-search`
- **Status**: Ready for Review

## Checklist
- [x] Observe `WindowInsets.isImeVisible` in `ThresholdHomeScreen` to auto-dismiss empty search when IME closes
- [x] Add deterministic Compose unit tests in `ThresholdHomeScreenTest` for empty vs non-empty query IME dismissal
- [x] Verify `./gradlew spotlessCheck test :app:installDebug` passes and installed on Pixel 9a
