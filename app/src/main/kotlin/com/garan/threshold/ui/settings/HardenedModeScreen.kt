package com.garan.threshold.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garan.threshold.R

/**
 * Validates the QR-code deep link URI (`threshold://hardened/disable?key=...`) used to open
 * the hidden [DisableHardenedModeScreen].
 *
 * The 64-character hex token prevents a user from casually typing a short URL on the phone
 * itself without scanning the QR code hosted on the Threshold website.
 */
object HardenedModeDeepLink {
    const val SCHEME = "threshold"
    const val HOST = "hardened"
    const val PATH_DISABLE = "/disable"
    const val QUERY_PARAM_KEY = "key"
    const val DISABLE_KEY = "4e07408562bedb8b60ce05c1decfe3ad16b72230967de01f640b7e4729b49fce"
    const val FULL_DISABLE_URI = "$SCHEME://$HOST$PATH_DISABLE?$QUERY_PARAM_KEY=$DISABLE_KEY"
    private const val EXTRA_CONSUMED = "com.garan.threshold.extra.DISABLE_DEEP_LINK_CONSUMED"

    fun isValidDisableIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        if (intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val uri = intent.data ?: return false
        return uri.scheme == SCHEME &&
            uri.host == HOST &&
            uri.path == PATH_DISABLE &&
            uri.getQueryParameter(QUERY_PARAM_KEY) == DISABLE_KEY
    }

    fun markConsumed(intent: Intent?) {
        intent?.putExtra(EXTRA_CONSUMED, true)
    }
}

