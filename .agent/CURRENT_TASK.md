# Current Task

- **Issue:** [#213](https://github.com/garanj/drytoast/issues/213) — `fix(friction): preserve Drag-to-Match progress across rotation/resize and bound playfield for large screens`
- **Branch:** `fix/213-drag-sort-continuity-and-bounds`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added live resize/rotation continuity, `StateRestorationTester` recreation survival, `1280x800dp` max-bounds (`560x680dp`), separating `FoldingFeature` hinge avoidance (`androidx.window:window-testing`), and `>= 48.dp` action button touch-target tests in `DragSortSurfaceTest` + `:core` `DragSortSessionTest`.
- [x] TDD Green: Supported `initiallyCompletedIds` in `:core` `DragSortSession`, persisted `effectiveSeed` and `completedPairIds` via `rememberSaveable` in `DragSortSurface`, bounded playfield to `560x680dp` and kept clear of separating `FoldingFeature` hinges, and enforced `48.dp` min size on `"Go back"` / `"Open"`.
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, and zero `android.*` imports in `:core`.
