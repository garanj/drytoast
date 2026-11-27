# Current Task

- **Issue:** [#214](https://github.com/garanj/drytoast/issues/214) — `fix(friction): adapt Toaster Timer and Typed Intention to compact landscape height and fold recreation`
- **Branch:** `fix/214-toaster-typed-intention-adaptive`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added compact landscape (`891x390dp`) & short split-screen (`411x380dp`) non-overlapping bounds + `StateRestorationTester` tests in `CaretTracingSurfaceTest` and mid-phrase random-phrase/progress `StateRestorationTester` + `1280x800dp` max-width + `48.dp` touch-target tests in `TypedIntentionSurfaceTest` + `:core` `TypedIntentionSessionTest`.
- [x] TDD Green: Sized `CaretTracingSurface` square toaster stage via `BoxWithConstraints`, persisted `isComplete` via `rememberSaveable`, supported `initialMatchedCount` in `:core` `TypedIntentionSession`, persisted `targetPhrase` & `matchedCount` via `rememberSaveable` in `TypedIntentionSurface` with `600.dp` max-width framing & `48.dp` action button, recorded `caret_tracing_phone_landscape.png` Roborazzi golden, and added `DryToastFrictionRotationInstrumentedTest`.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, and zero `android.*` imports in `:core`.
