package com.vibe.app.presentation.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider.getUriForFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vibe.app.R
import com.vibe.app.data.model.ClientType
import com.vibe.app.util.FileUtils
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onNavigateToAddPlatform: () -> Unit,
    onBackAction: () -> Unit
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidthDp = with(LocalDensity.current) { containerSize.width.toDp() }
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboard.current
    val systemChatMargin = 32.dp
    val maximumUserChatBubbleWidth = (screenWidthDp - systemChatMargin) * 0.8F
    val maximumOpponentChatBubbleWidth = screenWidthDp - systemChatMargin
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val chatRoom by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    val groupedMessages by chatViewModel.groupedMessages.collectAsStateWithLifecycle()
    val indexStates by chatViewModel.indexStates.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()
    val crashPrompt by chatViewModel.crashPrompt.collectAsStateWithLifecycle()
    val isProjectNameDialogOpen by chatViewModel.isProjectNameDialogOpen.collectAsStateWithLifecycle()
    val isEditQuestionDialogOpen by chatViewModel.isEditQuestionDialogOpen.collectAsStateWithLifecycle()
    val currentProjectId by chatViewModel.currentProjectId.collectAsStateWithLifecycle()
    val projectName by chatViewModel.projectName.collectAsStateWithLifecycle()
    val isBuildRunning by chatViewModel.isBuildRunning.collectAsStateWithLifecycle()
    val buildProgress by chatViewModel.buildProgress.collectAsStateWithLifecycle()
    val isSelectTextSheetOpen by chatViewModel.isSelectTextSheetOpen.collectAsStateWithLifecycle()
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val question by chatViewModel.question.collectAsStateWithLifecycle()
    val selectedFiles by chatViewModel.selectedFiles.collectAsStateWithLifecycle()
    val allPlatforms by chatViewModel.platformsInApp.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val enabledPlatformsInChat by chatViewModel.enabledPlatformsInChat.collectAsStateWithLifecycle()
    val chatPlatforms = remember(allPlatforms, enabledPlatformsInChat) {
        enabledPlatformsInChat.mapNotNull { uid ->
            allPlatforms.firstOrNull { it.uid == uid }
        }
    }
    val hasConfiguredPlatforms = allPlatforms.isNotEmpty()
    val canUseChat = appEnabledPlatforms.isNotEmpty()
    val imageInputSupportedTypes = setOf(ClientType.KIMI, ClientType.OPENAI, ClientType.ANTHROPIC)
    val isImageInputEnabled = chatPlatforms.isNotEmpty() &&
        chatPlatforms.size == enabledPlatformsInChat.size &&
        chatPlatforms.all { it.compatibleType in imageInputSupportedTypes }
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    val runButtonEnabled = isIdle && !isBuildRunning && currentProjectId != null
    val isChatMenuEnabled = chatRoom.id > 0
    val isProjectMenuEnabled = currentProjectId != null
    val context = LocalContext.current
    val imageInputNotSupportedText = stringResource(R.string.image_input_not_supported)
    val disabledInputPlaceholder = if (hasConfiguredPlatforms) {
        stringResource(R.string.some_platforms_disabled)
    } else {
        stringResource(R.string.add_api_key_to_start_chatting)
    }
    val showPlatformSetupPrompt = !hasConfiguredPlatforms && groupedMessages.userMessages.isEmpty()

    val scope = rememberCoroutineScope()

    // Reliable last-item index derived from the actual LazyColumn layout
    val lastItemIndex by remember {
        derivedStateOf { (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0) }
    }

    // Whether the user is currently near the bottom of the list (within 3 items)
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= layoutInfo.totalItemsCount - 3
        }
    }

    // Show scroll-to-bottom button only when scrolled far enough from bottom (>= 5 items away)
    val showScrollToBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index < layoutInfo.totalItemsCount - 5
        }
    }

    // Scroll to bottom when messages are first loaded from database
    LaunchedEffect(isLoaded) {
        if (isLoaded) {
            // Wait for message items to appear in the LazyColumn (more than just the spacer)
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 1 }
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // Auto-scroll when a new conversation round starts (new user message added)
    val messageCount = groupedMessages.userMessages.size
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    // Auto-scroll when crash prompt appears
    LaunchedEffect(crashPrompt) {
        if (crashPrompt != null) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    // Auto-scroll to bottom when keyboard opens
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(100) // Small delay to let keyboard animation start
            listState.scrollToItem(lastItemIndex)
        }
    }

    // Single streaming scroll handler: keep view anchored to bottom while AI is generating.
    // Monitors layout changes (item count, content height) so both new items and growing
    // text within existing items trigger a scroll.
    LaunchedEffect(isIdle) {
        if (isIdle) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            // Reading totalItemsCount + last visible item's bottom edge covers both
            // "new item added" and "existing item grew taller" scenarios.
            val lastVisibleBottom = info.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size } ?: 0
            Triple(info.totalItemsCount, lastVisibleBottom, info.viewportEndOffset)
        }.collect { (totalItems, _, _) ->
            if (totalItems <= 0) return@collect
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@collect
            // Only auto-scroll when the user hasn't scrolled far up (within 3 items of bottom)
            if (lastVisible.index >= info.totalItemsCount - 3) {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.d("RunBuild", "buildEvent collector started")
        chatViewModel.buildEvent.collect { event ->
            Log.d("RunBuild", "buildEvent received: $event")
            when (event) {
                is ChatViewModel.BuildEvent.InstallApk -> installApk(context, event.apkPath)
            }
        }
    }

    // Re-sync platforms and check for new crash logs when returning from plugin/settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                chatViewModel.refreshPlatforms()
                chatViewModel.checkForNewCrashLog()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                projectName ?: chatRoom.title,
                isChatMenuEnabled,
                runButtonEnabled,
                isMoreOptionsEnabled = isIdle,
                isProjectMenuEnabled,
                buildProgress = buildProgress.progress,
                isBuildProgressVisible = buildProgress.isVisible,
                onBackAction,
                scrollBehavior,
                chatViewModel::openProjectNameDialog,
                chatViewModel::runBuild,
                onInstallApkClick = { chatViewModel.installBuild() },
                onExportChatItemClick = {
                    scope.launch {
                        exportChat(context, chatViewModel)
                    }
                },
                onExportSourceCodeItemClick = {
                    val projectId = currentProjectId ?: return@ChatTopBar
                    scope.launch { exportSourceCode(context, projectId) }
                },
                onExportApkItemClick = {
                    scope.launch {
                        val apkPath = withContext(Dispatchers.IO) { chatViewModel.getSignedApkPath() }
                        if (apkPath != null) {
                            shareApk(context, apkPath)
                        } else {
                            Toast.makeText(context, "No built APK found. Please run a build first.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    Log.d("ChatScreen", "GroupMessage: $groupedMessages")
                    if (showPlatformSetupPrompt) {
                        item(key = "platform_setup_prompt") {
                            MissingPlatformPromptCard(
                                onAddApiKeyClick = onNavigateToAddPlatform,
                            )
                        }
                    }
                    groupedMessages.userMessages.forEachIndexed { i, message ->
                        // i: index of nth message
                        val platformIndexState = indexStates.getOrElse(i) { 0 }
                        val assistantMessages = groupedMessages.assistantMessages.getOrNull(i) ?: emptyList()
                        val assistantContent = assistantMessages.getOrNull(platformIndexState)?.content ?: ""
                        val isCurrentPlatformLoading = loadingStates.getOrElse(platformIndexState) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
                        item(key = "user_$i") {
                            var isDropDownMenuExpanded by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Box {
                                    UserChatBubble(
                                        modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                                        text = message.content,
                                        files = message.files,
                                        onLongPress = { isDropDownMenuExpanded = true }
                                    )
                                    ChatBubbleDropdownMenu(
                                        isChatBubbleDropdownMenuExpanded = isDropDownMenuExpanded,
                                        canEdit = canUseChat && isIdle,
                                        onDismissRequest = { isDropDownMenuExpanded = false },
                                        onEditItemClick = { chatViewModel.openEditQuestionDialog(message) },
                                        onCopyItemClick = { scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(message.content, message.content))) } }
                                    )
                                }
                            }
                        }
                        // Agent step items (thinking, tool calls) — each as its own list item
                        val turnSteps = groupedMessages.agentSteps.getOrNull(i) ?: emptyList()
                        val isLastTurn = i == groupedMessages.assistantMessages.size - 1
                        val isTurnLoading = if (isLastTurn) isCurrentPlatformLoading else false

                        // Platform header
                        item(key = "platform_$i") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp)
                                    .padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VibeAppIcon(if (isLastTurn) !isIdle else false)
                                run {
                                    val assistantMsg = assistantMessages.getOrNull(platformIndexState)
                                    val platformName = assistantMsg?.platformType
                                        ?.let { uid ->
                                            allPlatforms.find { it.uid == uid }?.name
                                                ?: appEnabledPlatforms.find { it.uid == uid }?.name
                                                ?: chatPlatforms.find { it.uid == uid }?.name
                                        }
                                        ?: chatPlatforms.getOrNull(platformIndexState)?.name
                                        ?: stringResource(R.string.unknown)
                                    Text(
                                        text = platformName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }

                        // Render each step as its own item
                        if (turnSteps.isNotEmpty()) {
                            turnSteps.forEachIndexed { stepIdx, step ->
                                if (step.type != com.vibe.app.feature.agent.AgentStepType.OUTPUT) {
                                    item(key = "step_${i}_${stepIdx}") {
                                        val isLiveStep = isTurnLoading && stepIdx == turnSteps.lastIndex
                                        AgentStepBubble(
                                            step = step,
                                            isLive = isLiveStep,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Final output bubble (the assistant's text response)
                        item(key = "assistant_$i") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                OpponentChatBubble(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp)
                                        .widthIn(max = maximumOpponentChatBubbleWidth),
                                    canRetry = canUseChat && isLastTurn && !isTurnLoading,
                                    isLoading = isTurnLoading,
                                    text = assistantContent,
                                    onCopyClick = { scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(assistantContent, assistantContent))) } },
                                    onSelectClick = { chatViewModel.openSelectTextSheet(assistantContent) },
                                    onRetryClick = { chatViewModel.retryChat(platformIndexState) }
                                )
                            }
                        }
                    }
                    // Crash auto-fix prompt (shown when plugin crash is detected)
                    if (crashPrompt != null) {
                        item(key = "crash_prompt") {
                            CrashAutoFixCard(
                                crashSummary = crashPrompt!!.crashSummary,
                                onAutoFix = { chatViewModel.autoFixCrash() },
                                onDismiss = { chatViewModel.dismissCrashPrompt() },
                            )
                        }
                    }
                    // Bottom spacer so scrolling to this item reveals the full last message
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }

                if (showScrollToBottom) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ScrollToBottomButton {
                            scope.launch {
                                listState.animateScrollToItem(lastItemIndex)
                            }
                        }
                    }
                }
            }

            ChatInputBox(
                value = question,
                onValueChange = { s -> chatViewModel.updateQuestion(s) },
                chatEnabled = canUseChat,
                disabledPlaceholderText = disabledInputPlaceholder,
                sendButtonEnabled = question.trim().isNotBlank() && isIdle,
                isResponding = !isIdle,
                imageInputEnabled = isImageInputEnabled,
                selectedFiles = selectedFiles,
                onFileSelected = { filePath -> chatViewModel.addSelectedFile(filePath) },
                onFileRemoved = { filePath -> chatViewModel.removeSelectedFile(filePath) },
                onUnsupportedImageInputClick = {
                    Toast.makeText(
                        context,
                        imageInputNotSupportedText,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onStopClick = { chatViewModel.stopResponding() }
            ) {
                chatViewModel.askQuestion()
                focusManager.clearFocus()
            }
        }

        if (isProjectNameDialogOpen) {
            ProjectNameDialog(
                initialProjectName = projectName ?: chatRoom.title,
                onConfirmRequest = { name -> chatViewModel.updateProjectName(name) },
                onDismissRequest = chatViewModel::closeProjectNameDialog
            )
        }

        if (isEditQuestionDialogOpen) {
            val editedQuestion by chatViewModel.editedQuestion.collectAsStateWithLifecycle()
            ChatQuestionEditDialog(
                initialQuestion = editedQuestion,
                onDismissRequest = chatViewModel::closeEditQuestionDialog,
                onConfirmRequest = { question ->
                    chatViewModel.editQuestion(question)
                    chatViewModel.closeEditQuestionDialog()
                }
            )
        }

        if (isSelectTextSheetOpen) {
            val selectedText by chatViewModel.selectedText.collectAsStateWithLifecycle()
            ModalBottomSheet(onDismissRequest = chatViewModel::closeSelectTextSheet) {
                SelectionContainer(
                    modifier = Modifier
                        .padding(24.dp)
                        .heightIn(min = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(selectedText)
                }
            }
        }
    }
}

