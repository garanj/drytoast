package com.garan.drytoast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.garan.drytoast.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportConfigBottomSheet(onDismiss: () -> Unit, onConfirmExport: (passphrase: String?) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }

    val hasPassphrase = passphrase.isNotEmpty()
    val mismatch = hasPassphrase && confirmPassphrase != passphrase
    val canConfirm = !hasPassphrase || (confirmPassphrase == passphrase)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(SettingsTestTags.EXPORT_CONFIG_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_export_config_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.sheet_export_config_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(text = stringResource(R.string.sheet_export_passphrase_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.EXPORT_PASSPHRASE_INPUT),
            )

            if (hasPassphrase) {
                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    label = { Text(text = stringResource(R.string.sheet_export_confirm_passphrase_label)) },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        {
                            Text(
                                text = stringResource(R.string.sheet_export_passphrase_mismatch),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.EXPORT_CONFIRM_PASSPHRASE_INPUT),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(SettingsTestTags.EXPORT_CANCEL_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onConfirmExport(passphrase.takeIf { it.isNotEmpty() }) },
                    enabled = canConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(SettingsTestTags.EXPORT_CONFIRM_BUTTON),
                ) {
                    Text(text = stringResource(R.string.sheet_export_save_action))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportPassphraseBottomSheet(
    errorMessage: String?,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onSubmitPassphrase: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var passphrase by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(SettingsTestTags.IMPORT_PASSPHRASE_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_import_passphrase_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.sheet_import_passphrase_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(text = stringResource(R.string.sheet_import_passphrase_label)) },
                singleLine = true,
                enabled = !isWorking,
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) {
                    {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(SettingsTestTags.IMPORT_PASSPHRASE_ERROR),
                        )
                    }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.IMPORT_PASSPHRASE_INPUT),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(SettingsTestTags.IMPORT_CANCEL_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onSubmitPassphrase(passphrase) },
                    enabled = passphrase.isNotEmpty() && !isWorking,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(SettingsTestTags.IMPORT_UNLOCK_BUTTON),
                ) {
                    Text(text = stringResource(R.string.sheet_import_unlock_action))
                }
            }
        }
    }
}
