package com.garan.threshold.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garan.threshold.R
import com.garan.threshold.core.model.AppAssignment
import com.garan.threshold.core.model.BaseProfileSettings
import com.garan.threshold.core.model.ComponentKey
import com.garan.threshold.core.model.FrictionChoice
import com.garan.threshold.ui.search.SearchKeyboardOptions

object SettingsTestTags {
    const val SETTINGS_LIST = "settings_list"
    const val NAV_PROFILES = "settings_nav_profiles"
    const val NAV_CONFIGURE_APPS = "settings_nav_configure_apps"
    const val ADD_QUICK_ACTION = "settings_add_quick_action"
    const val PENDING_REBOOT_BANNER = "settings_pending_reboot_banner"
    const val DISCARD_PENDING_CHANGES_BUTTON = "settings_discard_pending_changes"
    const val TOGGLE_REQUIRE_REBOOT = "settings_toggle_require_reboot"
    const val CONFIRM_REQUIRE_REBOOT_DIALOG = "settings_confirm_require_reboot_dialog"
    const val CONFIRM_REQUIRE_REBOOT_BUTTON = "settings_confirm_require_reboot_button"
    const val CANCEL_REQUIRE_REBOOT_BUTTON = "settings_cancel_require_reboot_button"
    const val SET_BLACK_WALLPAPER_ACTION = "settings_set_black_wallpaper"
    const val CONFIRM_BLACK_WALLPAPER_DIALOG = "settings_confirm_black_wallpaper_dialog"
    const val CONFIRM_BLACK_WALLPAPER_BUTTON = "settings_confirm_black_wallpaper_button"
    const val CANCEL_BLACK_WALLPAPER_BUTTON = "settings_cancel_black_wallpaper_button"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ThresholdSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onNavigateToConfigureApps: () -> Unit,
    onUpdateShowUnlocks: (Boolean) -> Unit,
    onUpdateShowRest: (Boolean) -> Unit,
    onUpdateRequireFullTyping: (Boolean) -> Unit,
    onUpdateDefaultFriction: (FrictionChoice) -> Unit,
    onAddQuickAction: (ComponentKey) -> Unit,
    onRemoveQuickAction: (ComponentKey) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToProfiles: () -> Unit = {},
    onUpdateRequireReboot: (Boolean) -> Unit = {},
    onDiscardPendingChanges: () -> Unit = {},
    onApplyPureBlackWallpaper: () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    var showAddQuickActionSheet by remember { mutableStateOf(false) }
    var showConfirmRequireRebootDialog by remember { mutableStateOf(false) }
    var showConfirmBlackWallpaperDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val guardedCount = uiState.baseProfile.appAssignments.values.count { it is AppAssignment.Guarded }
    val blockedCount = uiState.baseProfile.appAssignments.values.count { it is AppAssignment.Blocked }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
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
                .testTag(SettingsTestTags.SETTINGS_LIST)
                .consumeWindowInsets(innerPadding),
            contentPadding = innerPadding,
        ) {
            if (uiState.hasPendingRebootChanges) {
                item(key = "pending_reboot_banner") {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.banner_pending_reboot_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.banner_pending_reboot_body),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = onDiscardPendingChanges,
                                modifier = Modifier.testTag(SettingsTestTags.DISCARD_PENDING_CHANGES_BUTTON),
                            ) {
                                Text(text = stringResource(R.string.action_discard_pending_changes))
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsTestTags.PENDING_REBOOT_BANNER),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                }
            }

            // SECTION 1: QUICK ACTIONS
            item(key = "header_quick_actions") {
                SettingsSectionHeader(title = stringResource(R.string.section_quick_actions))
            }

            if (uiState.baseProfile.quickActions.isEmpty()) {
                item(key = "quick_actions_empty") {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.label_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            } else {
                uiState.baseProfile.quickActions.forEachIndexed { index, comp ->
                    item(key = "quick_action_${comp.id}") {
                        val appLabel = uiState.availableApps.find { it.component == comp }?.label
                            ?: comp.packageName
                        val slotFormatted = String.format("%02d", index + 1)

                        ListItem(
                            leadingContent = {
                                Text(
                                    text = slotFormatted,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemoveQuickAction(comp) }) {
                                    Icon(
                                        imageVector = ThresholdIcons.Delete,
                                        contentDescription = stringResource(R.string.cd_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            if (uiState.baseProfile.quickActions.size < BaseProfileSettings.MAX_QUICK_ACTIONS) {
                item(key = "quick_action_add") {
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = ThresholdIcons.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.cd_add),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsTestTags.ADD_QUICK_ACTION)
                            .clickable { showAddQuickActionSheet = true },
                    )
                }
            }

            item(key = "divider_1") {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // SECTION 2: PROFILES & APP CONFIGURATION
            item(key = "header_app_config") {
                SettingsSectionHeader(title = stringResource(R.string.section_app_configuration))
            }

            item(key = "nav_profiles") {
                val profileCount = uiState.customProfiles.size
                val activeCount = uiState.customProfiles.count { it.enabled }
                val summary = when {
                    profileCount == 0 -> "No custom profiles configured"
                    activeCount == 1 -> "1 profile active"
                    else -> "$profileCount profiles ($activeCount active)"
                }

                ListItem(
                    headlineContent = {
                        Text(
                            text = "Custom profiles",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = ThresholdIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.NAV_PROFILES)
                        .clickable(onClick = onNavigateToProfiles),
                )
            }

            item(key = "nav_configure_apps") {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.action_configure_default_apps),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "$guardedCount guarded · $blockedCount blocked",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = ThresholdIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.NAV_CONFIGURE_APPS)
                        .clickable(onClick = onNavigateToConfigureApps),
                )
            }

            item(key = "divider_2") {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // SECTION 3: GENERAL
            item(key = "header_general") {
                SettingsSectionHeader(title = stringResource(R.string.section_general))
            }

            item(key = "toggle_show_unlocks") {
                SettingsToggleItem(
                    label = stringResource(R.string.setting_show_unlocks),
                    checked = uiState.general.showUnlocks,
                    onCheckedChange = onUpdateShowUnlocks,
                )
            }

            item(key = "toggle_show_rest") {
                SettingsToggleItem(
                    label = stringResource(R.string.setting_show_rest),
                    checked = uiState.general.showRest,
                    onCheckedChange = onUpdateShowRest,
                )
            }

            item(key = "toggle_require_full_typing") {
                SettingsToggleItem(
                    label = stringResource(R.string.setting_require_full_typing),
                    checked = uiState.general.requireFullTypingOfGuardedApps,
                    onCheckedChange = onUpdateRequireFullTyping,
                )
            }

            item(key = "toggle_require_reboot") {
                SettingsToggleItem(
                    label = stringResource(R.string.setting_require_reboot),
                    supportingText = stringResource(R.string.setting_require_reboot_summary),
                    checked = uiState.general.requireRebootForSettingsChange,
                    onCheckedChange = { newChecked ->
                        if (newChecked && !uiState.isRequireRebootActiveOnLive) {
                            showConfirmRequireRebootDialog = true
                        } else {
                            onUpdateRequireReboot(newChecked)
                        }
                    },
                    modifier = Modifier.testTag(SettingsTestTags.TOGGLE_REQUIRE_REBOOT),
                )
            }

            item(key = "action_set_black_wallpaper") {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.action_set_black_wallpaper),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.action_set_black_wallpaper_summary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = ThresholdIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.SET_BLACK_WALLPAPER_ACTION)
                        .clickable { showConfirmBlackWallpaperDialog = true },
                )
            }

            item(key = "divider_3") {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // SECTION 4: DEFAULT FRICTION
            item(key = "header_default_friction") {
                SettingsSectionHeader(title = stringResource(R.string.label_default_friction))
            }

            item(key = "default_friction_group") {
                val currentFriction = uiState.baseProfile.defaultFriction
                Column(modifier = Modifier.selectableGroup()) {
                    FrictionRadioOption(
                        title = stringResource(R.string.friction_typed_intention),
                        selected = currentFriction == FrictionChoice.TYPED_INTENTION,
                        onSelect = { onUpdateDefaultFriction(FrictionChoice.TYPED_INTENTION) },
                    )
                    FrictionRadioOption(
                        title = stringResource(R.string.friction_drag_sort),
                        selected = currentFriction == FrictionChoice.DRAG_SORT,
                        onSelect = { onUpdateDefaultFriction(FrictionChoice.DRAG_SORT) },
                    )
                    FrictionRadioOption(
                        title = stringResource(R.string.friction_caret_tracing),
                        selected = currentFriction == FrictionChoice.CARET_TRACING,
                        onSelect = { onUpdateDefaultFriction(FrictionChoice.CARET_TRACING) },
                    )
                    FrictionRadioOption(
                        title = stringResource(R.string.friction_random),
                        selected = currentFriction == FrictionChoice.RANDOM,
                        onSelect = { onUpdateDefaultFriction(FrictionChoice.RANDOM) },
                    )
                }
            }
        }
    }

    // Add Quick Action Searchable ModalBottomSheet
    if (showAddQuickActionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var searchQuery by remember { mutableStateOf("") }
        val availableToAdd = remember(uiState.availableApps, uiState.baseProfile.quickActions, searchQuery) {
            uiState.availableApps
                .filter { it.component !in uiState.baseProfile.quickActions }
                .filter { searchQuery.isBlank() || it.label.contains(searchQuery, ignoreCase = true) }
                .sortedBy { it.label.lowercase() }
        }

        ModalBottomSheet(
            onDismissRequest = { showAddQuickActionSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.section_quick_actions),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = SearchKeyboardOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(availableToAdd, key = { it.component.id }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAddQuickAction(app.component)
                                    showAddQuickActionSheet = false
                                },
                        )
                    }
                }
            }
        }
    }

    if (showConfirmRequireRebootDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmRequireRebootDialog = false },
            title = {
                Text(text = stringResource(R.string.dialog_require_reboot_title))
            },
            text = {
                Text(text = stringResource(R.string.dialog_require_reboot_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmRequireRebootDialog = false
                        onUpdateRequireReboot(true)
                    },
                    modifier = Modifier.testTag(SettingsTestTags.CONFIRM_REQUIRE_REBOOT_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_enable))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmRequireRebootDialog = false },
                    modifier = Modifier.testTag(SettingsTestTags.CANCEL_REQUIRE_REBOOT_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag(SettingsTestTags.CONFIRM_REQUIRE_REBOOT_DIALOG),
        )
    }

    if (showConfirmBlackWallpaperDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmBlackWallpaperDialog = false },
            title = {
                Text(text = stringResource(R.string.dialog_black_wallpaper_title))
            },
            text = {
                Text(text = stringResource(R.string.dialog_black_wallpaper_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmBlackWallpaperDialog = false
                        onApplyPureBlackWallpaper()
                    },
                    modifier = Modifier.testTag(SettingsTestTags.CONFIRM_BLACK_WALLPAPER_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmBlackWallpaperDialog = false },
                    modifier = Modifier.testTag(SettingsTestTags.CANCEL_BLACK_WALLPAPER_BUTTON),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag(SettingsTestTags.CONFIRM_BLACK_WALLPAPER_DIALOG),
        )
    }
}

@Composable
internal fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = if (supportingText != null) {
            {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}