@Composable
private fun MissingPlatformPromptCard(
    onAddApiKeyClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_key),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.add_api_key_prompt_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(R.string.add_api_key_prompt_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .align(Alignment.End),
                onClick = onAddApiKeyClick,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.add_api_key))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    title: String,
    isChatMenuEnabled: Boolean,
    isRunEnabled: Boolean,
    isMoreOptionsEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    buildProgress: Float,
    isBuildProgressVisible: Boolean,
    onBackAction: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onUpdateProjectNameClick: () -> Unit,
    onRunClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit
) {
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(
                    onClick = onBackAction
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                }
            },
            actions = {
                IconButton(
                    enabled = isRunEnabled,
                    onClick = onRunClick
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_run),
                        contentDescription = stringResource(R.string.run)
                    )
                }
                IconButton(
                    enabled = isMoreOptionsEnabled,
                    onClick = { isDropDownMenuExpanded = isDropDownMenuExpanded.not() }
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.options))
                }

                ChatDropdownMenu(
                    isDropdownMenuExpanded = isDropDownMenuExpanded,
                    isChatMenuEnabled = isChatMenuEnabled,
                    isProjectMenuEnabled = isProjectMenuEnabled,
                    onDismissRequest = { isDropDownMenuExpanded = false },
                    onUpdateProjectNameClick = {
                        onUpdateProjectNameClick.invoke()
                        isDropDownMenuExpanded = false
                    },
                    onInstallApkClick = {
                        onInstallApkClick()
                        isDropDownMenuExpanded = false
                    },
                    onExportChatItemClick = onExportChatItemClick,
                    onExportSourceCodeItemClick = {
                        onExportSourceCodeItemClick()
                        isDropDownMenuExpanded = false
                    },
                    onExportApkItemClick = {
                        onExportApkItemClick()
                        isDropDownMenuExpanded = false
                    }
                )
            },
            scrollBehavior = scrollBehavior
        )

        LinearProgressIndicator(
            progress = { buildProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .alpha(if (isBuildProgressVisible) 1f else 0f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isChatMenuEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onUpdateProjectNameClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.update_project_name)) },
            onClick = onUpdateProjectNameClick
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.install_apk)) },
            onClick = onInstallApkClick
        )
        DropdownMenuItem(
            enabled = isChatMenuEnabled,
            text = { Text(text = stringResource(R.string.export_chat)) },
            onClick = {
                onExportChatItemClick()
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.export_source_code)) },
            onClick = onExportSourceCodeItemClick
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.export_apk)) },
            onClick = onExportApkItemClick
        )
    }
}

