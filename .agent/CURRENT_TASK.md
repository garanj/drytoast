# Current Task

- **Active Issue**: [#122](https://github.com/garanj/threshold/issues/122) — `fix(nfc): write NDEF tag records on registration and use manifest NDEF dispatch to avoid blocking Google Wallet on home screen`
- **Branch**: `fix/122-nfc-ndef-wallet-compat`
- **Status**: Ready for Review

## Checklist
- [x] `:core`: Add `NfcTagRegistrationResult` (`Success`, `UnsupportedCard`, `ReadOnlyTag`, `InsufficientCapacity`, `WriteInterrupted`) with unit tests
- [x] `:app` (`AndroidNfcReaderSource` & `NfcTagProvisioner`): Scope `enableReaderMode()` strictly to active NFC registration sheet; provision `threshold://tag` NDEF message on registration and emit `NfcTagRegistrationResult`
- [x] `:app` (`NfcTagDispatchActivity` & `AndroidManifest.xml`): Add `Theme.NoDisplay` activity for `android.nfc.action.NDEF_DISCOVERED` (`threshold://tag`) that dispatches scanned tag UIDs and finishes immediately
- [x] `:app` (`TriggerEditorSheet`, `TriggerTypeEditorFields`, `ProfilesScreen`, `ProfileRulesEditorSheet`): Display M3 diagnostic feedback in `NfcEditorFields` when a non-writable or unsupported card is scanned
- [x] Run `./gradlew spotlessApply spotlessCheck test` and open PR assigned to `garanj`
