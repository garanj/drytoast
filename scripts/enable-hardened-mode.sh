#!/usr/bin/env bash
# ==============================================================================
# Threshold — Enable Hardened Mode (Device Owner) Helper Script
# ==============================================================================
# STRONGLY RECOMMENDED FOR NEW / FRESHLY RESET DEVICES ONLY.
#
# Usage:
#   ./scripts/enable-hardened-mode.sh [--check] [--with-account-freeze] [-s <serial>]
#
# Modes:
#   (default)              Runs pre-flight check and executes `dpm set-device-owner`
#                          if no blocking accounts/profiles are present.
#   --check                100% read-only diagnostic of users, profiles, and accounts.
#   --with-account-freeze  Experimental workaround for devices that already have
#                          accounts set up: temporarily freezes account-authenticator
#                          packages (including Google Play Services & 3rd-party apps)
#                          with an on-device self-healing watchdog timer, runs
#                          `dpm set-device-owner`, and immediately re-enables them.
# ==============================================================================

set -euo pipefail

PACKAGE_NAME="com.garan.threshold"
ADMIN_COMPONENT="${PACKAGE_NAME}/.dpc.ThresholdDeviceAdminReceiver"
SYNC_ACTION="com.garan.threshold.action.SYNC_HARDENED_MODE"
LOCK_RECEIVER="${PACKAGE_NAME}/.settings.SettingsLockReceiver"

MODE="standard"
ADB_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)
            MODE="check"
            shift
            ;;
        --with-account-freeze)
            MODE="freeze"
            shift
            ;;
        -s)
            ADB_ARGS+=("-s" "$2")
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--check] [--with-account-freeze] [-s <serial>]" >&2
            exit 1
            ;;
    esac
done

adb_cmd() {
    adb "${ADB_ARGS[@]}" "$@"
}

echo "======================================================================"
echo " Threshold Hardened Mode (Device Owner) Provisioner"
echo "======================================================================"
echo "NOTE: Hardened Mode is strongly recommended for new or freshly reset"
echo "devices before accounts are added. On an existing phone, you can also"
echo "use 'Require reboot for settings changes' + 'LOCK_SETTINGS' via ADB."
echo "----------------------------------------------------------------------"

if ! adb_cmd get-state >/dev/null 2>&1; then
    echo "ERROR: No connected Android device found over ADB." >&2
    exit 1
fi

DEVICE_MODEL="$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
ANDROID_VER="$(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"
echo "Connected device: ${DEVICE_MODEL} (Android ${ANDROID_VER})"

if ! adb_cmd shell pm path "${PACKAGE_NAME}" >/dev/null 2>&1; then
    echo "ERROR: ${PACKAGE_NAME} is not installed on the connected device." >&2
    exit 1
fi