@Composable
fun ChatBubbleDropdownMenu(
    isChatBubbleDropdownMenuExpanded: Boolean,
    canEdit: Boolean,
    onDismissRequest: () -> Unit,
    onEditItemClick: () -> Unit,
    onCopyItemClick: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isChatBubbleDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = canEdit,
            leadingIcon = {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit)
                )
            },
            text = { Text(text = stringResource(R.string.edit)) },
            onClick = {
                onEditItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.copy_text)
                )
            },
            text = { Text(text = stringResource(R.string.copy_text)) },
            onClick = {
                onCopyItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
    }
}

private suspend fun exportSourceCode(context: Context, projectId: String) {
    try {
        val sourceDir = File(context.filesDir, "projects/$projectId/app")
        if (!sourceDir.exists()) {
            Toast.makeText(context, "Project workspace not found", Toast.LENGTH_SHORT).show()
            return
        }
        val zipFileName = "${projectId}_source.zip"
        val exportDir = context.getExternalFilesDir(null)!!
        val zipFile = File(exportDir, zipFileName)
        withContext(Dispatchers.IO) {
            if (zipFile.exists()) zipFile.delete()
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                sourceDir.walkTopDown()
                    .filter { file ->
                        // Exclude build output to keep zip size small
                        file.toRelativeString(sourceDir).split(File.separator).none { it == "build" }
                    }
                    .forEach { file ->
                        if (file.isFile) {
                            val entryName = file.toRelativeString(sourceDir)
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
            }
        }
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Source Code").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ExportSource", "Failed to export source code", e)
        Toast.makeText(context, "Failed to export source code", Toast.LENGTH_SHORT).show()
    }
}

