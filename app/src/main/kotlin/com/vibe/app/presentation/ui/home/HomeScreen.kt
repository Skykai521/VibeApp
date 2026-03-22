package com.vibe.app.presentation.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.app.R
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.database.entity.ProjectWithChat
import com.vibe.app.feature.projecticon.ProjectIconRenderer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onProjectClick: (chatId: Int, enabledPlatforms: List<String>) -> Unit,
    navigateToChat: (chatId: Int, enabledPlatforms: List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val projectListState by homeViewModel.projectListState.collectAsStateWithLifecycle()
    val showDeleteWarningDialog by homeViewModel.showDeleteWarningDialog.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Handle navigation events from ViewModel
    LaunchedEffect(projectListState.navigationEvent) {
        projectListState.navigationEvent?.let { event ->
            when (event) {
                is HomeViewModel.NavigationEvent.OpenProject -> {
                    navigateToChat(event.chatId, event.enabledPlatforms)
                    homeViewModel.consumeNavigationEvent()
                }
            }
        }
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED &&
            !projectListState.isSelectionMode &&
            !projectListState.isSearchMode
        ) {
            homeViewModel.fetchProjects()
            homeViewModel.fetchPlatformStatus()
        }
    }

    BackHandler(enabled = projectListState.isSelectionMode || projectListState.isSearchMode) {
        when {
            projectListState.isSelectionMode -> homeViewModel.disableSelectionMode()
            projectListState.isSearchMode -> homeViewModel.disableSearchMode()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopAppBar(
                isSelectionMode = projectListState.isSelectionMode,
                isSearchMode = projectListState.isSearchMode,
                selectedCount = projectListState.selectedProjects.count { it },
                scrollBehavior = scrollBehavior,
                settingsOnClick = settingOnClick,
                navigationOnClick = {
                    if (projectListState.isSelectionMode) {
                        homeViewModel.disableSelectionMode()
                        return@HomeTopAppBar
                    }
                    if (projectListState.isSearchMode) {
                        homeViewModel.disableSearchMode()
                    } else {
                        homeViewModel.enableSearchMode()
                    }
                },
                onSearchQueryChanged = homeViewModel::updateSearchQuery,
                searchQuery = searchQuery,
            )
        },
        floatingActionButton = {
            if (projectListState.isSelectionMode) {
                DeleteProjectsButton(
                    selectedCount = projectListState.selectedProjects.count { it },
                    onClick = { homeViewModel.openDeleteWarningDialog() },
                )
            } else if (!projectListState.isSearchMode) {
                NewProjectButton(
                    expanded = listState.isScrollingUp(),
                    isCreating = projectListState.creationState is HomeViewModel.ProjectCreationState.InProgress,
                    onClick = { homeViewModel.createNewProject() },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            state = listState,
        ) {
            if (!projectListState.isSearchMode) {
                item { ProjectsTitle(scrollBehavior) }
            }
            if (projectListState.isSearchMode && projectListState.projects.isEmpty() && searchQuery.isNotEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        text = stringResource(R.string.no_search_results),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(projectListState.projects, key = { _, it -> it.project.projectId }) { idx, pwc ->
                ProjectListItem(
                    pwc = pwc,
                    isSelectionMode = projectListState.isSelectionMode,
                    isSelected = projectListState.selectedProjects.getOrElse(idx) { false },
                    onLongClick = {
                        if (!projectListState.isSearchMode) {
                            homeViewModel.enableSelectionMode()
                            homeViewModel.selectProject(idx)
                        }
                    },
                    onClick = {
                        if (projectListState.isSelectionMode) {
                            homeViewModel.selectProject(idx)
                        } else {
                            onProjectClick(pwc.project.chatId, pwc.chat.enabledPlatform)
                        }
                    },
                )
                if (idx < projectListState.projects.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        if (showDeleteWarningDialog) {
            DeleteWarningDialog(
                onDismissRequest = homeViewModel::closeDeleteWarningDialog,
                onConfirm = {
                    val deletedCount = projectListState.selectedProjects.count { it }
                    homeViewModel.deleteSelectedProjects()
                    Toast.makeText(
                        context,
                        context.getString(R.string.deleted_projects, deletedCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                    homeViewModel.closeDeleteWarningDialog()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectListItem(
    pwc: ProjectWithChat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(start = 8.dp, end = 8.dp),
        headlineContent = {
            Text(text = pwc.project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            } else {
                ProjectListItemIcon(workspacePath = pwc.project.workspacePath)
            }
        },
        trailingContent = {
            BuildStatusBadge(status = pwc.project.buildStatus)
        },
        supportingContent = {
            val displayText = pwc.lastMessageContent?.replace('\n', ' ')?.trim()
                ?: formatUpdatedAt(pwc.chat.updatedAt)
            Text(
                text = displayText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ProjectListItemIcon(workspacePath: String) {
    val iconSize = 40.dp
    val iconSizePx = with(LocalDensity.current) { iconSize.roundToPx() }
    val iconSignature = ProjectIconRenderer.iconSignature(workspacePath)
    val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        workspacePath,
        iconSignature,
        iconSizePx,
    ) {
        value = ProjectIconRenderer.loadProjectIcon(workspacePath, iconSizePx)
    }

    Box(
        modifier = Modifier
            .size(iconSize)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f),
                spotColor = Color.Black.copy(alpha = 0.15f),
            )
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_rounded_chat),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun BuildStatusBadge(status: ProjectBuildStatus) {
    when (status) {
        ProjectBuildStatus.INITIALIZING -> CircularProgressIndicator(
            modifier = Modifier.padding(4.dp),
            strokeWidth = 2.dp,
        )
        ProjectBuildStatus.SUCCESS -> Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
            Text("\u2713")
        }
        ProjectBuildStatus.FAILED -> Badge(containerColor = MaterialTheme.colorScheme.error) {
            Text("!")
        }
        ProjectBuildStatus.BUILDING -> CircularProgressIndicator(
            modifier = Modifier.padding(4.dp),
            strokeWidth = 2.dp,
        )
        ProjectBuildStatus.READY -> Unit
    }
}

@Composable
private fun DeleteProjectsButton(
    selectedCount: Int,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) },
        text = { Text(text = stringResource(R.string.delete) + " ($selectedCount)") },
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    isSelectionMode: Boolean,
    isSearchMode: Boolean,
    selectedCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    settingsOnClick: () -> Unit,
    navigationOnClick: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchQuery: String,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            scrolledContainerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified,
            containerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            titleContentColor = if (isSelectionMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
        ),
        title = {
            when {
                isSearchMode -> {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_projects)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChanged("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                    )
                }
                isSelectionMode -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.projects_selected, selectedCount),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.projects),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = scrollBehavior.state.overlappedFraction),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            when {
                isSelectionMode -> IconButton(modifier = Modifier.padding(4.dp), onClick = navigationOnClick) {
                    Icon(Icons.Rounded.Close, tint = MaterialTheme.colorScheme.onPrimaryContainer, contentDescription = stringResource(R.string.close))
                }
                isSearchMode -> IconButton(modifier = Modifier.padding(4.dp), onClick = navigationOnClick) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
                else -> IconButton(modifier = Modifier.padding(4.dp), onClick = navigationOnClick) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search_projects))
                }
            }
        },
        actions = {
            if (!isSelectionMode && !isSearchMode) {
                IconButton(modifier = Modifier.padding(4.dp), onClick = settingsOnClick) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsTitle(scrollBehavior: TopAppBarScrollBehavior) {
    Text(
        modifier = Modifier
            .padding(top = 32.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        text = stringResource(R.string.projects),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 1.0F - scrollBehavior.state.overlappedFraction),
        style = MaterialTheme.typography.headlineLarge,
    )
}

@Composable
private fun NewProjectButton(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    isCreating: Boolean = false,
    onClick: () -> Unit = {},
) {
    val orientation = LocalConfiguration.current.orientation
    val fabModifier = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        modifier.systemBarsPadding()
    } else {
        modifier
    }
    ExtendedFloatingActionButton(
        modifier = fabModifier
            .padding(bottom = 16.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.2f),
                spotColor = Color.Black.copy(alpha = 0.35f),
            ),
        onClick = { if (!isCreating) onClick() },
        expanded = expanded,
        containerColor = Color(0xFF1A1A1A),
        contentColor = Color.White,
        icon = {
            if (isCreating) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_project))
            }
        },
        text = { Text(text = stringResource(R.string.new_project)) },
    )
}

@Composable
private fun DeleteWarningDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.delete_selected_projects)) },
        text = { Text(stringResource(R.string.this_operation_can_t_be_undone)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

private fun formatUpdatedAt(unixSeconds: Long): String {
    val date = Date(unixSeconds * 1000)
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { time = date }

    return when {
        isSameDay(now, target) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        isYesterday(now, target) -> {
            val locale = Locale.getDefault()
            val yesterday = if (locale.language == "zh") "昨天" else "Yesterday"
            "$yesterday ${SimpleDateFormat("HH:mm", locale).format(date)}"
        }
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) ->
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(date)
        else ->
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(date)
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYesterday(now: Calendar, target: Calendar): Boolean {
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(yesterday, target)
}
