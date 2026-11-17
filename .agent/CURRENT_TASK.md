# Current Task

- **Active Issue**: [#120](https://github.com/garanj/threshold/issues/120) — `feat(ci): add tag-driven GitHub Release pipeline, dynamic VERSION_CODE/VERSION_NAME, and 6-tap QR checksum output`
- **Branch**: `feat/120-release-pipeline`
- **Status**: Ready for Review

## Checklist
- [x] `:app`: Support `VERSION_CODE` and `VERSION_NAME` environment variables in `app/build.gradle.kts` with local fallbacks (`1` and `"0.1.0"`)
- [x] `.github/workflows/release.yml`: Add tag-driven (`v*`) & `workflow_dispatch` release pipeline building signed `.apk` and `.aab`, computing URL-safe Base64 SHA-256 checksum for 6-tap QR provisioning, and publishing a GitHub Release
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