# 1. Inspect multi-user profiles (Work Profile, Private Space, Secure Folder, Dual Apps)
USERS_OUTPUT="$(adb_cmd shell pm list users | tr -d '\r')"
USER_COUNT="$(echo "${USERS_OUTPUT}" | grep -c "UserInfo{" || true)"

echo ""
echo "[1/3] Checking device user profiles (pm list users)..."
echo "${USERS_OUTPUT}"

if [[ "${USER_COUNT}" -gt 1 ]]; then
    echo ""
    echo "WARNING: Multiple user profiles detected (${USER_COUNT} users)."
    echo "Android rejects 'dpm set-device-owner' when Work Profiles, Android 15+"
    echo "Private Space, Samsung Secure Folder, or Dual/Cloned App profiles exist."
fi

# 2. Inspect active accounts & map account types -> authenticator package names
echo ""
echo "[2/3] Inspecting active accounts (dumpsys account)..."
ACCOUNT_DUMP="$(adb_cmd shell dumpsys account | tr -d '\r')"

# Extract active Account {name=..., type=...} types in User 0
ACTIVE_TYPES="$(echo "${ACCOUNT_DUMP}" | sed -n 's/.*Account {name=.*, type=\([^}]*\)}.*/\1/p' | sort -u)"

BLOCKING_PACKAGES=()
if [[ -z "${ACTIVE_TYPES}" ]]; then
    echo "No active accounts found on device."
else
    echo "Active account types detected:"
    while IFS= read -r acc_type; do
        [[ -z "${acc_type}" ]] && continue
        # Lookup AuthenticatorDescription {type=<acc_type>, packageName=<pkg>, ...}
        pkg="$(echo "${ACCOUNT_DUMP}" | grep "AuthenticatorDescription {type=${acc_type}," | head -n 1 | sed -n 's/.*packageName=\([^,}]*\).*/\1/p')"
        echo "  - ${acc_type} (provider package: ${pkg:-unknown})"
        if [[ -n "${pkg}" && "${pkg}" != "android" && "${pkg}" != "${PACKAGE_NAME}" ]]; then
            BLOCKING_PACKAGES+=("${pkg}")
        fi
    done <<< "${ACTIVE_TYPES}"
fi

# Deduplicate BLOCKING_PACKAGES
UNIQUE_PACKAGES=()
if [[ "${#BLOCKING_PACKAGES[@]}" -gt 0 ]]; then
    while IFS= read -r pkg; do
        [[ -n "${pkg}" ]] && UNIQUE_PACKAGES+=("${pkg}")
    done < <(printf "%s\n" "${BLOCKING_PACKAGES[@]}" | sort -u)
fi

if [[ "${MODE}" == "check" ]]; then
    echo ""
    echo "[3/3] Pre-flight check complete (read-only mode)."
    if [[ "${USER_COUNT}" -le 1 && "${#UNIQUE_PACKAGES[@]}" -eq 0 ]]; then
        echo "READY: Device is clean and ready for Hardened Mode activation."
    else
        echo "BLOCKED: ${#UNIQUE_PACKAGES[@]} account provider package(s) and $((USER_COUNT - 1)) extra user profile(s) present."
        echo "Run with '--with-account-freeze' to attempt temporary account hiding, or use a fresh install."
    fi
    exit 0
fi

if [[ "${#UNIQUE_PACKAGES[@]}" -gt 0 && "${MODE}" != "freeze" ]]; then
    echo ""
    echo "ERROR: Active accounts are present on the device (${UNIQUE_PACKAGES[*]})." >&2
    echo "Android will reject 'dpm set-device-owner' while accounts are active." >&2
    echo "" >&2
    echo "Options:" >&2
    echo "  1. Recommended: Keep Standard Mode + lock Threshold Settings via ADB:" >&2
    echo "     adb shell am broadcast -a com.garan.threshold.action.LOCK_SETTINGS -n ${LOCK_RECEIVER}" >&2
    echo "  2. Experimental: Re-run this script with '--with-account-freeze' to temporarily" >&2
    echo "     hide account provider packages during the 2-second provisioning window." >&2
    exit 1
fi

FROZEN_PACKAGES=()

restore_frozen_packages() {
    if [[ "${#FROZEN_PACKAGES[@]}" -gt 0 ]]; then
        echo ""
        echo "Restoring temporarily frozen packages..."
        for pkg in "${FROZEN_PACKAGES[@]}"; do
            adb_cmd shell pm enable "${pkg}" >/dev/null 2>&1 || true
            echo "  + Re-enabled ${pkg}"
        done
    fi
}
trap restore_frozen_packages EXIT INT TERM

if [[ "${MODE}" == "freeze" && "${#UNIQUE_PACKAGES[@]}" -gt 0 ]]; then
    echo ""
    echo "[3/3] Arming on-device 25s self-healing watchdog & temporarily hiding account packages..."
    ENABLE_CMD_LIST=""
    for pkg in "${UNIQUE_PACKAGES[@]}"; do
        ENABLE_CMD_LIST="${ENABLE_CMD_LIST} pm enable ${pkg};"
    done
    # On-device watchdog guarantees packages are re-enabled even if USB cable is unplugged mid-script
    adb_cmd shell "nohup sh -c 'sleep 25; ${ENABLE_CMD_LIST}' >/dev/null 2>&1 &"

    for pkg in "${UNIQUE_PACKAGES[@]}"; do
        echo "  - Temporarily freezing ${pkg}..."
        if adb_cmd shell pm disable-user --user 0 "${pkg}" >/dev/null 2>&1; then
            FROZEN_PACKAGES+=("${pkg}")
        else
            echo "    (Warning: OS refused to disable ${pkg}; continuing...)"
        fi
    done
else
    echo ""
    echo "[3/3] Setting Threshold as Device Owner..."
fi

if adb_cmd shell dpm set-device-owner "${ADMIN_COMPONENT}"; then
    restore_frozen_packages
    FROZEN_PACKAGES=()
    adb_cmd shell am broadcast -a "${SYNC_ACTION}" -n "${LOCK_RECEIVER}" >/dev/null 2>&1 || true
    echo ""
    echo "SUCCESS: Threshold Hardened Mode (Device Owner) is now ACTIVE."
else
    echo "" >&2
    echo "FAILED: Android rejected 'dpm set-device-owner'." >&2
    exit 1
fi
