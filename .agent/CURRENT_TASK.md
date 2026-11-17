# Current Task

- **Active Issue**: [#114](https://github.com/garanj/threshold/issues/114) — `fix(hardening): resolve red-team audit findings across package replacement, DPC restrictions, background reconciliation & UI strings`
- **Branch**: `fix/114-red-team-audit-fixes`
- **Status**: Ready for Review

## Checklist
- [x] `:data`: Guard `AndroidPackageIndexSource.onPackagesUnavailable` / `onPackagesAvailable` when `replacing == true` and add unit tests in `AndroidPackageIndexSourceTest`
- [x] `:app`: Add `addUserRestriction` / `clearUserRestriction` (`DISALLOW_ADD_USER`, `DISALLOW_SAFE_BOOT`) to `DpcPolicyBridge` and `AndroidAppAccessController`, and verify in `AndroidAppAccessControllerTest`
- [x] `:app`: Refresh location for enabled Geo profiles and persist pruned `activeNfcLatches` in `ThresholdAppContainer.reconcileDpcFromSettings()`
- [x] `:app`: Ensure `ThresholdProvisioningActivity` applies self-protection before completing `ACTION_ADMIN_POLICY_COMPLIANCE`
- [x] `:app`: Externalize `CapabilityFaultBottomSheet` and `ThresholdHomeCanvas` strings to `strings.xml` and make fault rows clickable
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
