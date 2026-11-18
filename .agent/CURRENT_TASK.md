# Current Task

- **Active Issue**: [#126](https://github.com/garanj/threshold/issues/126) — `refactor(ui): simplify Hardened Mode Setup & Removal section to website link button`
- **Branch**: `refactor/126-simplify-hardened-mode-setup`
- **Status**: Ready for Review

## Checklist
- [x] `:app`: Replace the prerequisite/QR/ADB command items below `hardened_mode_setup_intro` on `HardenedModeScreen` with an `OutlinedButton` launching the Hardened Mode website guide URL (`R.string.hardened_mode_website_url`)
- [x] `:app`: Update `HardenedModeScreenTest` to verify the button opens the website URL via `onOpenWebsite` / `Intent.ACTION_VIEW`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
