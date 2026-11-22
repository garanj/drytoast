/**
 * Threshold Expert Mode: Guided WebUSB Hardened Mode (Device Owner) Provisioner.
 *
 * 4-Stage Pipeline:
 *   1. Connect & Authorize Device (WebUSB ADB)
 *   2. Suitability & Accounts Audit (`pm list users`, `dpm list-owners`, `dumpsys account`)
 *      - If third-party (non-com.google) accounts exist, lists them with a one-tap shortcut to
 *        open Android Accounts Settings (`am start -a android.settings.SYNC_SETTINGS`) and a live
 *        Re-check button while reminding the user to leave their personal Google account intact.
 *   3. testOnly Bridge -> `dpm set-device-owner` -> pause 2s -> in-place Production APK upgrade
 *   4. Verify active Device Owner (`dpm list-owners`) & clean up `/data/local/tmp/*.apk`
 */
(function () {
  "use strict";

  const ADMIN_COMPONENT = "com.garan.threshold/.dpc.ThresholdDeviceAdminReceiver";
  const TESTONLY_URL = "/downloads/threshold-testonly.bin";
  const RELEASE_URL = "/downloads/threshold-release.bin";

  let session = null;
  let auditResult = null;
  let isRunning = false;

  const els = {
    browserWarn: document.getElementById("expert-browser-warn"),
    btnConnect: document.getElementById("btn-expert-connect"),
    btnDisconnect: document.getElementById("btn-expert-disconnect"),
    deviceSummary: document.getElementById("expert-device-summary"),
    auditCard: document.getElementById("expert-audit-card"),
    auditList: document.getElementById("expert-audit-list"),
    auditBlockers: document.getElementById("expert-audit-blockers"),
    accountsGuide: document.getElementById("expert-accounts-guide"),
    ackWrap: document.getElementById("expert-ack-wrap"),
    ackCheckbox: document.getElementById("expert-ack-checkbox"),
    btnRun: document.getElementById("btn-expert-run"),
    stageList: document.getElementById("expert-stage-list"),
    progressWrap: document.getElementById("expert-progress-wrap"),
    progressBar: document.getElementById("expert-progress-bar"),
    progressText: document.getElementById("expert-progress-text"),
    logEl: document.getElementById("expert-log"),
    btnOpenAccounts: document.getElementById("btn-expert-open-accounts"),
    btnRepairApk: document.getElementById("btn-expert-repair-apk"),
    overrideTestonly: document.getElementById("expert-file-testonly"),
    overrideRelease: document.getElementById("expert-file-release"),
  };

  if (!els.btnConnect) return;

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function log(msg, level) {
    const ts = new Date().toTimeString().slice(0, 8);
    const prefix = level === "error" ? "[ERR] " : level === "ok" ? "[OK]  " : level === "warn" ? "[WRN] " : "[...] ";
    els.logEl.textContent += ts + " " + prefix + msg + "\n";
    els.logEl.scrollTop = els.logEl.scrollHeight;
  }

  function setProgress(pct, text) {
    els.progressWrap.style.display = "block";
    els.progressBar.style.width = Math.max(0, Math.min(100, pct)) + "%";
    els.progressText.textContent = text;
  }

  function hideProgress() {
    els.progressWrap.style.display = "none";
  }

  function setStageState(stageNum, state, detail) {
    const item = els.stageList.querySelector('[data-stage="' + stageNum + '"]');
    if (!item) return;
    item.setAttribute("data-state", state);
    const badge = item.querySelector(".stage-state");
    if (badge) {
      const labels = {
        idle: "PENDING",
        active: "RUNNING",
        ok: "DONE",
        warn: "ACTION NEEDED",
        error: "FAILED",
      };
      badge.textContent = labels[state] || state.toUpperCase();
    }
    const sub = item.querySelector(".stage-detail");
    if (sub && detail !== undefined) {
      sub.textContent = detail;
    }
  }

  function shellQuote(arg) {
    return "'" + String(arg).replace(/'/g, "'\\''") + "'";
  }

  function sleep(ms) {
    return new Promise(function (resolve) {
      setTimeout(resolve, ms);
    });
  }

  function parseUsers(pmListUsersOutput) {
    const users = [];
    const regex = /UserInfo\{(\d+):([^:}]+):([0-9a-fA-F]+)\}/g;
    let m;
    while ((m = regex.exec(pmListUsersOutput)) !== null) {
      users.push({ id: parseInt(m[1], 10), name: m[2], flags: m[3] });
    }
    return users;
  }

  function parseAccountsFromDumpsys(dumpsysAccountOutput) {
    const googleAccounts = [];
    const nonGoogleAccounts = [];
    const seen = new Set();
    const acctRegex = /Account\s*\{name=([^,}]+),\s*type=([^}\s]+)\}/g;
    let m;
    while ((m = acctRegex.exec(dumpsysAccountOutput)) !== null) {
      const name = m[1].trim();
      const type = m[2].trim();
      const key = type + "::" + name;
      if (seen.has(key)) continue;
      seen.add(key);
      if (type === "com.google") {
        googleAccounts.push({ name: name, type: type });
      } else {
        nonGoogleAccounts.push({ name: name, type: type });
      }
    }
    return {
      googleAccounts: googleAccounts,
      nonGoogleAccounts: nonGoogleAccounts,
    };
  }

  async function openAccountsSettingsOnPhone() {
    if (!session) return;
    try {
      log("Opening Android Accounts Settings on phone...");
      const res = await window.ThresholdWebAdb.runShell(
        session.adb,
        "am start -a android.settings.SYNC_SETTINGS"
      );
      log("Launched SYNC_SETTINGS: " + (res.output || "OK"), "ok");
    } catch (err) {
      log("Could not open Accounts Settings: " + (err.message || String(err)), "error");
    }
  }

  async function fetchBinaryPayload(url, fileInput, label) {
    if (fileInput && fileInput.files && fileInput.files.length > 0) {
      log("Using local override file for " + label + ": " + fileInput.files[0].name);
      const buf = await fileInput.files[0].arrayBuffer();
      return new Uint8Array(buf);
    }
    log("Downloading " + label + " from " + url + "...");
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) {
      throw new Error(
        "Could not download " + label + " (" + url + ", HTTP " + res.status + "). " +
        "Cut a new release tag so GitHub Actions publishes both payloads, or select local APKs in Advanced Overrides below."
      );
    }
    const buf = await res.arrayBuffer();
    if (buf.byteLength < 100000) {
      throw new Error("Downloaded " + label + " looks invalid (" + buf.byteLength + " bytes).");
    }
    return new Uint8Array(buf);
  }

  function updateRunButtonState() {
    const canRun = Boolean(
      session &&
      auditResult &&
      auditResult.suitable &&
      !auditResult.alreadyOwner &&
      auditResult.nonGoogleAccounts.length === 0 &&
      els.ackCheckbox.checked &&
      !isRunning
    );
    els.btnRun.disabled = !canRun;
    els.btnOpenAccounts.disabled = !session || isRunning;
    els.btnRepairApk.disabled = !session || isRunning;
  }

  function resetAuditUi() {
    auditResult = null;
    els.auditCard.style.display = "none";
    els.auditList.innerHTML = "";
    els.auditBlockers.style.display = "none";
    els.auditBlockers.innerHTML = "";
    els.accountsGuide.style.display = "none";
    els.accountsGuide.innerHTML = "";
    els.ackWrap.style.display = "none";
    els.ackCheckbox.checked = false;
    for (let i = 1; i <= 4; i++) {
      setStageState(i, "idle", undefined);
    }
    hideProgress();
    updateRunButtonState();
  }

  function renderAccountsGuide(googleAccounts, nonGoogleAccounts, requireAllAccounts) {
    const listToShow = requireAllAccounts
      ? nonGoogleAccounts.concat(googleAccounts)
      : nonGoogleAccounts;

    if (listToShow.length === 0) {
      els.accountsGuide.style.display = "none";
      els.accountsGuide.innerHTML = "";
      return;
    }

    const googleNames = googleAccounts.map(function (a) { return escapeHtml(a.name); }).join(", ");
    const safeBanner = !requireAllAccounts && googleAccounts.length > 0
      ? '<div class="accounts-guide-safe"><strong>Keep your Google account signed in:</strong> ' +
        googleNames +
        '. Only remove the third-party accounts listed below.</div>'
      : requireAllAccounts
        ? '<div class="accounts-guide-safe"><strong>All remaining accounts must be temporarily removed:</strong> Android flagged the remaining Google account as incompatible (common on Google Workspace, supervised, or restricted accounts). Remove it temporarily, complete provisioning, then sign back in.</div>'
        : "";

    const itemsHtml = listToShow.map(function (acct) {
      return (
        '<li>' +
        '<span><strong>' + escapeHtml(acct.name) + '</strong></span>' +
        '<span class="accounts-guide-type">' + escapeHtml(acct.type) + '</span>' +
        '</li>'
      );
    }).join("");

    els.accountsGuide.innerHTML =
      safeBanner +
      '<div><strong>Remove these ' + listToShow.length + ' account(s) on your phone before continuing:</strong></div>' +
      '<ul class="accounts-guide-list">' + itemsHtml + '</ul>' +
      '<p class="accounts-guide-note">For messaging apps like WhatsApp, Signal, and Telegram, removing the entry in Android Settings only removes the Contacts sync account (not your chats); the app recreates it when opened.</p>' +
      '<div class="btn-row">' +
      '<button type="button" id="btn-guide-open-accounts" class="btn-primary">Open Accounts Settings on Phone</button>' +
      '<button type="button" id="btn-guide-recheck" class="btn-secondary">Re-check Accounts</button>' +
      '</div>';

    els.accountsGuide.style.display = "block";

    const btnOpen = document.getElementById("btn-guide-open-accounts");
    const btnRecheck = document.getElementById("btn-guide-recheck");
    if (btnOpen) {
      btnOpen.addEventListener("click", openAccountsSettingsOnPhone);
    }
    if (btnRecheck) {
      btnRecheck.addEventListener("click", function () {
        if (!isRunning && session) {
          runPreflightAudit();
        }
      });
    }
  }

  async function runPreflightAudit() {
    setStageState(2, "active", "Inspecting users, Device Owner state, and active accounts...");
    log("Stage 2: Running read-only suitability audit...");

    const [manufacturerRes, modelRes, releaseRes, sdkRes, usersRes, ownersRes, accountsRes] = await Promise.all([
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.product.manufacturer"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.product.model"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.build.version.release"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.build.version.sdk"),
      window.ThresholdWebAdb.runShell(session.adb, "pm list users"),
      window.ThresholdWebAdb.runShell(session.adb, "dpm list-owners"),
      window.ThresholdWebAdb.runShell(session.adb, "dumpsys account"),
    ]);

    const manufacturer = manufacturerRes.output.trim();
    const model = modelRes.output.trim();
    const androidRelease = releaseRes.output.trim();
    const sdkInt = parseInt(sdkRes.output.trim(), 10) || 0;
    const users = parseUsers(usersRes.output);
    const ownersText = ownersRes.output.trim();
    const parsedAccounts = parseAccountsFromDumpsys(accountsRes.output);
    const googleAccounts = parsedAccounts.googleAccounts;
    const nonGoogleAccounts = parsedAccounts.nonGoogleAccounts;

    const alreadyOwner = ownersText.includes("com.garan.threshold");
    const otherOwner = !alreadyOwner && /Device Owner|deviceOwner/i.test(ownersText) && !/No device owner/i.test(ownersText) && ownersText.length > 0 && !ownersText.includes("null");
    const secondaryUsers = users.filter(function (u) { return u.id !== 0; });

    const blockers = [];
    if (sdkInt > 0 && sdkInt < 29) {
      blockers.push("Android " + androidRelease + " (API " + sdkInt + ") is below Threshold's minimum Android 10 (API 29).");
    }
    if (secondaryUsers.length > 0) {
      const names = secondaryUsers.map(function (u) {
        return "User " + u.id + " (" + u.name + ")";
      }).join(", ");
      blockers.push(
        "Multiple users/profiles detected: " + names + ". Android blocks Device Owner whenever a Work Profile, Private Space, Samsung Secure Folder, Dual Apps profile, or Guest User exists. Remove those secondary profiles in Android Settings first, then reconnect."
      );
    }
    if (otherOwner) {
      blockers.push("Another Device Owner is already active on this phone: " + ownersText);
    }

    const suitable = blockers.length === 0;

    auditResult = {
      deviceName: (manufacturer + " " + model).trim() || session.device.serial,
      androidRelease: androidRelease,
      sdkInt: sdkInt,
      users: users,
      secondaryUsers: secondaryUsers,
      alreadyOwner: alreadyOwner,
      otherOwner: otherOwner,
      googleAccounts: googleAccounts,
      nonGoogleAccounts: nonGoogleAccounts,
      suitable: suitable,
      blockers: blockers,
    };

    els.deviceSummary.textContent = auditResult.deviceName + " · Android " + androidRelease + " (API " + sdkInt + ")";
    els.auditCard.style.display = "block";

    const accountSummary = nonGoogleAccounts.length === 0
      ? (googleAccounts.length > 0
          ? "0 blocking third-party accounts (" + googleAccounts.length + " Google account kept)"
          : "0 accounts on device (ready)")
      : nonGoogleAccounts.length + " third-party account(s) must be temporarily removed in Settings";

    const rows = [
      {
        label: "Device & OS",
        value: auditResult.deviceName + " (Android " + androidRelease + ")",
        status: sdkInt >= 29 || sdkInt === 0 ? "PASS" : "FAIL",
      },
      {
        label: "Users / Work Profile / Private Space",
        value: secondaryUsers.length === 0
          ? "Single user (User 0 only)"
          : secondaryUsers.length + " secondary profile(s) found",
        status: secondaryUsers.length === 0 ? "PASS" : "BLOCKER",
      },
      {
        label: "Current Device Owner",
        value: alreadyOwner
          ? "Threshold is already Device Owner"
          : otherOwner
            ? "Another Device Owner is active"
            : "None (Ready)",
        status: alreadyOwner ? "ACTIVE" : otherOwner ? "BLOCKER" : "PASS",
      },
      {
        label: "Blocking Accounts",
        value: accountSummary,
        status: nonGoogleAccounts.length === 0 ? "PASS" : "ACTION",
      },
    ];

    els.auditList.innerHTML = rows.map(function (row) {
      const cls = row.status === "BLOCKER" || row.status === "FAIL"
        ? "audit-pill fail"
        : row.status === "ACTION"
          ? "audit-pill warn"
          : "audit-pill ok";
      return (
        '<div class="audit-row">' +
        '<div><strong>' + escapeHtml(row.label) + '</strong><div class="audit-val">' + escapeHtml(row.value) + '</div></div>' +
        '<span class="' + cls + '">' + escapeHtml(row.status) + '</span>' +
        '</div>'
      );
    }).join("");

    els.auditBlockers.style.display = "none";
    els.auditBlockers.innerHTML = "";
    els.accountsGuide.style.display = "none";
    els.accountsGuide.innerHTML = "";
    els.ackWrap.style.display = "none";

    if (blockers.length > 0) {
      els.auditBlockers.style.display = "block";
      els.auditBlockers.innerHTML = "<strong>Cannot proceed: hard OS blocker detected.</strong><ul>" +
        blockers.map(function (b) { return "<li>" + escapeHtml(b) + "</li>"; }).join("") +
        "</ul>";
      setStageState(2, "error", "Blocked by OS preflight check");
      log("Preflight audit found " + blockers.length + " blocker(s).", "error");
    } else if (alreadyOwner) {
      setStageState(2, "ok", "Threshold is already Device Owner on this device");
      log("Threshold is already Device Owner on this phone.", "ok");
    } else if (nonGoogleAccounts.length > 0) {
      renderAccountsGuide(googleAccounts, nonGoogleAccounts, false);
      setStageState(2, "warn", "Remove " + nonGoogleAccounts.length + " third-party account(s) in Settings, then click Re-check");
      log("Found " + nonGoogleAccounts.length + " blocking third-party account(s). Use 'Open Accounts Settings on Phone' to remove them, then click 'Re-check Accounts'.", "warn");
    } else {
      setStageState(2, "ok", "Preflight passed. Ready for 1-click provisioning.");
      els.ackWrap.style.display = "block";
      log("Preflight audit passed! Ready to activate Hardened Mode.", "ok");
    }

    updateRunButtonState();
  }

  async function pushAndInstallApk(bytes, remotePath, flags, label, startPct, endPct) {
    setProgress(startPct, "Pushing " + label + " over USB...");
    await window.ThresholdWebAdb.pushFile(session.adb, remotePath, bytes, function (sent, total) {
      const ratio = total > 0 ? sent / total : 1;
      const pct = startPct + ratio * (endPct - startPct - 5);
      setProgress(pct, "Pushing " + label + " (" + Math.round(ratio * 100) + "%)...");
    });
    setProgress(endPct - 3, "Installing " + label + " on phone...");
    const cmd = "pm install " + flags + " " + shellQuote(remotePath);
    log("Running: " + cmd);
    const res = await window.ThresholdWebAdb.runShell(session.adb, cmd);
    log("Install output: " + res.output);
    if (!res.output.includes("Success")) {
      if (res.output.includes("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
        throw new Error(
          "Signature mismatch (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Uninstall the existing Threshold build on your phone first and retry."
        );
      }
      throw new Error("APK install failed for " + label + ": " + res.output);
    }
    setProgress(endPct, label + " installed.");
  }

  async function runExpertProvisioning() {
    if (!session || !auditResult || !auditResult.suitable || auditResult.nonGoogleAccounts.length > 0 || isRunning) {
      return;
    }
    isRunning = true;
    updateRunButtonState();

    try {
      setStageState(3, "active", "Downloading Threshold bridge & production APKs...");
      setProgress(5, "Downloading Threshold bridge & production APKs...");
      const testOnlyBytes = await fetchBinaryPayload(TESTONLY_URL, els.overrideTestonly, "testOnly bridge APK");
      const releaseBytes = await fetchBinaryPayload(RELEASE_URL, els.overrideRelease, "production release APK");
      log("Both APK payloads downloaded and verified in memory.", "ok");

      // STAGE 3: testOnly bridge -> dpm set-device-owner -> pause -> production APK
      setStageState(3, "active", "Installing testOnly bridge APK...");
      await pushAndInstallApk(
        testOnlyBytes,
        "/data/local/tmp/threshold-testonly.apk",
        "-r -t -i com.android.vending",
        "testOnly bridge APK",
        15,
        45
      );

      setStageState(3, "active", "Activating Device Owner...");
      setProgress(52, "Running dpm set-device-owner...");
      const dpmRes = await window.ThresholdWebAdb.runShell(
        session.adb,
        "dpm set-device-owner " + ADMIN_COMPONENT
      );
      log("dpm set-device-owner -> " + dpmRes.output);
      if (!dpmRes.output.includes("Success")) {
        if (/already some accounts/i.test(dpmRes.output) && auditResult.googleAccounts.length > 0) {
          log("Android rejected the remaining Google account as incompatible. Temporarily remove it in Accounts Settings, then click Re-check Accounts.", "warn");
          renderAccountsGuide(auditResult.googleAccounts, [], true);
        }
        throw new Error("dpm set-device-owner failed: " + dpmRes.output);
      }

      setStageState(3, "active", "Device Owner active. Pausing before production upgrade...");
      setProgress(60, "Pausing 2s before upgrading to production APK...");
      await sleep(2000);

      setStageState(3, "active", "Upgrading in-place to production APK (clearing testOnly flag)...");
      await pushAndInstallApk(
        releaseBytes,
        "/data/local/tmp/threshold-release.apk",
        "-r -i com.android.vending",
        "production release APK",
        65,
        92
      );

      await window.ThresholdWebAdb.runShell(
        session.adb,
        "rm -f /data/local/tmp/threshold-testonly.apk /data/local/tmp/threshold-release.apk"
      );
      setStageState(3, "ok", "Device Owner active & upgraded to production APK");

      // STAGE 4: Verify active Device Owner
      setStageState(4, "active", "Verifying active Device Owner...");
      setProgress(96, "Verifying Device Owner...");
      const verifyRes = await window.ThresholdWebAdb.runShell(session.adb, "dpm list-owners");
      log("Final verification (dpm list-owners): " + verifyRes.output);
      if (!verifyRes.output.includes("com.garan.threshold")) {
        throw new Error("Verification failed: Threshold not listed as Device Owner.");
      }

      setProgress(100, "Hardened Mode provisioned successfully!");
      setStageState(4, "ok", "Hardened Mode active and verified");
      log("Expert provisioning complete! You may safely unplug your phone and re-open your apps.", "ok");
    } catch (err) {
      log(err.message || String(err), "error");
      const activeStage = els.stageList.querySelector('[data-state="active"]');
      if (activeStage) {
        setStageState(activeStage.getAttribute("data-stage"), "error", err.message || "Failed");
      }
    } finally {
      isRunning = false;
      updateRunButtonState();
    }
  }

  // Browser support check
  if (!window.ThresholdWebAdb || !window.ThresholdWebAdb.isWebUsbSupported()) {
    els.browserWarn.style.display = "block";
    els.btnConnect.disabled = true;
    return;
  }

  els.btnConnect.addEventListener("click", async function () {
    if (isRunning) return;
    resetAuditUi();
    setStageState(1, "active", "Select your phone in the browser USB prompt and tap Allow on your phone...");
    log("Requesting WebUSB ADB connection...");

    try {
      if (session) {
        await window.ThresholdWebAdb.disconnectDevice(session);
        session = null;
      }
      const connected = await window.ThresholdWebAdb.connectDevice();
      if (!connected) {
        setStageState(1, "idle", "No device selected");
        log("Device picker cancelled.");
        return;
      }
      session = connected;
      els.btnConnect.style.display = "none";
      els.btnDisconnect.style.display = "inline-block";
      setStageState(1, "ok", "Connected via WebUSB (" + session.device.serial + ")");
      log("Authorized WebUSB connection to " + session.device.serial, "ok");

      await runPreflightAudit();
    } catch (err) {
      const msg = err && err.message ? err.message : String(err);
      let hint = msg;
      if (/claim interface|busy|AccessDenied/i.test(msg)) {
        hint = "USB interface is busy. Close Android Studio or run 'adb kill-server' in your terminal, then click Connect again.";
      } else if (/SecurityError|NotFoundError/i.test(msg)) {
        hint = "No ADB device selected. Ensure USB Debugging is enabled in Developer Options and the USB cable supports data.";
      }
      setStageState(1, "error", hint);
      log(hint, "error");
    }
  });

  els.btnDisconnect.addEventListener("click", async function () {
    if (isRunning) return;
    await window.ThresholdWebAdb.disconnectDevice(session);
    session = null;
    els.btnConnect.style.display = "inline-block";
    els.btnDisconnect.style.display = "none";
    els.deviceSummary.textContent = "";
    resetAuditUi();
    log("Disconnected from device.");
  });

  els.ackCheckbox.addEventListener("change", updateRunButtonState);
  els.btnRun.addEventListener("click", runExpertProvisioning);
  els.btnOpenAccounts.addEventListener("click", openAccountsSettingsOnPhone);

  els.btnRepairApk.addEventListener("click", async function () {
    if (!session || isRunning) return;
    isRunning = true;
    updateRunButtonState();
    try {
      const releaseBytes = await fetchBinaryPayload(RELEASE_URL, els.overrideRelease, "production release APK");
      await pushAndInstallApk(
        releaseBytes,
        "/data/local/tmp/threshold-release.apk",
        "-r -i com.android.vending",
        "production release APK",
        10,
        95
      );
      await window.ThresholdWebAdb.runShell(session.adb, "rm -f /data/local/tmp/threshold-release.apk");
      setProgress(100, "Production APK installed.");
      log("Production APK repair complete.", "ok");
    } catch (err) {
      log(err.message || String(err), "error");
    } finally {
      isRunning = false;
      updateRunButtonState();
    }
  });
})();
