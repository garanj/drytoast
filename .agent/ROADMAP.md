# Threshold Delivery Roadmap

## Phase 1: Test Infrastructure, Core Architecture & Safe Shell (Milestone 1)
- [x] **Task 1.1: Multi-Module Gradle Scaffolding & Hygiene Baseline**
  - Setup `:core` (pure Kotlin JVM), `:data` (Android library), `:app` (Android application), `:benchmark` (Macrobenchmark).
  - Configure Spotless / ktlint, Java 17/21 compatibility, strict compiler flags (`-Werror`).
- [x] **Task 1.2: Shift-Left Quality & Testing Harness**
  - Host JVM screenshot testing harness setup (Roborazzi on `:app`).
  - LeakCanary embedded in debug builds for early memory leak detection.
  - Automated PSS memory ceiling check script/verification (< 30,000 KB PSS).
  - Pure Kotlin unit test suite infrastructure in `:core` (< 5s target execution time).
- [x] **Task 1.3: Core Domain Models & Ports (`:core`)**
  - Implement `ComponentKey`, `AppAccessState`, `FrictionPolicy`, `DndPolicy`, `Profile`.
  - Implement `PackageIndexSource` and `AppAccessController` interfaces.
- [x] **Task 1.4: Universal Dialer AllowList Invariant (`:core`)**
  - Test and verify permanent AllowListing of the system default dialer across all profile filters.
- [x] **Task 1.5: Fast Zero-Allocation Fuzzy Search (`:core`)**
  - Unicode-normalized, zero-allocation fuzzy matching with reverse-search friction logic.
- [x] **Task 1.6: Android Launcher Shell & Package Index (`:data` / `:app`)**
  - **Task 1.6a: `AndroidPackageIndexSource` Implementation & Robolectric Suite (`:data`)**
    - `LauncherApps.getActivityList` for launchable activities across personal and work profiles.
    - Multi-user mapping via `UserManager.getSerialNumberForUser`.
    - Reactive `observePackageChanges()` flow via `LauncherApps.Callback` (`Added`, `Removed`, `Changed`).
    - Fast host JVM Robolectric unit test coverage.
  - **Task 1.6b: Real OS Package Lifecycle Instrumented Test (`:data:androidTest`)**
    - Minimal in-tree test fixture APK (`:fixtures:dummy-app`) compiled directly by Gradle.
    - Device / emulator integration test using `pm install` / `pm uninstall` to verify real Android OS Binder callback delivery.
- [x] **Task 1.7: Typographic Surface & Insets Foundation (`:app`)**
  - Clean typographic home display with OpenType `tnum, zero` numerals and `PlatformTextStyle(includeFontPadding = false)`.
  - WindowInsets handling (3-button vs gesture navigation).
  - Programmatic IME dismissal prior to `startActivity()`.
  - Roborazzi golden screenshot tests for insets and typography modes.
- [x] **Task 1.8: Search Interface & Golden Screenshot Verification (`:app`)**
  - Search surface binding `SearchEngine` (`:core`) to reactive `PackageIndexSource` (`:data`).
  - Reverse-search friction UI and command prefixing (`:` / `,`).
  - Roborazzi host JVM screenshot tests:
    - App appears in search results when installed and user types query.
    - App does not appear when not installed or after uninstall event.
    - Guarded friction visual indicator state in search results.

---

## Phase 2: Sensory Mechanics & Friction Engine (Milestone 2)
- [x] **Task 2.1: Micro-Haptics Engine (`:data` / `:app`)**
  - `VibrationEffect.Composition` implementation: Rotary Detent (`PRIMITIVE_CLICK`), Relay Snap (`PRIMITIVE_HEAVY_CLICK` + 30ms tick), Resistance Detent.
- [x] **Task 2.2: Motion System (Optics, Not Cartoons)**
  - 120Hz decelerating optical curves (`cubic-bezier(0.16, 1, 0.3, 1)`), 80ms–140ms durations.
  - Hairline Shutter Wipe transition (1dp rule).
