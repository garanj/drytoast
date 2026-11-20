# Current Task

- **Active Issue**: [#128](https://github.com/garanj/threshold/issues/128) — `chore(settings): update Hardened Mode setup guide URL to thresholdlauncher.com`
- **Branch**: `fix/128-update-hardened-mode-url`
- **Status**: Ready for Review

## Checklist
- [x] `:app`: Update `R.string.hardened_mode_website_url` to `https://thresholdlauncher.com/hardened-mode`
- [x] `:app`: Update `HardenedModeScreenTest` to assert `https://thresholdlauncher.com/hardened-mode`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
