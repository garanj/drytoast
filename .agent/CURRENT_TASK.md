# Current Task

- **Active Issue**: [#147](https://github.com/garanj/drytoast/issues/147) — `feat(ui): audit and improve in-app links to drytoast.app documentation and console`
- **Branch**: `feat/147-in-app-docs-and-console-links`
- **Status**: Ready for Review

## Checklist
- [x] Create reusable `SecondDeviceLinkBottomSheet` (large monospace URL + Share link + Copy link + Open on this phone)
- [x] Add **Documentation & Guides** and **Settings Lock Console** rows in `DryToastSettingsScreen`
- [x] Wire `HardenedModeScreen` **Open Hardened Mode Setup Guide** and **Settings Lock Console** companion row to `SecondDeviceLinkBottomSheet`
- [x] Add subtle mobile-only second-device banner to `site/console/index.html` and `site/console/hardening/index.html`
- [x] Add Compose unit tests and verify `./gradlew spotlessCheck test` passes
- [x] Open PR assigned to `garanj`
