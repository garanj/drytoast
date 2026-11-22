/**
 * Threshold Expert Mode: 2-Phase WebUSB Hardened Mode (Device Owner) Provisioner.
 *
 * Why 2 phases when non-Google accounts exist?
 * Android's DevicePolicyManagerService caches `mHasIncompatibleAccounts` and only recalculates it
 * on BOOT_COMPLETED or LOGIN_ACCOUNTS_CHANGED (which is not sent when apps are disabled without
 * deleting accounts).
 *
 * Phase 1 (only if non-Google accounts exist):
 *   - Discover active non-com.google account packages via `dumpsys account`
 *   - Save package list to localStorage, run `pm disable-user --user 0 <pkg>` (FLAG_INSTALLED stays
 *     set so AccountManagerService preserves all accounts in AccountsDb), and reboot the phone.
 * Phase 2 (after reboot, or single-pass if 0 non-Google accounts):
 *   - Install testOnly bridge APK -> `dpm set-device-owner` -> pause 2s -> upgrade in-place to
 *     production release APK -> restore all paused packages via `pm enable <pkg>` -> verify.
 */
(function () {
  "use strict";

  const STORAGE_KEY = "threshold_expert_paused_packages";
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
    ackWrap: document.getElementById("expert-ack-wrap"),
    ackCheckbox: document.getElementById("expert-ack-checkbox"),
    btnRun: document.getElementById("btn-expert-run"),
    stageList: document.getElementById("expert-stage-list"),
    progressWrap: document.getElementById("expert-progress-wrap"),
    progressBar: document.getElementById("expert-progress-bar"),
    progressText: document.getElementById("expert-progress-text"),
    logEl: document.getElementById("expert-log"),
    btnRestore: document.getElementById("btn-expert-restore"),
    btnRepairApk: document.getElementById("btn-expert-repair-apk"),
    overrideTestonly: document.getElementById("expert-file-testonly"),
    overrideRelease: document.getElementById("expert-file-release"),
  };

  if (!els.btnConnect) return;

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
        warn: "WAITING",
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

  function savePausedPackages(packages) {
    try {
      if (packages && packages.length > 0) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(packages));
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    } catch (_) {}
  }

  function loadPausedPackages() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch (_) {
      return [];
    }
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

  function parsePackagesList(pmListOutput) {
    const pkgs = [];
    pmListOutput.split(/\r?\n/).forEach(function (line) {
      const trimmed = line.trim();
      if (trimmed.startsWith("package:")) {
        pkgs.push(trimmed.slice("package:".length).trim());
      }
    });
    return pkgs;
  }

  function parseIncompatibleAccountPackagesFromDumpsys(dumpsysAccountOutput) {
    const activeTypes = new Set();
    const acctRegex = /Account\s*\{[^}]*\btype=([^,}\s]+)/g;
    let m;
    while ((m = acctRegex.exec(dumpsysAccountOutput)) !== null) {
      const type = m[1].trim();
      if (type && type !== "com.google") {
        activeTypes.add(type);
      }
    }

    const typeToPackage = new Map();
    const svcRegex = /AuthenticatorDescription\s*\{type=([^}\s]+)\},\s*ComponentInfo\{([a-zA-Z0-9_.]+)\//g;
    while ((m = svcRegex.exec(dumpsysAccountOutput)) !== null) {
      typeToPackage.set(m[1].trim(), m[2].trim());
    }

    const packages = new Set();
    for (const type of activeTypes) {
      const pkg = typeToPackage.get(type);
      if (pkg && pkg !== "com.google.android.gms" && pkg !== "com.garan.threshold") {
        packages.add(pkg);
      }
    }
    return Array.from(packages).sort();
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
      els.ackCheckbox.checked &&
      !isRunning
    );
    els.btnRun.disabled = !canRun;
    els.btnRestore.disabled = !session || isRunning;
    els.btnRepairApk.disabled = !session || isRunning;
  }

  function resetAuditUi() {
    auditResult = null;
    els.auditCard.style.display = "none";
    els.auditList.innerHTML = "";
    els.auditBlockers.style.display = "none";
    els.auditBlockers.innerHTML = "";
    els.ackWrap.style.display = "none";
    els.ackCheckbox.checked = false;
    for (let i = 1; i <= 5; i++) {
      setStageState(i, "idle", undefined);
    }
    hideProgress();
    updateRunButtonState();
  }

  async function runPreflightAudit() {
    setStageState(2, "active", "Inspecting users, Device Owner state, and active accounts...");
    log("Stage 2: Running read-only suitability audit...");

    const [manufacturerRes, modelRes, releaseRes, sdkRes, usersRes, ownersRes, accountsRes, disabledRes] = await Promise.all([
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.product.manufacturer"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.product.model"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.build.version.release"),
      window.ThresholdWebAdb.runShell(session.adb, "getprop ro.build.version.sdk"),
      window.ThresholdWebAdb.runShell(session.adb, "pm list users"),
      window.ThresholdWebAdb.runShell(session.adb, "dpm list-owners"),
      window.ThresholdWebAdb.runShell(session.adb, "dumpsys account"),
      window.ThresholdWebAdb.runShell(session.adb, "pm list packages -d"),
    ]);

    const manufacturer = manufacturerRes.output.trim();
    const model = modelRes.output.trim();
    const androidRelease = releaseRes.output.trim();
    const sdkInt = parseInt(sdkRes.output.trim(), 10) || 0;
    const users = parseUsers(usersRes.output);
    const ownersText = ownersRes.output.trim();
    const activeNonGooglePkgs = parseIncompatibleAccountPackagesFromDumpsys(accountsRes.output);
    const disabledSet = new Set(parsePackagesList(disabledRes.output));
    const savedPaused = loadPausedPackages().filter(function (p) { return disabledSet.has(p); });

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

    const needsPhase1Reboot = activeNonGooglePkgs.length > 0;
    const isPhase2Resume = !needsPhase1Reboot && savedPaused.length > 0;
    const suitable = blockers.length === 0;

    auditResult = {
      deviceName: (manufacturer + " " + model).trim() || session.device.serial,
      androidRelease: androidRelease,
      sdkInt: sdkInt,
      users: users,
      secondaryUsers: secondaryUsers,
      alreadyOwner: alreadyOwner,
      otherOwner: otherOwner,
      activeNonGooglePkgs: activeNonGooglePkgs,
      savedPaused: savedPaused,
      needsPhase1Reboot: needsPhase1Reboot,
      isPhase2Resume: isPhase2Resume,
      suitable: suitable,
      blockers: blockers,
    };

    els.deviceSummary.textContent = auditResult.deviceName + " · Android " + androidRelease + " (API " + sdkInt + ")";
    els.auditCard.style.display = "block";

    const accountSummary = isPhase2Resume
      ? "Phase 1 complete: " + savedPaused.length + " app(s) paused across reboot (" + savedPaused.join(", ") + "). Ready to activate & restore!"
      : activeNonGooglePkgs.length === 0
        ? "0 active non-Google accounts (single-pass activation ready)"
        : activeNonGooglePkgs.length + " app(s) will be paused + phone rebooted before activation: " + activeNonGooglePkgs.join(", ");

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
        label: "Non-Google Accounts",
        value: accountSummary,
        status: isPhase2Resume ? "PHASE 2" : activeNonGooglePkgs.length === 0 ? "PASS" : "PHASE 1",
      },
    ];

    els.auditList.innerHTML = rows.map(function (row) {
      const cls = row.status === "BLOCKER" || row.status === "FAIL"
        ? "audit-pill fail"
        : row.status === "PHASE 1"
          ? "audit-pill warn"
          : "audit-pill ok";
      return (
        '<div class="audit-row">' +
        '<div><strong>' + row.label + '</strong><div class="audit-val">' + row.value + '</div></div>' +
        '<span class="' + cls + '">' + row.status + '</span>' +
        '</div>'
      );
    }).join("");

    if (blockers.length > 0) {
      els.auditBlockers.style.display = "block";
      els.auditBlockers.innerHTML = "<strong>Cannot proceed: hard OS blocker detected.</strong><ul>" +
        blockers.map(function (b) { return "<li>" + b + "</li>"; }).join("") +
        "</ul>";
      setStageState(2, "error", "Blocked by OS preflight check");
      log("Preflight audit found " + blockers.length + " blocker(s).", "error");
    } else if (alreadyOwner) {
      setStageState(2, "ok", "Threshold is already Device Owner on this device");
      log("Threshold is already Device Owner on this phone.", "ok");
      if (savedPaused.length > 0) {
        log("Restoring " + savedPaused.length + " previously paused app(s)...", "warn");
        await restorePackages(savedPaused);
      }
    } else {
      setStageState(2, "ok", isPhase2Resume
        ? "Ready for Phase 2 (activate Device Owner & restore " + savedPaused.length + " app(s))"
        : needsPhase1Reboot
          ? "Phase 1 required: pause " + activeNonGooglePkgs.length + " app(s) and reboot"
          : "Ready for single-pass activation");

      if (isPhase2Resume) {
        setStageState(3, "ok", "Completed before reboot (" + savedPaused.length + " app(s) paused)");
        els.btnRun.textContent = "Phase 2: Activate Hardened Mode & Restore Apps";
      } else if (needsPhase1Reboot) {
        els.btnRun.textContent = "Phase 1: Pause Accounts & Reboot Phone";
      } else {
        els.btnRun.textContent = "Run Expert Provisioning";
      }

      els.ackWrap.style.display = "block";
      log("Preflight audit passed.", "ok");
    }

    updateRunButtonState();
  }

  async function restorePackages(packages) {
    const list = packages && packages.length > 0 ? packages : loadPausedPackages();
    if (!list || list.length === 0) {
      log("No paused packages cached to restore.", "ok");
      return;
    }
    log("Restoring " + list.length + " paused package(s)...");
    let allOk = true;
    for (const pkg of list) {
      try {
        const res = await window.ThresholdWebAdb.runShell(session.adb, "pm enable " + shellQuote(pkg));
        log("pm enable " + pkg + " -> " + (res.output || "enabled"));
      } catch (err) {
        allOk = false;
        log("Failed to re-enable " + pkg + ": " + err.message, "error");
      }
    }
    if (allOk) {
      savePausedPackages([]);
      log("All paused apps and accounts restored.", "ok");
    }
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

  async function runPhase1PauseAndReboot() {
    const pkgs = auditResult.activeNonGooglePkgs;
    const pausedSoFar = [];
    setStageState(3, "active", "Pausing " + pkgs.length + " non-Google account app(s)...");
    setProgress(15, "Pausing non-Google account apps...");

    try {
      for (const pkg of pkgs) {
        const res = await window.ThresholdWebAdb.runShell(
          session.adb,
          "pm disable-user --user 0 " + shellQuote(pkg)
        );
        log("Paused " + pkg + " -> " + (res.output || "disabled-user"));
        if (/Exception|SecurityException|Error/i.test(res.output)) {
          throw new Error("Could not pause " + pkg + ": " + res.output);
        }
        pausedSoFar.push(pkg);
      }

      // Persist all paused packages so Phase 2 (or Emergency Restore) knows what to re-enable
      const merged = Array.from(new Set(loadPausedPackages().concat(pausedSoFar))).sort();
      savePausedPackages(merged);

      setProgress(50, "Rebooting phone to refresh Android account state...");
      setStageState(3, "warn", "Rebooting phone. Unlock and click Connect Phone via USB for Phase 2.");
      log("All " + pausedSoFar.length + " app(s) paused cleanly. Rebooting phone now...", "ok");

      // Trigger clean OS reboot (connection will drop as phone restarts)
      try {
        await window.ThresholdWebAdb.runShell(session.adb, "svc power reboot || reboot");
      } catch (_) {
        // Expected when USB drops during reboot
      }

      await window.ThresholdWebAdb.disconnectDevice(session);
      session = null;
      els.btnConnect.style.display = "inline-block";
      els.btnDisconnect.style.display = "none";
      els.auditCard.style.display = "none";
      els.deviceSummary.textContent = "Rebooting... unlock phone, then click Connect Phone via USB for Phase 2.";
      log("Phase 1 complete! Wait for your phone to finish booting, unlock it, and click 'Connect Phone via USB' to run Phase 2.", "ok");
    } catch (err) {
      log(err.message || String(err), "error");
      if (pausedSoFar.length > 0) {
        log("Rolling back Phase 1: re-enabling paused apps...", "warn");
        await restorePackages(pausedSoFar);
      }
      setStageState(3, "error", err.message || "Phase 1 failed");
    }
  }

  async function runPhase2ActivateAndRestore() {
    const pausedToRestore = auditResult.savedPaused || [];
    try {
      setProgress(5, "Downloading Threshold bridge & production APKs...");
      const testOnlyBytes = await fetchBinaryPayload(TESTONLY_URL, els.overrideTestonly, "testOnly bridge APK");
      const releaseBytes = await fetchBinaryPayload(RELEASE_URL, els.overrideRelease, "production release APK");
      log("Both APK payloads downloaded and verified in memory.", "ok");

      if (pausedToRestore.length === 0) {
        setStageState(3, "ok", "No non-Google accounts needed pausing");
      }

      // STAGE 4: testOnly bridge -> dpm set-device-owner -> pause -> production APK
      setStageState(4, "active", "Installing testOnly bridge APK...");
      await pushAndInstallApk(
        testOnlyBytes,
        "/data/local/tmp/threshold-testonly.apk",
        "-r -t -i com.android.vending",
        "testOnly bridge APK",
        15,
        45
      );

      setStageState(4, "active", "Activating Device Owner...");
      setProgress(52, "Running dpm set-device-owner...");
      const dpmRes = await window.ThresholdWebAdb.runShell(
        session.adb,
        "dpm set-device-owner " + ADMIN_COMPONENT
      );
      log("dpm set-device-owner -> " + dpmRes.output);
      if (!dpmRes.output.includes("Success")) {
        throw new Error("dpm set-device-owner failed: " + dpmRes.output);
      }

      setStageState(4, "active", "Device Owner active. Pausing before production upgrade...");
      setProgress(60, "Pausing 2s before upgrading to production APK...");
      await sleep(2000);

      setStageState(4, "active", "Upgrading in-place to production APK (clearing testOnly flag)...");
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
      setStageState(4, "ok", "Device Owner active & upgraded to production APK");

      // STAGE 5: Restore paused packages & verify
      setStageState(5, "active", "Restoring paused apps & verifying Device Owner...");
      setProgress(95, "Restoring paused apps...");
      if (pausedToRestore.length > 0) {
        await restorePackages(pausedToRestore);
      }

      const verifyRes = await window.ThresholdWebAdb.runShell(session.adb, "dpm list-owners");
      log("Final verification (dpm list-owners): " + verifyRes.output);
      if (!verifyRes.output.includes("com.garan.threshold")) {
        throw new Error("Verification failed: Threshold not listed as Device Owner.");
      }

      setProgress(100, "Hardened Mode provisioned successfully!");
      setStageState(5, "ok", "All apps restored & Hardened Mode active");
      log("Expert provisioning complete! You may safely unplug your phone.", "ok");
    } catch (err) {
      log(err.message || String(err), "error");
      if (pausedToRestore.length > 0) {
        log("Restoring paused apps after failure...", "warn");
        await restorePackages(pausedToRestore);
      }
      const activeStage = els.stageList.querySelector('[data-state="active"]');
      if (activeStage) {
        setStageState(activeStage.getAttribute("data-stage"), "error", err.message || "Failed");
      }
    }
  }

  async function runExpertProvisioning() {
    if (!session || !auditResult || !auditResult.suitable || isRunning) return;
    isRunning = true;
    updateRunButtonState();
    try {
      if (auditResult.needsPhase1Reboot) {
        await runPhase1PauseAndReboot();
      } else {
        await runPhase2ActivateAndRestore();
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

  // Show resume banner in log if Phase 1 packages are waiting in localStorage
  const pendingResume = loadPausedPackages();
  if (pendingResume.length > 0) {
    els.logEl.textContent = "Phase 1 paused " + pendingResume.length + " app(s) before reboot. Unlock your phone and click 'Connect Phone via USB' to run Phase 2.\n";
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

  els.btnRestore.addEventListener("click", async function () {
    if (!session || isRunning) return;
    isRunning = true;
    updateRunButtonState();
    try {
      const saved = loadPausedPackages();
      if (saved.length > 0) {
        await restorePackages(saved);
      } else {
        log("No packages cached in browser storage; scanning phone for disabled packages...");
        const res = await window.ThresholdWebAdb.runShell(session.adb, "pm list packages -d -3");
        const disabledPkgs = parsePackagesList(res.output);
        if (disabledPkgs.length === 0) {
          log("No disabled 3rd-party packages found on device.", "ok");
        } else {
          await restorePackages(disabledPkgs);
        }
      }
    } finally {
      isRunning = false;
      updateRunButtonState();
    }
  });

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
