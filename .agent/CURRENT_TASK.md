# Current Task

- **Issue:** [#216](https://github.com/garanj/drytoast/issues/216) — `feat(home): adapt Home canvas and Search surface to compact heights, landscape IME, and tabletop posture`
- **Branch:** `feat/216-home-search-compact-height-tabletop`
- **Status:** Ready for Review

## Checklist
- [x] TDD Red: Added landscape compact-height (7 Quick Actions scroll + active Search with IME) and horizontal `HALF_OPENED` Tabletop `FoldingFeature` tests in `DryToastHomeScreenTest`.
- [x] TDD Green: Implemented compact single-row instrument header, Tabletop two-zone crease-safe layout, and compact Search vertical rhythm across `DryToastHomeCanvas`, `DryToastHomeScreen`, and `DryToastSearchSurface`; recorded `home_canvas_phone_landscape.png` and `home_search_foldable_tabletop.png` Roborazzi goldens; added `DryToastSearchRotationInstrumentedTest` (`androidTest`).
- [x] Verified `./gradlew spotlessCheck --no-daemon`, `./gradlew test`, `:app:compileReleaseKotlin`, `:app:compileDebugAndroidTestKotlin`, and zero `android.*` imports in `:core`.
