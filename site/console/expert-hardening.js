/**
 * Threshold Expert Mode: Staged WebUSB Hardened Mode (Device Owner) Provisioner.
 *
 * Stages:
 *  1. Connect & Authorize Device (WebUSB via self-hosted Tango ADB bundle)
 *  2. Read-Only Suitability & Preflight Audit (multi-user/Work Profile/Private Space,
 *     existing Device Owner, and 3rd-party AccountAuthenticator discovery)
 *  3. Pause 3rd-Party AccountAuthenticator components with an on-device 90s auto-rollback watchdog
 *  4. Install testOnly bridge APK -> dpm set-device-owner -> pause -> install production APK
 *  5. Restore all 3rd-party AccountAuthenticator components & verify Device Owner state
 */
(function () {
  "use strict";

  const STORAGE_KEY = "threshold_expert_paused_components";
  const ADMIN_COMPONENT = "com.garan.threshold/.dpc.ThresholdDeviceAdminReceiver";
  const TESTONLY_URL = "/downloads/threshold-testonly.bin";
  const RELEASE_URL = "/downloads/threshold-release.bin";
  const WATCHDOG_SECONDS = 90;

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
        warn: "WARNING",
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

  function savePausedComponents(components) {
    try {
      if (components && components.length > 0) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(components));
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    } catch (_) {}
  }

  function loadPausedComponents() {
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

  function parseThirdPartyPackages(pmListPackagesOutput) {
    const pkgs = new Set();
    pmListPackagesOutput.split(/\r?\n/).forEach(function (line) {
      const trimmed = line.trim();
      if (trimmed.startsWith("package:")) {
        pkgs.add(trimmed.slice("package:".length).trim());
      }
    });
    return pkgs;
  }

  function parseIncompatibleAccountPackagesFromDumpsys(dumpsysAccountOutput) {
    // Parse active account types: "Account {name=..., type=com.whatsapp}"
    const activeTypes = new Set();
    const acctRegex = /Account\s*\{[^}]*\btype=([^,}\s]+)/g;
    let m;
    while ((m = acctRegex.exec(dumpsysAccountOutput)) !== null) {
      const type = m[1].trim();
      // Core Google accounts (com.google) are allowed when the DPC is testOnly=true.
      if (type && type !== "com.google") {
        activeTypes.add(type);
      }
    }

    // Map authenticator type -> owning package from:
    // "ServiceInfo: AuthenticatorDescription {type=com.whatsapp}, ComponentInfo{com.whatsapp/...}"
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
    setStageState(2, "active", "Inspecting users, Device Owner state, and 3rd-party authenticators...");
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
    const authComponents = parseIncompatibleAccountPackagesFromDumpsys(accountsRes.output);

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
      authComponents: authComponents,
      suitable: suitable,
      blockers: blockers,
    };

    els.deviceSummary.textContent = auditResult.deviceName + " · Android " + androidRelease + " (API " + sdkInt + ")";
    els.auditCard.style.display = "block";

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
        label: "3rd-Party Account Authenticators",
        value: authComponents.length === 0
          ? "0 found (no accounts need pausing)"
          : authComponents.length + " service(s) will be paused for ~15s: " +
            authComponents.map(function (c) { return c.split("/")[0]; }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(", "),
        status: authComponents.length === 0 ? "PASS" : "PAUSE",
      },
    ];

    els.auditList.innerHTML = rows.map(function (row) {
      const cls = row.status === "BLOCKER" || row.status === "FAIL"
        ? "audit-pill fail"
        : row.status === "PAUSE"
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
    } else {
      setStageState(2, "ok", "Device is eligible (" + authComponents.length + " 3P authenticator service(s) to pause)");
      els.ackWrap.style.display = "block";
      log("Preflight audit passed. Ready for Expert Provisioning.", "ok");
    }

    updateRunButtonState();
  }

  async function restoreAuthenticators(components) {
    const list = components && components.length > 0 ? components : loadPausedComponents();
    if (!list || list.length === 0) {
      log("No paused authenticator components to restore.", "ok");
      return;
    }
    log("Restoring " + list.length + " 3rd-party authenticator component(s)...");
    let allOk = true;
    for (const comp of list) {
      try {
        const res = await window.ThresholdWebAdb.runShell(session.adb, "pm enable " + shellQuote(comp));
        log("pm enable " + comp + " -> " + (res.output || "OK"));
      } catch (err) {
        allOk = false;
        log("Failed to re-enable " + comp + ": " + err.message, "error");
      }
    }
    if (allOk) {
      savePausedComponents([]);
      log("All 3rd-party authenticators restored.", "ok");
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
          "Signature mismatch (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Uninstall the existing Threshold build on your phone first (or ensure Play App Signing uses the same signing key) and retry."
        );
      }
      throw new Error("APK install failed for " + label + ": " + res.output);
    }
    setProgress(endPct, label + " installed.");
  }

  async function runExpertProvisioning() {
    if (!session || !auditResult || !auditResult.suitable || isRunning) return;
    isRunning = true;
    updateRunButtonState();

    const comps = auditResult.authComponents || [];
    let pausedAny = false;

    try {
      // Pre-fetch both APK payloads BEFORE touching anything on the phone!
      setProgress(5, "Downloading Threshold bridge & production APKs...");
      const testOnlyBytes = await fetchBinaryPayload(TESTONLY_URL, els.overrideTestonly, "testOnly bridge APK");
      const releaseBytes = await fetchBinaryPayload(RELEASE_URL, els.overrideRelease, "production release APK");
      log("Both APK payloads downloaded and verified in memory.", "ok");

      // STAGE 3: Pause 3rd-party authenticators with 90s on-device watchdog
      setStageState(3, "active", "Arming " + WATCHDOG_SECONDS + "s on-device rollback watchdog...");
      if (comps.length > 0) {
        savePausedComponents(comps);

        const enableChain = comps.map(function (c) { return "pm enable " + shellQuote(c); }).join("; ");
        const watchdogCmd = "nohup sh -c 'sleep " + WATCHDOG_SECONDS + "; " + enableChain + "' >/dev/null 2>&1 </dev/null &";
        log("Arming on-device " + WATCHDOG_SECONDS + "s self-healing watchdog...");
        await window.ThresholdWebAdb.runShell(session.adb, watchdogCmd);

        for (const comp of comps) {
          const res = await window.ThresholdWebAdb.runShell(
            session.adb,
            "pm disable-user --user 0 " + shellQuote(comp)
          );
          log("Paused " + comp + " -> " + (res.output || "disabled"));
          if (/Exception|SecurityException|Error/i.test(res.output)) {
            throw new Error("Could not pause " + comp + ": " + res.output);
          }
          pausedAny = true;
        }

        // Wait for AccountManagerService -> LOGIN_ACCOUNTS_CHANGED -> DevicePolicyManagerService recalc
        setStageState(3, "active", "Waiting for Android AccountManager to settle...");
        let compatible = false;
        for (let attempt = 0; attempt < 12; attempt++) {
          await sleep(500);
          const dump = await window.ThresholdWebAdb.runShell(
            session.adb,
            "dumpsys device_policy | grep -i hasIncompatibleAccounts"
          );
          if (dump.output.includes("false")) {
            compatible = true;
            log("DevicePolicyManagerService confirmed mHasIncompatibleAccounts=false.", "ok");
            break;
          }
          if (attempt === 3 && !dump.output) {
            // Some OEM builds omit mHasIncompatibleAccounts from dumpsys; give 2s settle window
            compatible = true;
            break;
          }
        }
        if (!compatible) {
          log("DevicePolicyManagerService still reports incompatible accounts after 6s; attempting dpm anyway.", "warn");
        }
        setStageState(3, "ok", "Paused " + comps.length + " authenticator(s) (" + WATCHDOG_SECONDS + "s watchdog armed)");
      } else {
        setStageState(3, "ok", "No 3rd-party authenticators needed pausing");
      }

      // STAGE 4: testOnly bridge -> dpm set-device-owner -> pause -> production APK
      setStageState(4, "active", "Installing testOnly bridge APK...");
      await pushAndInstallApk(
        testOnlyBytes,
        "/data/local/tmp/threshold-testonly.apk",
        "-r -t -i com.android.vending",
        "testOnly bridge APK",
        20,
        50
      );

      setStageState(4, "active", "Activating Device Owner...");
      setProgress(55, "Running dpm set-device-owner...");
      const dpmRes = await window.ThresholdWebAdb.runShell(
        session.adb,
        "dpm set-device-owner " + ADMIN_COMPONENT
      );
      log("dpm set-device-owner -> " + dpmRes.output);
      if (!dpmRes.output.includes("Success")) {
        throw new Error("dpm set-device-owner failed: " + dpmRes.output);
      }

      setStageState(4, "active", "Device Owner active. Pausing before production upgrade...");
      setProgress(62, "Pausing 2s before upgrading to production APK...");
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

      // Clean up temporary files
      await window.ThresholdWebAdb.runShell(
        session.adb,
        "rm -f /data/local/tmp/threshold-testonly.apk /data/local/tmp/threshold-release.apk"
      );
      setStageState(4, "ok", "Device Owner active & upgraded to production APK");

      // STAGE 5: Restore authenticators & verify
      setStageState(5, "active", "Restoring 3rd-party authenticators & verifying...");
      setProgress(95, "Restoring 3rd-party authenticators...");
      if (pausedAny) {
        await restoreAuthenticators(comps);
        pausedAny = false;
      }

      const verifyRes = await window.ThresholdWebAdb.runShell(session.adb, "dpm list-owners");
      log("Final verification (dpm list-owners): " + verifyRes.output);
      if (!verifyRes.output.includes("com.garan.threshold")) {
        throw new Error("Verification failed: Threshold not listed as Device Owner.");
      }

      setProgress(100, "Hardened Mode provisioned successfully!");
      setStageState(5, "ok", "All accounts restored & Hardened Mode active");
      log("Expert provisioning complete! You may safely unplug your phone.", "ok");
    } catch (err) {
      log(err.message || String(err), "error");
      if (pausedAny) {
        log("Rolling back: restoring paused 3rd-party authenticators immediately...", "warn");
        await restoreAuthenticators(comps);
      }
      // Mark whichever stage is currently active as failed
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
    els.logEl.textContent = "";
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
      const saved = loadPausedComponents();
      if (saved.length > 0) {
        await restoreAuthenticators(saved);
      } else {
        log("No components cached in browser storage; scanning phone for disabled 3rd-party packages/components...");
        const res = await window.ThresholdWebAdb.runShell(session.adb, "pm list packages -d -3");
        const disabledPkgs = parseThirdPartyPackages(res.output);
        if (disabledPkgs.size === 0) {
          log("No disabled 3rd-party packages found on device.", "ok");
        } else {
          for (const pkg of disabledPkgs) {
            await window.ThresholdWebAdb.runShell(session.adb, "pm enable " + shellQuote(pkg));
            log("Re-enabled package: " + pkg, "ok");
          }
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
