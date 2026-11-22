# Current Task

- **Active Issue**: [#142](https://github.com/garanj/threshold/issues/142) — `feat(home): swipe up anywhere on home screen to open search with keyboard`
- **Branch**: `feat/142-swipe-up-search`
- **Status**: Ready for Review

## Checklist
- [x] Write Compose UI unit tests for swipe-up gesture activating search, focusing the search input, showing the keyboard, and ignoring downward/horizontal swipes (TDD Red)
- [x] Implement `Modifier.swipeUpToOpenSearch` on `ThresholdHomeScreen` and wire `LocalSoftwareKeyboardController.current?.show()` in `ThresholdSearchSurface` (TDD Green)
- [x] Verify `./gradlew spotlessCheck test` passes with zero regressions
- [x] Open PR assigned to `garanj`
