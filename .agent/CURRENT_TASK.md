# Current Task

- **Active Issue**: [#112](https://github.com/garanj/threshold/issues/112) — `feat(release): implement Phase 7 hardening, baseline profiles, RTL visual audit & R8 release configuration`
- **Branch**: `feat/112-phase7-hardening`
- **Status**: Ready for Review

## Checklist
- [x] `:benchmark`: Expand `BaselineProfileGenerator` and add `StartupAndFrameTimingBenchmark` (`MacrobenchmarkRule`)
- [x] `:app`: Add `app/src/main/baseline-prof.txt` AOT baseline profile rules
- [x] `:app`: Enable `autoMirror = true` on directional icons (`ArrowBack`, `ChevronRight`, `OpenInNew`) and record RTL Roborazzi golden screenshots
- [x] `:app`: Configure `app/proguard-rules.pro`, `isShrinkResources = true`, and environment-driven release signing in `app/build.gradle.kts`
- [x] Verify `./gradlew spotlessApply spotlessCheck test assembleRelease` and open PR assigned to `garanj`
