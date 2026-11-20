# Current Task

- **Active Issue**: [#138](https://github.com/garanj/threshold/issues/138) — `feat(site,release): add Expert Mode WebUSB Hardened Mode provisioner and testOnly release artifact`
- **Branch**: `feat/138-expert-webusb-hardening`
- **Status**: Ready for Review

## Checklist
- [x] Add `THRESHOLD_TEST_ONLY` manifest placeholder support in `app/build.gradle.kts` and `app/src/main/AndroidManifest.xml`
- [x] Update `.github/workflows/release.yml` to build & publish `threshold-testonly.apk`, verify `testOnly` flags, and stage `site/downloads/threshold-testonly.bin` & `site/downloads/threshold-release.bin`
- [x] Bundle self-hosted Tango WebUSB ADB client (`site/console/webadb.bundle.js`)
- [x] Add **ENABLE (EXPERT)** tab and 5-stage WebUSB Hardened Mode provisioner + on-device 90s watchdog + Emergency Restore/Repair controls to `site/console/hardening/index.html`
- [x] Verify Gradle unit tests & Spotless pass (`./gradlew spotlessCheck test`)