private fun shareApk(context: Context, apkPath: String) {
    try {
        val file = File(apkPath)
        if (!file.exists()) {
            Toast.makeText(context, "APK file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share APK").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ExportApk", "Failed to share APK", e)
        Toast.makeText(context, "Failed to share APK", Toast.LENGTH_SHORT).show()
    }
}

private fun installApk(context: Context, apkPath: String) {
    Log.d("RunBuild", "installApk called: apkPath=$apkPath")
    val file = File(apkPath)
    Log.d("RunBuild", "APK file exists=${file.exists()}, size=${file.length()}")
    try {
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", file)
        Log.d("RunBuild", "FileProvider URI=$uri")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("RunBuild", "startActivity(install intent) succeeded")
    } catch (e: Exception) {
        Log.e("RunBuild", "installApk failed", e)
    }
}

private suspend fun exportChat(context: Context, chatViewModel: ChatViewModel) {
    try {
        val exportBundle = chatViewModel.exportChat()
        val zipFile = File(context.getExternalFilesDir(null), exportBundle.zipFileName)
        withContext(Dispatchers.IO) {
            if (zipFile.exists()) zipFile.delete()
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("chat.md"))
                zos.write(exportBundle.chatMarkdown.toByteArray())
                zos.closeEntry()

                exportBundle.diagnosticLogContent?.takeIf { it.isNotBlank() }?.let { diagnosticContent ->
                    zos.putNextEntry(ZipEntry("diagnostic-log.ndjson"))
                    zos.write(diagnosticContent.toByteArray())
                    zos.closeEntry()
                }

                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(exportBundle.manifestJson.toByteArray())
                zos.closeEntry()
            }
        }
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Chat Export").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ChatExport", "Failed to export chat", e)
        Toast.makeText(context, "Failed to export chat", Toast.LENGTH_SHORT).show()
    }
}

