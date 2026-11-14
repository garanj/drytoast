# Current Task

- **Active Issue**: [#98](https://github.com/garanj/threshold/issues/98) — `feat(wallpaper): add one-tap pure black wallpaper utility action`
- **Branch**: `feat/98-black-wallpaper-utility`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Define `WallpaperController` port (`applyPureBlackWallpaper(): Boolean`)
- [x] `:data`: Implement `AndroidWallpaperController` with ephemeral 1x1 black `Bitmap` and unit tests
- [x] `:app`: Add `SET_WALLPAPER` permission to `AndroidManifest.xml`, wire `WallpaperController` in `ThresholdAppContainer` & `MainActivity`, add `"Set pure black wallpaper"` action + confirmation `AlertDialog` in `ThresholdSettingsScreen` and `:wallpaper` search action in `ThresholdHomeScreen`
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
