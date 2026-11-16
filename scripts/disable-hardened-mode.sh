#!/usr/bin/env bash
# ==============================================================================
# Threshold — Disable Hardened Mode (Device Owner) Helper Script
# ==============================================================================
# Cleanly unsuspends all packages, removes uninstall/Force-Stop/launcher locks,
# and calls DevicePolicyManager.clearDeviceOwnerApp().
#
# Usage:
#   ./scripts/disable-hardened-mode.sh [-s <serial>]
# ==============================================================================

set -euo pipefail

PACKAGE_NAME="com.garan.threshold"
DISABLE_ACTION="com.garan.threshold.action.DISABLE_HARDENED_MODE"
LOCK_RECEIVER="${PACKAGE_NAME}/.settings.SettingsLockReceiver"

adb "$@" shell am broadcast -a "${DISABLE_ACTION}" -n "${LOCK_RECEIVER}"
echo "Dispatched DISABLE_HARDENED_MODE to Threshold. Hardened Mode is now deactivated."
