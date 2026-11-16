package com.garan.threshold.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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

object HardenedModeTestTags {
    const val SCREEN_LIST = "hardened_mode_list"
    const val STATUS_CARD = "hardened_mode_status_card"
    const val COPY_ENABLE_COMMAND = "hardened_mode_copy_enable_command"
    const val COPY_DISABLE_COMMAND = "hardened_mode_copy_disable_command"
    const val COPY_LOCK_SETTINGS_COMMAND = "hardened_mode_copy_lock_settings_command"
    const val COPY_UNLOCK_SETTINGS_COMMAND = "hardened_mode_copy_unlock_settings_command"
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

            // SECTION 3: SETUP & REMOVAL (COMPUTER REQUIRED)
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