@Preview
@Composable
fun ChatInputBox(
    value: String = "",
    onValueChange: (String) -> Unit = {},
    chatEnabled: Boolean = true,
    disabledPlaceholderText: String = "Some APIs are disabled",
    sendButtonEnabled: Boolean = true,
    isResponding: Boolean = false,
    imageInputEnabled: Boolean = false,
    selectedFiles: List<String> = emptyList(),
    onFileSelected: (String) -> Unit = {},
    onFileRemoved: (String) -> Unit = {},
    onUnsupportedImageInputClick: () -> Unit = {},
    onStopClick: () -> Unit = {},
    onSendButtonClick: (String) -> Unit = {}
) {
    val localStyle = LocalTextStyle.current
    val mergedStyle = localStyle.merge(TextStyle(color = LocalContentColor.current))
    val context = LocalContext.current
    val supportedImageFormatsText = stringResource(R.string.supported_image_formats)
    val failedToSelectImageText = stringResource(R.string.failed_to_select_image)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val mimeType = FileUtils.getMimeType(context, it.toString())
            if (!FileUtils.isVisionSupportedImage(mimeType)) {
                Toast.makeText(
                    context,
                    supportedImageFormatsText,
                    Toast.LENGTH_SHORT,
                ).show()
                return@rememberLauncherForActivityResult
            }

            val filePath = copyFileToAppDirectory(context, it)
            if (filePath != null) {
                onFileSelected(filePath)
            } else {
                Toast.makeText(
                    context,
                    failedToSelectImageText,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (selectedFiles.isNotEmpty()) {
            FileThumbnailRow(
                selectedFiles = selectedFiles,
                onFileRemoved = onFileRemoved
            )
        }
        BasicTextField(
            modifier = Modifier
                .heightIn(max = 120.dp),
            value = value,
            enabled = chatEnabled,
            textStyle = mergedStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onValueChange = { if (chatEnabled) onValueChange(it) },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = chatEnabled,
                        modifier = Modifier.size(44.dp),
                        onClick = {
                            if (imageInputEnabled) {
                                filePickerLauncher.launch("image/*")
                            } else {
                                onUnsupportedImageInputClick()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_add_file),
                            contentDescription = stringResource(R.string.select_image),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                            .padding(start = 4.dp)
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                modifier = Modifier.alpha(0.38f),
                                text = if (chatEnabled) {
                                    stringResource(R.string.ask_a_question)
                                } else {
                                    disabledPlaceholderText
                                }
                            )
                        }
                        innerTextField()
                    }
                    if (isResponding) {
                        IconButton(
                            modifier = Modifier.size(44.dp),
                            onClick = { onStopClick() }
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_pause),
                                contentDescription = stringResource(R.string.stop),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    } else {
                        IconButton(
                            enabled = chatEnabled && sendButtonEnabled,
                            modifier = Modifier.size(44.dp),
                            onClick = { onSendButtonClick(value) }
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_send_btn),
                                contentDescription = stringResource(R.string.send),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun FileThumbnailRow(
    selectedFiles: List<String>,
    onFileRemoved: (String) -> Unit
) {
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        selectedFiles.forEach { filePath ->
            FileThumbnail(
                filePath = filePath,
                onImageClick = { previewImagePath = filePath },
                onRemove = { onFileRemoved(filePath) }
            )
        }
    }

    previewImagePath?.let { imagePath ->
        FullscreenImagePreview(
            filePath = imagePath,
            onDismissRequest = { previewImagePath = null }
        )
    }
}

@Composable
private fun FileThumbnail(
    filePath: String,
    onImageClick: () -> Unit,
    onRemove: () -> Unit
) {
    val file = File(filePath)
    val isImage = isImageFile(file.extension)

    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (isImage) Modifier.clickable(onClick = onImageClick) else Modifier)
        ) {
            if (isImage) {
                AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .background(
                        MaterialTheme.colorScheme.error,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(72.dp)
        )
    }
}

private fun copyFileToAppDirectory(context: Context, uri: android.net.Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val rawFileName = getFileName(context, uri)
        val sanitizedFileName = sanitizeFileName(rawFileName)

        val attachmentsDir = File(context.filesDir, "attachments")
        attachmentsDir.mkdirs()

        var targetFile = File(attachmentsDir, sanitizedFileName)

        // If file exists, append timestamp to avoid overwrites
        if (targetFile.exists()) {
            val nameWithoutExt = sanitizedFileName.substringBeforeLast(".")
            val ext = sanitizedFileName.substringAfterLast(".", "")
            val uniqueName = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_${System.currentTimeMillis()}.$ext"
            } else {
                "${sanitizedFileName}_${System.currentTimeMillis()}"
            }
            targetFile = File(attachmentsDir, uniqueName)
        }

        // Verify canonical path is within attachments directory to prevent path traversal
        val attachmentsDirCanonical = attachmentsDir.canonicalPath
        val targetFileCanonical = targetFile.canonicalPath
        if (!targetFileCanonical.startsWith(attachmentsDirCanonical + File.separator) &&
            targetFileCanonical != attachmentsDirCanonical
        ) {
            return null
        }

        inputStream?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        targetFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun getFileName(context: Context, uri: android.net.Uri): String {
    var fileName = "attachment_${System.currentTimeMillis()}"

    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex != -1) {
            fileName = cursor.getString(nameIndex) ?: fileName
        }
    }

    return fileName
}

private fun sanitizeFileName(fileName: String): String {
    val maxLength = 200

    // Remove path separators and ".." segments
    val withoutPathTraversal = fileName
        .replace("..", "")
        .replace("/", "")
        .replace("\\", "")

    // Keep only safe characters: alphanumerics, dash, underscore, dot
    val sanitized = withoutPathTraversal
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .take(maxLength)
        .trim('.')

    // If sanitized name is empty, generate a fallback
    return sanitized.ifEmpty { "attachment_${System.currentTimeMillis()}" }
}

private fun isImageFile(extension: String?): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    return extension?.lowercase() in imageExtensions
}

@Composable
fun ScrollToBottomButton(onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = Color.White,
        contentColor = Color.Black,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 3.dp,
        ),
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom_icon))
    }
}