- [x] **Task 2.3: Intentional Friction Checkpoints**
  - [x] Typed intention check (30 curated phrases, case-insensitive illumination, lock-tumbler resistance) (#35).
  - [x] Drag-to-match icon sorting check (6 monochrome glyph pairs, unified Canvas, 120Hz zero-allocation translation, optical snap-back) (#37).
  - [x] Caret tracing meditative delay checkpoint (3-second continuous trace, optical ease-in/ease-out, hairline track, 14dp indicator) (#43).
  - *(Note: Pre-session budgeting deferred to DeviceOwner mode; Visual app decay discarded by design decision).*
- [x] **Task 2.4: Anti-Metric Display**
  - [x] Anti-metric readout below clock / hairline divider ("REST X · Y UNLOCKS") (#48, #50).
  - *(Note: Flip-to-Silence / Flip-to-DND discarded by design decision; Dual-flank fast capture moved/discarded; Launcher Action Registry moved to Task 8.1).*

---

## Phase 3: Configuration, Base Settings & Profile Cascading Engine (Milestone 3)
- [x] **Task 3.1: Base Settings & Quick Actions Baseline**
  - **General Settings**: Show Unlocks, Show Rest, Require full typing of guarded apps.
  - **Quick Actions**: Up to 7 slots beneath empty search box; reflects calculated state (Blocked = greyed out, Guarded = badge, Standard = launchable); hidden when typing search queries.
  - **Base App Configuration Baseline**: Working base configuration, friction radio group (including dynamic Random evaluation on every launch), and DataStore persistence.
  - **Entry Points**: First-run hint ("long-press to configure", 20s fade, permanent flag), home screen long-press gesture, and `:settings` command.
  - **Reconciliation**: Package uninstall listener automatically removes uninstalled apps from rules and quick actions.
  - **Universal Dialer Invariant**: Permanently Standard; omitted from configuration picker.
- [x] **Task 3.2: Tactile Multi-State Toggle & Unified App Configuration (`:app`)**
  - **Multi-State Toggle Component**: Precision mechanical segmented / slider toggle in Compose with discrete state positions:
    - Base / Default Profile: `[Allow | Guard | Block]`.
    - Custom Profiles: `[Default] | [Allow | Guard | Block]` (`Default` as unmanaged/inactive).
  - **Tactile Ergonomics & Micro-Haptics**: Micro-haptic detent feedback on state transitions (`PRIMITIVE_CLICK` / resistance), optical motion easing, high-contrast monochrome design.
  - **Filter Counter Header Bar**: Minimalist horizontal summary bar (`[ ALL (count) ]  [ BLOCKED (count) ]  [ GUARDED (count) ]  [ ALLOW (count) ]`) allowing instant audit/filtering of restricted apps without losing single-list stability.
  - **Unified App Configuration List**: Replace multi-list triage with a single alphabetical list of all installed apps, each with its multi-state toggle.
  - **Friction Override Integration**: Expandable row or inline options for app-level friction override when set to `Guard`.
- [x] **Task 3.3: Custom Profiles & Cascading Resolution Engine (`:core` / `:data`)**
  - Profile triggers: Schedules (start day/time to end day/time), NFC (duration timer and/or tap-to-relock), Geofence (list of lat/long/radii), Manual.
  - User-ordered cascading evaluation: Top-to-bottom active profile evaluation with base profile fallback.
  - Dynamic profile disable invariant when all app rules are removed due to uninstallation.
  - Zero-allocation cached effective ruleset computation.
- [x] **Task 3.4: Custom Profile Management UI (`:app`)**
  - Profile list management (create, edit, delete, enable/disable, reorder) and reusable `SavedTrigger` library with AND/OR rule evaluation (#63, #86, #91).
  - Reusing the unified multi-state toggle app configuration screen for custom profile overrides.
  - Live active profile indicator below the clock (#82).
  - Tamper resistance: Reboot-staged settings changes (#94) and ADB settings lock (#96).
- [x] **Task 3.5: Launcher Management & Restore Launcher Action (`:app`)** *(Dropped by design decision)*
  - *(Dropped to prevent an un-stageable OS `ACTION_HOME_SETTINGS` escape hatch inside Threshold Settings that would bypass "Require reboot for settings changes". Users can switch default home apps via Android System Settings).*
- [x] **Task 3.6: Onboarding Flow & First-Run Educational Wizard (`:app`)**
  - First-launch detection from Play Store / external launcher (checking if `RoleManager.isRoleHeld(RoleManager.ROLE_HOME)` is false).
  - Swipeable multi-step introduction educating the user on Threshold's philosophy, intent gatekeeping, and tactile mechanics.
  - Direct "Set Threshold as Default Launcher" prompt (`RoleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)`).
  - Seamless handoff into Threshold home screen upon role grant (#100).
- [x] **Task 3.7: Pure Black Wallpaper Utility Action (`:core` / `:data` / `:app`)**
  - One-tap utility action in Settings and `:wallpaper` search shortcut applying a solid `#000000` AMOLED black wallpaper to system and lock screen (#98).

---

## Phase 4: Spatial Context & Geofencing (Milestone 4)
- [x] **Task 4.1: Low-Power Geofencing & Location Integration (`:data` / `:app`)**
  - `AndroidLocationSource` with passive/network/GPS fix caching, telemetry, and inside/outside geofence evaluation (#63, #84).
- [x] **Task 4.2: Temporal Schedule Transitions (`:data` / `:app`)**
  - `AndroidScheduleAlarmScheduler` and `ScheduleAlarmReceiver` using `AlarmManager` exact alarms for schedule boundaries and NFC timed-latch expiries (#63, #86).
- [x] **Task 4.3: Geofence & Schedule State Reconciliation & Safety**
  - Handle location/alarm capability warnings (`AndroidProfileCapabilityChecker`) and boot promotion/rescheduling (`ScheduleAlarmReceiver` `ACTION_BOOT_COMPLETED`) (#63, #94).

---

## Phase 5: Physical NFC Ecosystem (Milestone 5)
- [x] **Task 5.1: Foreground NFC Reader Adapter (`:data` / `:app`)**
  - `AndroidNfcReaderSource` (`NfcAdapter.enableReaderMode` bound strictly to `onResume`/`onPause` with `FLAG_READER_NFC_A | FLAG_READER_NFC_B | FLAG_READER_NFC_F | FLAG_READER_NFC_V | FLAG_READER_SKIP_NDEF_CHECK` and 250ms hardware debounce) (#72, #80).
- [x] **Task 5.2: Physical NFC Triggers, Latches & Timed Passes (`:core` / `:data` / `:app`)**
  - Reusable `NfcTrigger` supporting `TOGGLE` latch on/off (with optional require-same-tag-to-unlock) and `TIMED` countdown pass with configurable `maxDailyUses` (#72, #80, #86, #89).
- [x] **Task 5.3: Multi-Tag & Composable Trigger Rules (`:core` / `:app`)**
  - Composable `TriggerRule` AND/OR evaluation across multiple NFC, Schedule, and Geofence triggers (#86).

---

## Phase 6: Device Owner & DPC Mode (Milestone 6)
- [x] **Task 6.1: DPC Receiver & Dynamic Tier Detection (`:app`)**
  - `ThresholdDeviceAdminReceiver` implementation (`@xml/device_admin_receiver`) and reactive `isDeviceOwnerFlow` via `DevicePolicyManager.isDeviceOwnerApp` (#110).
- [x] **Task 6.2: Kernel-Level Package Suspension (`:core` / `:data` / `:app`)**
  - Diff-reconciled `setPackagesSuspended` via `AppAccessController` port for `Blocked` apps (preserving notifications on `Guarded` apps and permanently exempting the default dialer and system packages) (#110).
  - *(Note: Kiosk `setLockTaskPackages` dropped by design decision; persistent default Home launcher enforced via `addPersistentPreferredActivity`).*
- [x] **Task 6.3: Anti-Tamper Self-Protection & Hardened Mode Education UI (`:app`)**
  - `setUninstallBlocked`, `setUserControlDisabledPackages` (disabling Force Stop & Clear Storage), and `addPersistentPreferredActivity` when Device Owner is active, plus computer-only `DISABLE_HARDENED_MODE` ADB deactivation (#110).
  - Dedicated `HardenedModeScreen` (`ThresholdRoute.HardenedMode`) in Settings with live status, Standard vs. Hardened breakdown, Companion Commitment Layers, and copyable ADB commands (#110).
  - *(Note: `setStatusBarDisabled` notification shade lockdown dropped by design decision).*
- [x] **Task 6.4: 6-Tap QR Provisioning & QR Deep-Link Deactivation**
  - Headless `ThresholdProvisioningActivity` (`GET_PROVISIONING_MODE` & `ADMIN_POLICY_COMPLIANCE`) for Android 12+ 6-tap Welcome screen QR setup (#110).
  - Hidden `DisableHardenedModeScreen` (`threshold://hardened/disable?key=...`) accessible via website QR scan even when Settings are locked (#110).
  - External ADB CLI revocation fallback (`DISABLE_HARDENED_MODE`) (#110).

---

## Phase 7: Hardening, Baseline Profiles & Release Readiness (Milestone 7)
- [x] **Task 7.1: Automated Baseline Profile Generation (`:benchmark`)**
  - `BaselineProfileGenerator` capturing cold/warm launch and search surface paths, plus embedded `app/src/main/baseline-prof.txt` for AOT compilation (#112).
- [x] **Task 7.2: Macrobenchmark Startup & Frame Timing Suite (`:benchmark`)**
  - `StartupAndFrameTimingBenchmark` (`MacrobenchmarkRule`) measuring cold/warm startup and 120Hz frame timing (#112).
- [x] **Task 7.3: Full RTL & Typographic Archetype Audit**
  - Auto-mirrored directional icons (`ArrowBack`, `ChevronRight`, `OpenInNew`) and Roborazzi RTL visual regression goldens across Home, Search, and App Configuration (#112).
- [x] **Task 7.4: CI Hardening & Release Artifact Signing**
  - ProGuard/R8 rules (`app/proguard-rules.pro`), resource shrinking (`isShrinkResources = true`), and environment-driven release keystore signing config (#112).

---

## Phase 8: Miscellaneous & Enhancements (Milestone 8)
- [x] **Task 8.1: Launcher Action Registry & Dispatcher (`:`, `,` prefix)**
  - *(Moved from Phase 2 Task 2.4; dual-flank panels discarded).*
  - Core utility actions (`:settings`, `:wallpaper`) registered as `ActionCandidate` shortcuts in `MainActivity` and `SearchEngine` (#50, #98).
  - Wired `ThresholdHomeScreen` action execution (`SearchItem.Action` in `onItemClick` and IME `Go` quick-launch).
