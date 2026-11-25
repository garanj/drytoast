package com.garan.drytoast.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.garan.drytoast.DryToastAppContainer
import com.garan.drytoast.core.backup.ConfigBackupCodec
import com.garan.drytoast.core.backup.ConfigImportResult
import com.garan.drytoast.core.port.SettingsRepository
import com.garan.drytoast.schedule.ScheduleTransitionNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStreamReader

/**
 * Debug-only BroadcastReceiver enabling automated JSON configuration import via ADB during
 * local development and video/demo scripting.
 *
 * Lives strictly under `app/src/debug/` so it is completely absent from `release` builds.
 *
 * Usage:
 * ```bash
 * adb push config.json /sdcard/Android/data/com.garan.drytoast/files/config.json
 * adb shell am broadcast \
 *   -a com.garan.drytoast.debug.IMPORT_CONFIG \
 *   -n com.garan.drytoast/.debug.DebugConfigImportReceiver \
 *   --es path /sdcard/Android/data/com.garan.drytoast/files/config.json \
 *   --es passphrase "optional-passphrase"
 * ```
 *
 * Or inline JSON via `--es json '{...}'`.
 * Returns `result=0, data="OK"` (or `"OK (staged for reboot)"`) on success, or
 * `result=1, data="ERROR [CODE]: detail"` on failure.
 */
class DebugConfigImportReceiver internal constructor(
    private val settingsRepositoryOverride: SettingsRepository?,
    private val configBackupCodec: ConfigBackupCodec,
    private val ioDispatcher: CoroutineDispatcher,
    private val onImportedOverride: (suspend () -> Unit)?,
) : BroadcastReceiver() {

    constructor() : this(
        settingsRepositoryOverride = null,
        configBackupCodec = ConfigBackupCodec(),
        ioDispatcher = Dispatchers.IO,
        onImportedOverride = null,
    )

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_IMPORT_CONFIG) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext ?: context
        val path = intent.getStringExtra(EXTRA_PATH)
        val inlineJson = intent.getStringExtra(EXTRA_JSON)
        val passphrase = intent.getStringExtra(EXTRA_PASSPHRASE)

        CoroutineScope(ioDispatcher).launch {
            try {
                val (code, message) = handleImport(
                    appContext = appContext,
                    path = path,
                    inlineJson = inlineJson,
                    passphrase = passphrase,
                )
                pendingResult?.setResult(code, message, null)
            } catch (t: Throwable) {
                pendingResult?.setResult(
                    RESULT_ERROR,
                    "ERROR [UNEXPECTED]: ${t.message ?: t::class.java.simpleName}",
                    null,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    internal suspend fun handleImport(
        appContext: Context,
        path: String?,
        inlineJson: String?,
        passphrase: String?,
    ): Pair<Int, String> {
        val rawJson = when {
            !inlineJson.isNullOrEmpty() -> inlineJson
            !path.isNullOrBlank() -> {
                val file = File(path)
                if (!file.exists()) {
                    return RESULT_ERROR to "ERROR [FILE_NOT_FOUND]: File does not exist: $path"
                }
                if (!file.isFile || !file.canRead()) {
                    return RESULT_ERROR to "ERROR [FILE_UNREADABLE]: Cannot read file: $path"
                }
                runCatching { readBoundedFile(file) }.getOrElse { err ->
                    return RESULT_ERROR to "ERROR [IO_ERROR]: ${err.message ?: "Failed to read $path"}"
                }
            }
            else -> {
                return RESULT_ERROR to "ERROR [MISSING_INPUT]: Provide --es path <file> or --es json <payload>"
            }
        }

        val container = if (settingsRepositoryOverride == null) DryToastAppContainer.from(appContext) else null
        val repository = settingsRepositoryOverride ?: container!!.settingsRepository

        if (repository.getSettings().isSettingsLocked) {
            return RESULT_ERROR to "ERROR [SETTINGS_LOCKED]: Settings Lock is active"
        }

        return when (val decoded = configBackupCodec.decode(rawJson, passphrase)) {
            is ConfigImportResult.PassphraseRequired -> {
                RESULT_ERROR to "ERROR [PASSPHRASE_REQUIRED]: Encrypted config requires --es passphrase <value>"
            }
            is ConfigImportResult.Error -> {
                RESULT_ERROR to "ERROR [${decoded.reason.name}]: ${decoded.detail}"
            }
            is ConfigImportResult.Success -> {
                val applied = repository.importConfiguration(decoded.snapshot)
                if (!applied) {
                    RESULT_ERROR to "ERROR [SETTINGS_LOCKED]: Settings Lock is active"
                } else {
                    if (onImportedOverride != null) {
                        onImportedOverride.invoke()
                    } else {
                        container?.reconcileDpcFromSettings()
                        ScheduleTransitionNotifier.notifyTransition()
                    }
                    val updated = repository.getSettings()
                    val status = if (updated.hasPendingRebootChanges) "OK (staged for reboot)" else "OK"
                    RESULT_OK to status
                }
            }
        }
    }

    private fun readBoundedFile(file: File): String {
        val maxChars = ConfigBackupCodec.MAX_INPUT_CHARS + 1
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
            while (sb.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - sb.length))
                if (read < 0) break
                sb.append(buffer, 0, read)
            }
        }
        return sb.toString()
    }

    companion object {
        const val ACTION_IMPORT_CONFIG = "com.garan.drytoast.debug.IMPORT_CONFIG"
        const val EXTRA_PATH = "path"
        const val EXTRA_JSON = "json"
        const val EXTRA_PASSPHRASE = "passphrase"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
    }
}