object HardenedModeTestTags {
    const val SCREEN_LIST = "hardened_mode_list"
    const val STATUS_CARD = "hardened_mode_status_card"
    const val COPY_ENABLE_COMMAND = "hardened_mode_copy_enable_command"
    const val COPY_DISABLE_COMMAND = "hardened_mode_copy_disable_command"
    const val COPY_LOCK_SETTINGS_COMMAND = "hardened_mode_copy_lock_settings_command"
    const val COPY_UNLOCK_SETTINGS_COMMAND = "hardened_mode_copy_unlock_settings_command"
    const val DISABLE_SCREEN_ROOT = "disable_hardened_mode_screen"
    const val CONFIRM_DISABLE_BUTTON = "disable_hardened_mode_confirm_button"
    const val CANCEL_DISABLE_BUTTON = "disable_hardened_mode_cancel_button"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HardenedModeScreen(
    isHardenedModeActive: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyCommand: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val copyHandler: (String) -> Unit = onCopyCommand ?: { command ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Threshold ADB command", command))
        Toast.makeText(context, context.getString(R.string.toast_command_copied), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.hardened_mode_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ThresholdIcons.ArrowBack,
                            contentDescription = stringResource(R.string.action_go_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(HardenedModeTestTags.SCREEN_LIST)
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding,
        ) {
            item(key = "status_banner") {
                val statusLabel = if (isHardenedModeActive) {
                    stringResource(R.string.hardened_mode_status_active_label)
                } else {
                    stringResource(R.string.hardened_mode_status_inactive_label)
                }
                val statusBody = if (isHardenedModeActive) {
                    stringResource(R.string.hardened_mode_status_active_body)
                } else {
                    stringResource(R.string.hardened_mode_status_inactive_body)
                }
                val containerColor = if (isHardenedModeActive) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }

                ListItem(
                    headlineContent = {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isHardenedModeActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    supportingContent = {
                        Text(
                            text = statusBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = containerColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(HardenedModeTestTags.STATUS_CARD),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
            }

            // SECTION 1: WHAT HARDENED MODE ENFORCES
            item(key = "header_capabilities") {
                SettingsSectionHeader(title = stringResource(R.string.hardened_mode_section_capabilities))
            }

            item(key = "cap_suspend") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_cap_suspend_title),
                    body = stringResource(R.string.hardened_mode_cap_suspend_body),
                )
            }

            item(key = "cap_home") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_cap_home_title),
                    body = stringResource(R.string.hardened_mode_cap_home_body),
                )
            }

            item(key = "cap_tamper") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_cap_tamper_title),
                    body = stringResource(R.string.hardened_mode_cap_tamper_body),
                )
            }

            item(key = "divider_1") {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // SECTION 2: COMPANION COMMITMENT LAYERS
            item(key = "header_companion") {
                SettingsSectionHeader(title = stringResource(R.string.hardened_mode_section_companion))
            }

            item(key = "companion_intro") {
                Text(
                    text = stringResource(R.string.hardened_mode_companion_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "companion_reboot") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_companion_reboot_title),
                    body = stringResource(R.string.hardened_mode_companion_reboot_body),
                )
            }

            item(key = "companion_adb") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_companion_adb_title),
                    body = stringResource(R.string.hardened_mode_companion_adb_body),
                )
            }

            item(key = "divider_2") {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // SECTION 3: SETUP & REMOVAL
            item(key = "header_setup") {
                SettingsSectionHeader(title = stringResource(R.string.hardened_mode_section_setup))
            }

            item(key = "setup_intro") {
                Text(
                    text = stringResource(R.string.hardened_mode_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "setup_prereq") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_setup_prereq_title),
                    body = stringResource(R.string.hardened_mode_setup_prereq_body),
                )
            }

            item(key = "setup_qr") {
                HardenedFeatureRow(
                    title = stringResource(R.string.hardened_mode_qr_setup_title),
                    body = stringResource(R.string.hardened_mode_qr_setup_body),
                )
            }

            item(key = "setup_adb_header") {
                SettingsSectionHeader(title = stringResource(R.string.hardened_mode_adb_setup_title))
            }

            item(key = "cmd_enable") {
                val cmd = stringResource(R.string.hardened_mode_cmd_enable)
                AdbCommandBlock(
                    label = stringResource(R.string.hardened_mode_cmd_enable_label),
                    command = cmd,
                    copyButtonTestTag = HardenedModeTestTags.COPY_ENABLE_COMMAND,
                    onCopy = { copyHandler(cmd) },
                )
            }

            item(key = "cmd_disable") {
                val cmd = stringResource(R.string.hardened_mode_cmd_disable)
                AdbCommandBlock(
                    label = stringResource(R.string.hardened_mode_cmd_disable_label),
                    command = cmd,
                    copyButtonTestTag = HardenedModeTestTags.COPY_DISABLE_COMMAND,
                    onCopy = { copyHandler(cmd) },
                )
            }

            item(key = "cmd_lock_settings") {
                val cmd = stringResource(R.string.hardened_mode_cmd_lock_settings)
                AdbCommandBlock(
                    label = stringResource(R.string.hardened_mode_cmd_lock_settings_label),
                    command = cmd,
                    copyButtonTestTag = HardenedModeTestTags.COPY_LOCK_SETTINGS_COMMAND,
                    onCopy = { copyHandler(cmd) },
                )
            }

            item(key = "cmd_unlock_settings") {
                val cmd = stringResource(R.string.hardened_mode_cmd_unlock_settings)
                AdbCommandBlock(
                    label = stringResource(R.string.hardened_mode_cmd_unlock_settings_label),
                    command = cmd,
                    copyButtonTestTag = HardenedModeTestTags.COPY_UNLOCK_SETTINGS_COMMAND,
                    onCopy = { copyHandler(cmd) },
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Hidden screen reachable strictly via the external QR-code deep link
 * ([HardenedModeDeepLink.FULL_DISABLE_URI]). Remains accessible even when
 * `isSettingsLocked` is true so scanning the website QR code always provides a clean exit path.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DisableHardenedModeScreen(
    isHardenedModeActive: Boolean,
    onConfirmDisable: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(HardenedModeTestTags.DISABLE_SCREEN_ROOT),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.disable_hardened_mode_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = ThresholdIcons.ArrowBack,
                            contentDescription = stringResource(R.string.action_go_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.disable_hardened_mode_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                text = if (isHardenedModeActive) {
                    stringResource(R.string.disable_hardened_mode_body)
                } else {
                    stringResource(R.string.disable_hardened_mode_inactive_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isHardenedModeActive) {
                Button(
                    onClick = onConfirmDisable,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(HardenedModeTestTags.CONFIRM_DISABLE_BUTTON),
                ) {
                    Text(
                        text = stringResource(R.string.action_disable_hardened_mode),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HardenedModeTestTags.CANCEL_DISABLE_BUTTON),
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun HardenedFeatureRow(title: String, body: String, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AdbCommandBlock(
    label: String,
    command: String,
    copyButtonTestTag: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
            onClick = onCopy,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.testTag(copyButtonTestTag),
        ) {
            Text(
                text = stringResource(R.string.action_copy_command),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
