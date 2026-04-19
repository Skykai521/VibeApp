package com.vibe.app.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.app.R
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode
import com.vibe.app.presentation.common.LocalDynamicTheme
import com.vibe.app.presentation.common.LocalThemeMode
import com.vibe.app.presentation.common.LocalThemeViewModel
import com.vibe.app.presentation.common.RadioItem
import com.vibe.app.presentation.common.SettingItem
import com.vibe.app.util.getClientTypeDisplayName
import com.vibe.app.util.getDynamicThemeTitle
import com.vibe.app.util.getThemeModeTitle
import com.vibe.app.util.pinnedExitUntilCollapsedScrollBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToPlatformSetting: (String) -> Unit,
    onNavigateToAboutPage: () -> Unit,
    onNavigateToBuildRuntimeDebug: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val platformState by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val switchedHint = stringResource(R.string.switched_platform_hint)

    LaunchedEffect(Unit) {
        settingViewModel.switchedPlatformEvent.collect { name ->
            Toast.makeText(context, switchedHint.format(name), Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingTopBar(
                scrollBehavior = scrollBehavior,
                navigationOnClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            // General
            ThemeSetting { settingViewModel.openThemeDialog() }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Platforms
            platformState.forEach { platform ->
                PlatformItem(
                    platform = platform,
                    onItemClick = { onNavigateToPlatformSetting(platform.uid) },
                    onDeleteClick = { settingViewModel.openDeleteDialog(platform.id) }
                )
            }

            SettingItem(
                title = stringResource(R.string.add_platform),
                description = stringResource(R.string.add_platform_description),
                onItemClick = onNavigateToAddPlatform,
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // About
            AboutPageItem(onItemClick = onNavigateToAboutPage)

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Developer Options
            DebugModeSetting(
                isEnabled = settingViewModel.debugMode.collectAsStateWithLifecycle().value,
                onToggle = settingViewModel::toggleDebugMode
            )

            if (settingViewModel.debugMode.collectAsStateWithLifecycle().value) {
                SettingItem(
                    title = "Build Runtime (debug)",
                    description = "Trigger bootstrap + launch test process",
                    onItemClick = onNavigateToBuildRuntimeDebug,
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            if (dialogState.isThemeDialogOpen) {
                ThemeSettingDialog(settingViewModel)
            }

            if (dialogState.isDeleteDialogOpen) {
                DeletePlatformDialog(settingViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    navigationOnClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = stringResource(R.string.settings),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = navigationOnClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun ThemeSetting(
    onItemClick: () -> Unit
) {
    SettingItem(
        title = stringResource(R.string.theme_settings),
        description = stringResource(R.string.theme_description),
        onItemClick = onItemClick,
        showTrailingIcon = false,
        showLeadingIcon = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun AboutPageItem(
    onItemClick: () -> Unit
) {
    SettingItem(
        title = stringResource(R.string.about),
        description = stringResource(R.string.about_description),
        onItemClick = onItemClick,
        showTrailingIcon = true,
        showLeadingIcon = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun ThemeSettingDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    val themeViewModel = LocalThemeViewModel.current
    AlertDialog(
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(text = stringResource(R.string.dynamic_theme), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                DynamicTheme.entries.forEach { theme ->
                    RadioItem(
                        title = getDynamicThemeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalDynamicTheme.current == theme
                    ) {
                        themeViewModel.updateDynamicTheme(theme)
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
                Text(text = stringResource(R.string.dark_mode), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                ThemeMode.entries.forEach { theme ->
                    RadioItem(
                        title = getThemeModeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalThemeMode.current == theme
                    ) {
                        themeViewModel.updateThemeMode(theme)
                    }
                }
            }
        },
        onDismissRequest = settingViewModel::closeThemeDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::closeThemeDialog
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
fun PlatformItem(
    platform: PlatformV2,
    onItemClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    SettingItem(
        title = platform.name,
        description = "${getClientTypeDisplayName(platform.compatibleType)} • ${if (platform.enabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)}",
        onItemClick = onItemClick,
        showTrailingIcon = true,
        showLeadingIcon = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = if (platform.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun DeletePlatformDialog(
    settingViewModel: SettingViewModelV2 = hiltViewModel()
) {
    AlertDialog(
        title = {
            Text(stringResource(R.string.delete_platform))
        },
        text = {
            Text(stringResource(R.string.delete_platform_confirmation))
        },
        onDismissRequest = settingViewModel::closeDeleteDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::confirmDelete
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = settingViewModel::closeDeleteDialog
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DebugModeSetting(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        headlineContent = { Text(stringResource(R.string.debug_log)) },
        supportingContent = { Text(stringResource(R.string.debug_log_description)) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    )
}
