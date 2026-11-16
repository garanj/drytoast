# Current Task

- **Active Issue**: [#110](https://github.com/garanj/threshold/issues/110) — `feat(dpc): implement Phase 6 Hardened Mode (Device Owner DPC, package suspension, anti-tamper & education UI)`
- **Branch**: `feat/110-hardened-mode-dpc`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Expand `AppAccessController` port (`isDeviceOwnerFlow`, `reconcileBlockedPackages`, `enforceHardenedSelfProtection`, `deactivateHardenedMode`) and `SettingsRepository` (`dpcSuspendedPackages` persistence for restart recovery)
- [x] `:data`: Persist `dpc_suspended_packages` in `AndroidSettingsRepository`
- [x] `:app`: Add `ThresholdDeviceAdminReceiver` and `res/xml/device_admin_receiver.xml`, registered in `AndroidManifest.xml`
- [x] `:app`: Implement `AndroidAppAccessController` enforcing `setPackagesSuspended` (with Universal Dialer & self-package protection), `setUninstallBlocked`, `setUserControlDisabledPackages`, and `addPersistentPreferredActivity`, plus `DISABLE_HARDENED_MODE` cleanup
- [x] `:app`: Wire `AppAccessController` reconciliation into `SearchViewModel`, `ScheduleAlarmReceiver`, `ThresholdDeviceAdminReceiver`, and `SettingsLockReceiver`
- [x] `:app`: Add `"Hardened mode"` row in `ThresholdSettingsScreen`, `ThresholdRoute.HardenedMode`, and `HardenedModeScreen` with live status, Standard vs. Hardened breakdown, Companion Locks, and copyable ADB commands
- [x] Write extensive unit, integration, and Roborazzi screenshot tests
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
