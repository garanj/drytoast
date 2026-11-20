# Current Task

- **Active Issue**: [#134](https://github.com/garanj/threshold/issues/134) — `feat(site): add Hardened Mode console and automated Firebase Hosting release flow`
- **Branch**: `feat/134-hardened-mode-console`
- **Status**: Ready for Review

## Checklist
- [x] Verify `*.apk` is ignored in `.gitignore`
- [x] Add `site/provisioning-qr-payload.json` with `https://thresholdlauncher.com/downloads/threshold-release.apk`
- [x] Add mode switcher / explainer to `site/console/index.html` and create `site/console/hardening/index.html` (with `/hardened-mode` redirect in `firebase.json`)
- [x] Update `.github/workflows/release.yml` to stage `site/downloads/threshold-release.apk` and `site/provisioning-qr-payload.json` and deploy to Firebase Hosting
