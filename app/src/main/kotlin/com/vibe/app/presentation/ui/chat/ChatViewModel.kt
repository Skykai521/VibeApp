package com.vibe.app.presentation.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.repository.ChatRepository
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.data.repository.SettingRepository
import com.vibe.app.feature.agent.service.AgentSessionManager
import com.vibe.app.feature.agent.service.AgentSessionStatus
import com.vibe.app.feature.agent.service.SessionMessageState
import com.vibe.app.feature.agent.service.BuildMutex
import com.vibe.app.feature.diagnostic.BuildTriggerSource
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ChatTurnDiagnosticContext
import com.vibe.app.feature.diagnostic.DiagnosticContext
import com.vibe.app.feature.projectinit.ProjectInitializer
import com.vibe.app.util.getPlatformName
import com.vibe.app.util.handleStates
import com.vibe.app.util.FileUtils
import com.vibe.app.plugin.PluginManager
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildMode
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import com.vibe.build.engine.pipeline.BuildProgressListener
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val projectRepository: ProjectRepository,
    private val projectInitializer: ProjectInitializer,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val sessionManager: AgentSessionManager,
    private val buildMutex: BuildMutex,
    private val pluginManager: PluginManager,
) : ViewModel() {
    sealed class LoadingState {
        data object Idle : LoadingState()
        data object Loading : LoadingState()
    }

    sealed class BuildEvent {
        data class InstallApk(val apkPath: String) : BuildEvent()
    }

    data class GroupedMessages(
        val userMessages: List<MessageV2> = listOf(),
        val assistantMessages: List<List<MessageV2>> = listOf()
    )

    data class BuildProgressUiState(
        val isVisible: Boolean = false,
        val progress: Float = 0f,
        val currentStage: BuildStage? = null,
    )

    data class CrashPrompt(
        val crashSummary: String,
    )

    data class ChatExportBundle(
        val zipFileName: String,
        val chatMarkdown: String,
        val diagnosticLogContent: String?,
        val manifestJson: String,
    )

    private data class ActiveTurnState(
        val diagnosticChatId: Int,
        val context: ChatTurnDiagnosticContext,
    )

    private val chatRoomId: Int = checkNotNull(savedStateHandle["chatRoomId"])
    private val enabledPlatformString: String = checkNotNull(savedStateHandle["enabledPlatforms"])
    private val _enabledPlatformsInChat = MutableStateFlow(enabledPlatformString.split(',').filter { it.isNotBlank() })
    val enabledPlatformsInChat: StateFlow<List<String>> = _enabledPlatformsInChat.asStateFlow()

    private val _currentProjectId = MutableStateFlow<String?>(null)
    val currentProjectId: StateFlow<String?> = _currentProjectId.asStateFlow()

    private val _projectName = MutableStateFlow<String?>(null)
    val projectName: StateFlow<String?> = _projectName.asStateFlow()

    private val _isBuildRunning = MutableStateFlow(false)
    val isBuildRunning = _isBuildRunning.asStateFlow()

    private val _buildProgress = MutableStateFlow(BuildProgressUiState())
    val buildProgress = _buildProgress.asStateFlow()

    private val _buildEvent = MutableSharedFlow<BuildEvent>()
    val buildEvent: SharedFlow<BuildEvent> = _buildEvent.asSharedFlow()

    private val currentTimeStamp: Long
        get() = System.currentTimeMillis() / 1000

    private val _chatRoom = MutableStateFlow(ChatRoomV2(id = -1, title = "", enabledPlatform = _enabledPlatformsInChat.value))
    val chatRoom = _chatRoom.asStateFlow()

    private val _isProjectNameDialogOpen = MutableStateFlow(false)
    val isProjectNameDialogOpen = _isProjectNameDialogOpen.asStateFlow()

    private val _isEditQuestionDialogOpen = MutableStateFlow(false)
    val isEditQuestionDialogOpen = _isEditQuestionDialogOpen.asStateFlow()

    private val _isSelectTextSheetOpen = MutableStateFlow(false)
    val isSelectTextSheetOpen = _isSelectTextSheetOpen.asStateFlow()

    private val _chatPlatformModels = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatPlatformModels = _chatPlatformModels.asStateFlow()

    // All platforms configured in app (including disabled)
    private val _platformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val platformsInApp = _platformsInApp.asStateFlow()

    // Enabled platforms list in app
    private val _enabledPlatformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val enabledPlatformsInApp = _enabledPlatformsInApp.asStateFlow()

    // User input used for TextField
    private val _question = MutableStateFlow("")
    val question: StateFlow<String> = _question.asStateFlow()

    // Selected files for current message
    private val _selectedFiles = MutableStateFlow(listOf<String>())
    val selectedFiles = _selectedFiles.asStateFlow()

    // Chat messages currently in the chat room
    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    // Each chat states for assistant chat messages
    // Index of the currently shown message's platform - default is 0 (first platform)
    private val _indexStates = MutableStateFlow(listOf<Int>())
    val indexStates = _indexStates.asStateFlow()

    // Loading states for each platform
    private val _loadingStates = MutableStateFlow(List<LoadingState>(_enabledPlatformsInChat.value.size) { LoadingState.Idle })
    val loadingStates = _loadingStates.asStateFlow()

    // Jobs for active AI response coroutines (used to cancel on stop)
    private val responseJobs = mutableListOf<Job>()
    private var activeTurnState: ActiveTurnState? = null
    private var pendingUnsavedDiagnosticChatId: Int? = null
    private val json = Json { explicitNulls = false; encodeDefaults = false }

    // Used for passing user question to Edit User Message Dialog
    private val _editedQuestion = MutableStateFlow(MessageV2(chatId = chatRoomId, content = "", platformType = null))
    val editedQuestion = _editedQuestion.asStateFlow()

    // Used for text data to show in SelectText Bottom Sheet
    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    // Crash auto-fix prompt shown in the chat list
    private val _crashPrompt = MutableStateFlow<CrashPrompt?>(null)
    val crashPrompt = _crashPrompt.asStateFlow()
    private var lastKnownCrashLogSize: Long = 0L

    // State for the message loading state (From the database)
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    init {
        Log.d("ViewModel", "$chatRoomId")
        Log.d("ViewModel", "${_enabledPlatformsInChat.value}")
        fetchChatRoom()
        viewModelScope.launch {
            refreshPlatformsInternal()
            fetchMessages()
            // Reconnect to an existing agent session if one is running for this chat
            reconnectToExistingSession()
        }
        observeStateChanges()
        viewModelScope.launch {
            if (chatRoomId != 0) {
                val project = projectRepository.fetchProjectByChatId(chatRoomId)
                _currentProjectId.update { project?.projectId }
                _projectName.update { project?.name }
                // Snapshot current crash log size so we only detect new crashes
                if (project != null) {
                    withContext(Dispatchers.IO) {
                        val crashFile = File(appContext.filesDir, "projects/${project.projectId}/logs/crash.log")
                        lastKnownCrashLogSize = if (crashFile.exists()) crashFile.length() else 0L
                    }
                }
            }
        }
    }

    fun addMessage(userMessage: MessageV2) {
        _groupedMessages.update {
            it.copy(
                userMessages = it.userMessages + listOf(userMessage),
                assistantMessages = it.assistantMessages + listOf(
                    _enabledPlatformsInChat.value.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }
        _indexStates.update { it + listOf(0) }
    }

    fun askQuestion() {
        Log.d("Question: ", _question.value)
        val userMessage = MessageV2(
            chatId = chatRoomId,
            content = _question.value,
            files = _selectedFiles.value,
            platformType = null,
            createdAt = currentTimeStamp
        )
        addMessage(userMessage)
        _question.update { "" }
        clearSelectedFiles()
        completeChat(startTurn(userMessage))
    }

    fun closeProjectNameDialog() = _isProjectNameDialogOpen.update { false }

    fun closeEditQuestionDialog() {
        _editedQuestion.update { MessageV2(chatId = chatRoomId, content = "", platformType = null) }
        _isEditQuestionDialogOpen.update { false }
    }

    fun closeSelectTextSheet() {
        _isSelectTextSheetOpen.update { false }
        _selectedText.update { "" }
    }

    fun openProjectNameDialog() = _isProjectNameDialogOpen.update { true }

    fun getSignedApkPath(): String? {
        val projectId = _currentProjectId.value ?: return null
        return projectInitializer.findSignedApkPath(projectId)
    }

    private fun buildProgressListener() = BuildProgressListener { update ->
        val progress = if (update.totalSteps == 0) 0f
        else update.completedSteps.toFloat() / update.totalSteps
        _buildProgress.update {
            it.copy(
                isVisible = true,
                progress = progress.coerceIn(0f, 1f),
                currentStage = update.stage,
            )
        }
    }

    fun runBuild() {
        val projectId = _currentProjectId.value
        Log.d("RunBuild", "runBuild called, projectId=$projectId")
        if (projectId == null) {
            Log.w("RunBuild", "projectId is null, aborting")
            return
        }
        _isBuildRunning.update { true }
        _buildProgress.update { BuildProgressUiState(isVisible = true) }
        viewModelScope.launch {
            try {
                Log.d("RunBuild", "Starting PLUGIN build for projectId=$projectId")
                val result = buildMutex.withBuildLock {
                    projectInitializer.buildProject(
                        projectId = projectId,
                        triggerSource = BuildTriggerSource.CHAT_BUTTON,
                        progressListener = buildProgressListener(),
                        buildMode = BuildMode.STANDALONE,
                    )
                }
                Log.d("RunBuild", "buildProject finished: status=${result.status}")
                if (result.status == BuildStatus.SUCCESS) {
                    val signedApkPath = result.artifacts
                        .firstOrNull { it.stage == BuildStage.SIGN }?.path
                    Log.d("RunBuild", "Build succeeded, signedApkPath=$signedApkPath")
                    if (signedApkPath != null) {
                        val packageName = projectInitializer.projectPackageName(projectId)
                        pluginManager.launchPlugin(signedApkPath, packageName, projectId, _projectName.value)
                    } else {
                        Log.w("RunBuild", "No SIGN artifact found in: ${result.artifacts}")
                    }
                } else {
                    val errorMsg = buildBuildErrorMessage(result)
                    Log.w("RunBuild", "Build failed, sending error to chat: $errorMsg")
                    sendBuildErrorToChat(errorMsg)
                }
            } finally {
                _isBuildRunning.update { false }
                _buildProgress.update { BuildProgressUiState() }
            }
        }
    }

    fun installBuild() {
        val projectId = _currentProjectId.value
        if (projectId == null) return
        _isBuildRunning.update { true }
        _buildProgress.update { BuildProgressUiState(isVisible = true) }
        viewModelScope.launch {
            try {
                val result = buildMutex.withBuildLock {
                    projectInitializer.buildProject(
                        projectId = projectId,
                        triggerSource = BuildTriggerSource.CHAT_BUTTON,
                        progressListener = buildProgressListener(),
                        buildMode = BuildMode.STANDALONE,
                    )
                }
                if (result.status == BuildStatus.SUCCESS) {
                    val signedApkPath = result.artifacts
                        .firstOrNull { it.stage == BuildStage.SIGN }?.path
                    if (signedApkPath != null) {
                        _buildEvent.emit(BuildEvent.InstallApk(signedApkPath))
                    }
                } else {
                    sendBuildErrorToChat(buildBuildErrorMessage(result))
                }
            } finally {
                _isBuildRunning.update { false }
                _buildProgress.update { BuildProgressUiState() }
            }
        }
    }

    private fun buildBuildErrorMessage(result: com.vibe.build.engine.model.BuildResult): String {
        val baseError = result.errorMessage ?: ""
        val errorLogs = result.logs
            .filter { it.level == BuildLogLevel.ERROR }
            .joinToString("\n") { it.message }
        return if (baseError.isNotBlank()) baseError else errorLogs
    }

    /**
     * Called from ChatScreen's ON_RESUME. Checks if crash.log has grown since the last check.
     * If so, shows a crash prompt in the chat list.
     */
    fun checkForNewCrashLog() {
        val projectId = _currentProjectId.value ?: return
        viewModelScope.launch {
            val crashInfo = withContext(Dispatchers.IO) {
                val crashFile = File(appContext.filesDir, "projects/$projectId/logs/crash.log")
                if (!crashFile.exists()) return@withContext null
                val currentSize = crashFile.length()
                if (currentSize <= lastKnownCrashLogSize) return@withContext null
                lastKnownCrashLogSize = currentSize
                // Read the last crash entry
                val lines = crashFile.readLines()
                val lastCrashIdx = lines.indexOfLast { it.startsWith("--- CRASH") }
                if (lastCrashIdx < 0) return@withContext null
                lines.drop(lastCrashIdx).take(15).joinToString("\n")
            } ?: return@launch
            _crashPrompt.update { CrashPrompt(crashSummary = crashInfo) }
        }
    }

    fun dismissCrashPrompt() {
        _crashPrompt.update { null }
    }

    fun autoFixCrash() {
        val prompt = _crashPrompt.value ?: return
        _crashPrompt.update { null }
        val content = "The app crashed at runtime. Please call fix_crash_guide to diagnose the crash " +
            "and get step-by-step fix instructions, then follow them to fix the code and rebuild.\n\nCrash summary:\n${prompt.crashSummary}"
        val userMessage = MessageV2(
            chatId = chatRoomId,
            content = content,
            platformType = null,
            createdAt = currentTimeStamp,
        )
        addMessage(userMessage)
        completeChat(startTurn(userMessage))
    }

    private fun sendBuildErrorToChat(errorMessage: String) {
        val content = "Build failed. Please fix the following errors:\n\n$errorMessage"
        val userMessage = MessageV2(
            chatId = chatRoomId,
            content = content,
            platformType = null,
            createdAt = currentTimeStamp
        )
        addMessage(userMessage)
        completeChat(startTurn(userMessage))
    }

    fun openEditQuestionDialog(question: MessageV2) {
        _editedQuestion.update { question }
        _isEditQuestionDialogOpen.update { true }
    }

    fun openSelectTextSheet(content: String) {
        _selectedText.update { content }
        _isSelectTextSheetOpen.update { true }
    }

    fun updateChatPlatformModels(models: Map<String, String>) {
        val sanitizedModels = models
            .filterKeys { it in _enabledPlatformsInChat.value }
            .mapValues { (_, model) -> model.trim() }

        _chatPlatformModels.update { it + sanitizedModels }

        if (_chatRoom.value.id > 0) {
            viewModelScope.launch {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
            }
        }
    }

    fun retryChat(platformIndex: Int) {
        if (platformIndex >= _enabledPlatformsInChat.value.size || platformIndex < 0) return
        val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == _enabledPlatformsInChat.value[platformIndex] }
        if (platform == null) {
            Log.w("ChatViewModel", "Platform at index $platformIndex is no longer available")
            return
        }
        val platformWithChatModel = resolvePlatformModel(platform)
        _loadingStates.update { it.toMutableList().apply { this[platformIndex] = LoadingState.Loading } }
        _groupedMessages.update {
            val updatedAssistantMessages = it.assistantMessages.toMutableList()
            updatedAssistantMessages[it.assistantMessages.lastIndex] = updatedAssistantMessages[it.assistantMessages.lastIndex].toMutableList().apply {
                this[platformIndex] = MessageV2(chatId = chatRoomId, content = "", platformType = platformWithChatModel.uid)
            }
            it.copy(assistantMessages = updatedAssistantMessages)
        }

        val turnState = startTurn(_groupedMessages.value.userMessages.last(), listOf(platformWithChatModel.uid))
        if (shouldUseAgentMode(platformWithChatModel)) {
            val effectiveChatId = _chatRoom.value.id.takeIf { it > 0 } ?: chatRoomId
            sessionManager.startSession(
                chatId = effectiveChatId,
                projectId = _currentProjectId.value,
                platform = platformWithChatModel,
                userMessages = _groupedMessages.value.userMessages,
                assistantMessages = _groupedMessages.value.assistantMessages,
                systemPrompt = platformWithChatModel.systemPrompt,
                diagnosticContext = turnState.context.diagnosticContext.copy(platformUid = platformWithChatModel.uid),
                chatRoom = _chatRoom.value,
                chatPlatformModels = _chatPlatformModels.value,
            )
            viewModelScope.launch {
                observeAgentSessionState(effectiveChatId)
            }
        } else {
            viewModelScope.launch {
                chatRepository.completeChat(
                    _groupedMessages.value.userMessages,
                    _groupedMessages.value.assistantMessages,
                    platformWithChatModel,
                    turnState.context.diagnosticContext.copy(platformUid = platformWithChatModel.uid),
                ).handleStates(
                    messageFlow = _groupedMessages,
                    platformIdx = platformIndex,
                    onLoadingComplete = {
                        _loadingStates.update { it.toMutableList().apply { this[platformIndex] = LoadingState.Idle } }
                    }
                )
            }
        }
    }

    fun updateProjectName(name: String) {
        val projectId = _currentProjectId.value ?: return
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return

        _projectName.update { normalizedName }
        _chatRoom.update { it.copy(title = normalizedName) }

        viewModelScope.launch {
            projectRepository.renameProject(projectId, normalizedName)
        }
    }

    fun updateChatPlatformIndex(assistantIndex: Int, platformIndex: Int) {
        // Change the message shown in the screen to another platform
        if (assistantIndex >= _indexStates.value.size || assistantIndex < 0) return
        if (platformIndex >= _enabledPlatformsInChat.value.size || platformIndex < 0) return

        _indexStates.update {
            val updatedIndex = it.toMutableList()
            updatedIndex[assistantIndex] = platformIndex
            updatedIndex
        }
    }

    fun updateQuestion(q: String) = _question.update { q }

    fun addSelectedFile(filePath: String) {
        _selectedFiles.update { currentFiles ->
            if (filePath !in currentFiles) {
                currentFiles + filePath
            } else {
                currentFiles
            }
        }
    }

    fun removeSelectedFile(filePath: String) {
        _selectedFiles.update { currentFiles ->
            currentFiles.filter { it != filePath }
        }
    }

    fun clearSelectedFiles() {
        _selectedFiles.update { emptyList() }
    }

    fun editQuestion(editedMessage: MessageV2) {
        val userMessages = _groupedMessages.value.userMessages
        val assistantMessages = _groupedMessages.value.assistantMessages

        // Find the index of the message being edited
        val messageIndex = userMessages.indexOfFirst { it.id == editedMessage.id }
        if (messageIndex == -1) return

        // Update the message content
        val updatedUserMessages = userMessages.toMutableList()
        updatedUserMessages[messageIndex] = editedMessage.copy(createdAt = currentTimeStamp)

        // Remove all messages after the edited question (both user and assistant messages)
        val remainingUserMessages = updatedUserMessages.take(messageIndex + 1)
        val remainingAssistantMessages = assistantMessages.take(messageIndex)

        // Update the grouped messages
        _groupedMessages.update {
            GroupedMessages(
                userMessages = remainingUserMessages,
                assistantMessages = remainingAssistantMessages
            )
        }

        // Add empty assistant message slots for the edited question
        _groupedMessages.update {
            it.copy(
                assistantMessages = it.assistantMessages + listOf(
                    _enabledPlatformsInChat.value.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }

        // Update index states to match the new message count - trim the end part
        val removedMessagesCount = userMessages.size - remainingUserMessages.size
        _indexStates.update {
            val currentStates = it.toMutableList()
            repeat(removedMessagesCount) { currentStates.removeLastOrNull() }
            currentStates
        }

        // Start new conversation from the edited question
        completeChat(startTurn(editedMessage))
    }

    suspend fun exportChat(): ChatExportBundle {
        // Build the chat history in Markdown format
        val chatHistoryMarkdown = buildString {
            appendLine("# Chat Export: \"${chatRoom.value.title}\"")
            appendLine()
            appendLine("**Exported on:** ${formatCurrentDateTime()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Chat History")
            appendLine()
            _groupedMessages.value.userMessages.forEachIndexed { i, message ->
                appendLine("**User:**")
                appendLine(message.content)
                appendLine()

                _groupedMessages.value.assistantMessages[i].forEach { message ->
                    val platformName = message.platformType
                        ?.let { _platformsInApp.value.getPlatformName(it) }
                        ?: "Unknown"
                    appendLine("**Assistant ($platformName):**")
                    appendLine(message.content)
                    appendLine()
                }
            }
        }

        val exportedAt = System.currentTimeMillis()
        val diagnostics = diagnosticLogger.readChatLog(_chatRoom.value.id)
        val appVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
        val manifest = buildJsonObject {
            put("chatId", _chatRoom.value.id)
            put("chatTitle", _chatRoom.value.title)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)
            put("diagnosticSchemaVersion", 1)
            put("diagnosticEventCount", diagnostics?.eventCount ?: 0L)
        }
        return ChatExportBundle(
            zipFileName = "chat_export_${sanitizeForFileName(chatRoom.value.title)}_$exportedAt.zip",
            chatMarkdown = chatHistoryMarkdown,
            diagnosticLogContent = diagnostics?.content,
            manifestJson = json.encodeToString(manifest),
        )
    }

    fun stopResponding() {
        val turnState = activeTurnState
        if (turnState != null) {
            viewModelScope.launch {
                diagnosticLogger.logChatTurnFinished(
                    context = turnState.context,
                    success = false,
                    finishReason = "cancelled",
                    outputChars = currentTurnOutputChars(turnState.context.turnIndex),
                    thinkingChars = currentTurnThinkingChars(turnState.context.turnIndex),
                )
            }
            activeTurnState = null
        }
        // Cancel agent session if running
        val effectiveChatId = _chatRoom.value.id.takeIf { it > 0 } ?: chatRoomId
        sessionManager.stopSession(effectiveChatId)
        responseJobs.forEach { it.cancel() }
        responseJobs.clear()
        _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Idle } }
    }

    private fun completeChat(turnState: ActiveTurnState) {
        activeTurnState = turnState
        // Update all the platform loading states to Loading
        _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Loading } }
        responseJobs.clear()

        // Send chat completion requests
        _enabledPlatformsInChat.value.forEachIndexed { idx, platformUid ->
            val platform = _enabledPlatformsInApp.value.firstOrNull { it.uid == platformUid }
            if (platform == null) {
                // Platform was disabled/removed since chat was created — reset loading state
                _loadingStates.update { it.toMutableList().apply { this[idx] = LoadingState.Idle } }
                return@forEachIndexed
            }
            val platformWithChatModel = resolvePlatformModel(platform)
            if (shouldUseAgentMode(platformWithChatModel)) {
                // Delegate to AgentSessionManager — survives ViewModel destruction
                val effectiveChatId = _chatRoom.value.id.takeIf { it > 0 } ?: chatRoomId
                sessionManager.startSession(
                    chatId = effectiveChatId,
                    projectId = _currentProjectId.value,
                    platform = platformWithChatModel,
                    userMessages = _groupedMessages.value.userMessages,
                    assistantMessages = _groupedMessages.value.assistantMessages,
                    systemPrompt = platformWithChatModel.systemPrompt,
                    diagnosticContext = turnState.context.diagnosticContext.copy(platformUid = platformWithChatModel.uid),
                    chatRoom = _chatRoom.value,
                    chatPlatformModels = _chatPlatformModels.value,
                )
                // Observe the session's message state (source of truth)
                val observeJob = viewModelScope.launch {
                    observeAgentSessionState(effectiveChatId)
                }
                responseJobs.add(observeJob)
            } else {
                val job = viewModelScope.launch {
                    chatRepository.completeChat(
                        _groupedMessages.value.userMessages,
                        _groupedMessages.value.assistantMessages,
                        platformWithChatModel,
                        turnState.context.diagnosticContext.copy(platformUid = platformWithChatModel.uid),
                    ).handleStates(
                        messageFlow = _groupedMessages,
                        platformIdx = idx,
                        onLoadingComplete = {
                            _loadingStates.update { it.toMutableList().apply { this[idx] = LoadingState.Idle } }
                        }
                    )
                }
                responseJobs.add(job)
            }
        }
    }

    private fun formatCurrentDateTime(): String {
        val currentDate = java.util.Date()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a", java.util.Locale.getDefault())
        return format.format(currentDate)
    }

    private suspend fun fetchMessages() {
        // If the room isn't new
        if (chatRoomId != 0) {
            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }
            if (_groupedMessages.value.assistantMessages.size != _indexStates.value.size) {
                _indexStates.update { List(_groupedMessages.value.assistantMessages.size) { 0 } }
            }
            _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Idle } }
            _isLoaded.update { true } // Finish fetching
            return
        }

        // When message id should sync after saving chats
        if (_chatRoom.value.id != 0) {
            _groupedMessages.update { fetchGroupedMessages(_chatRoom.value.id) }
            return
        }
    }

    private suspend fun fetchGroupedMessages(chatId: Int): GroupedMessages {
        val messages = chatRepository.fetchMessagesV2(chatId).sortedBy { it.createdAt }
        val platformOrderMap = _enabledPlatformsInChat.value.withIndex().associate { (idx, uuid) -> uuid to idx }

        val userMessages = mutableListOf<MessageV2>()
        val assistantMessages = mutableListOf<MutableList<MessageV2>>()

        messages.forEach { message ->
            if (message.platformType == null) {
                userMessages.add(message)
                assistantMessages.add(mutableListOf())
            } else {
                assistantMessages.last().add(message)
            }
        }

        val sortedAssistantMessages = assistantMessages.map { assistantMessage ->
            assistantMessage.sortedWith(
                compareBy(
                    { platformOrderMap[it.platformType] ?: Int.MAX_VALUE },
                    { it.platformType }
                )
            )
        }

        return GroupedMessages(userMessages, sortedAssistantMessages)
    }

    private fun fetchChatRoom() {
        viewModelScope.launch {
            _chatRoom.update {
                if (chatRoomId == 0) {
                    ChatRoomV2(id = 0, title = "Untitled Chat", enabledPlatform = _enabledPlatformsInChat.value)
                } else {
                    chatRepository.fetchChatListV2().first { it.id == chatRoomId }
                }
            }
            Log.d("ViewModel", "chatroom: ${chatRoom.value}")
        }
    }

    /**
     * Re-fetch platform configuration from settings and sync the chat's enabled platforms
     * with currently enabled ones. Call this when returning from settings screen.
     */
    fun refreshPlatforms() {
        viewModelScope.launch {
            refreshPlatformsInternal()
        }
    }

    private suspend fun refreshPlatformsInternal() {
        val allPlatforms = settingRepository.fetchPlatformV2s()
        _platformsInApp.update { allPlatforms }
        val enabledPlatforms = allPlatforms.filter { it.enabled }
        _enabledPlatformsInApp.update { enabledPlatforms }

        val currentEnabledUids = enabledPlatforms.map { it.uid }
        if (currentEnabledUids.isNotEmpty() && currentEnabledUids != _enabledPlatformsInChat.value) {
            _enabledPlatformsInChat.update { currentEnabledUids }
            _loadingStates.update { List(currentEnabledUids.size) { LoadingState.Idle } }
            _chatRoom.update { it.copy(enabledPlatform = currentEnabledUids) }
        }

        initializeChatPlatformModels(allPlatforms)
    }

    private suspend fun initializeChatPlatformModels(platforms: List<PlatformV2>) {
        val defaultModels = _enabledPlatformsInChat.value.associateWith { uid ->
            platforms.firstOrNull { it.uid == uid }?.model ?: ""
        }
        val persistedModels = if (chatRoomId != 0) {
            chatRepository.fetchChatPlatformModels(chatRoomId)
        } else {
            emptyMap()
        }

        val mergedModels = defaultModels.mapValues { (uid, defaultModel) ->
            persistedModels[uid]?.takeIf { it.isNotBlank() } ?: defaultModel
        }

        _chatPlatformModels.update { mergedModels }

        if (chatRoomId != 0 && mergedModels != persistedModels) {
            chatRepository.saveChatPlatformModels(chatRoomId, mergedModels)
        }
    }

    private fun observeStateChanges() {
        viewModelScope.launch {
            _loadingStates.collect { states ->
                if (_chatRoom.value.id != -1 &&
                    states.all { it == LoadingState.Idle } &&
                    (_groupedMessages.value.userMessages.isNotEmpty() && _groupedMessages.value.assistantMessages.isNotEmpty()) &&
                    (_groupedMessages.value.userMessages.size == _groupedMessages.value.assistantMessages.size)
                ) {
                    Log.d("ChatViewModel", "GroupMessage: ${_groupedMessages.value}")

                    // Save the chat & chat room
                    val previousChatId = _chatRoom.value.id
                    _chatRoom.update {
                        chatRepository.saveChat(
                            chatRoom = _chatRoom.value,
                            messages = ungroupedMessages(),
                            chatPlatformModels = _chatPlatformModels.value
                        )
                    }
                    val savedChatId = _chatRoom.value.id
                    if (previousChatId <= 0 && savedChatId > 0) {
                        pendingUnsavedDiagnosticChatId?.let { fromChatId ->
                            diagnosticLogger.migrateChatLogs(fromChatId, savedChatId)
                            activeTurnState = activeTurnState
                                ?.takeIf { it.diagnosticChatId == fromChatId }
                                ?.let { state ->
                                    state.copy(
                                        diagnosticChatId = savedChatId,
                                        context = state.context.copy(
                                            diagnosticContext = state.context.diagnosticContext.copy(chatId = savedChatId),
                                        ),
                                    )
                                } ?: activeTurnState
                        }
                        pendingUnsavedDiagnosticChatId = null
                    }

                    // Sync message ids
                    fetchMessages()
                    finalizeActiveTurnIfNeeded()
                }
            }
        }
    }

    private fun resolvePlatformModel(platform: PlatformV2): PlatformV2 {
        val chatModel = _chatPlatformModels.value[platform.uid]?.trim().orEmpty()
        if (chatModel.isBlank() || chatModel == platform.model) return platform

        return platform.copy(model = chatModel)
    }

    private fun shouldUseAgentMode(platform: PlatformV2): Boolean {
        return _enabledPlatformsInChat.value.size == 1 &&
            (platform.compatibleType == ClientType.OPENAI || platform.compatibleType == ClientType.ANTHROPIC || platform.compatibleType == ClientType.QWEN || platform.compatibleType == ClientType.KIMI) &&
            _currentProjectId.value != null
    }

    /**
     * Observe the session manager's message StateFlow, which is the source of truth
     * while a session is running. This works both for newly started sessions and
     * for reconnecting after ViewModel recreation.
     */
    private suspend fun observeAgentSessionState(sessionChatId: Int) {
        val stateFlow = sessionManager.getMessageState(sessionChatId) ?: return

        // Also watch session status to detect completion
        val statusJob = viewModelScope.launch {
            sessionManager.getSessionStatus(sessionChatId)?.collect { status ->
                if (status != AgentSessionStatus.RUNNING) {
                    // Session finished — set loading to idle so observeStateChanges() can trigger save
                    _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Idle } }
                    // Refresh project name in case the agent called rename_project
                    _projectName.update { projectRepository.fetchProjectByChatId(chatRoomId)?.name }
                }
            }
        }

        try {
            stateFlow.collect { sessionState ->
                // Mirror the session's message state into the ViewModel's UI state
                _groupedMessages.update {
                    GroupedMessages(
                        userMessages = sessionState.userMessages,
                        assistantMessages = sessionState.assistantMessages,
                    )
                }
                // Keep indexStates in sync
                val expectedSize = sessionState.userMessages.size
                if (_indexStates.value.size != expectedSize) {
                    _indexStates.update { List(expectedSize) { 0 } }
                }
            }
        } finally {
            statusJob.cancel()
        }
    }

    /**
     * If an agent session is already running for this chat (e.g. user navigated away and came back),
     * reconnect to it and resume UI updates from the session's current state.
     */
    private fun reconnectToExistingSession() {
        val effectiveChatId = _chatRoom.value.id.takeIf { it > 0 } ?: chatRoomId

        // Check if there's an active session OR a recently completed session with state
        val hasActiveSession = sessionManager.isSessionRunning(effectiveChatId)
        val hasMessageState = sessionManager.getMessageState(effectiveChatId) != null

        if (!hasActiveSession && !hasMessageState) return

        Log.d("ChatViewModel", "Reconnecting to agent session for chatId=$effectiveChatId (active=$hasActiveSession)")

        // Restore message state immediately from session manager
        val currentState = sessionManager.getMessageState(effectiveChatId)?.value
        if (currentState != null) {
            _groupedMessages.update {
                GroupedMessages(
                    userMessages = currentState.userMessages,
                    assistantMessages = currentState.assistantMessages,
                )
            }
            _indexStates.update { List(currentState.userMessages.size) { 0 } }
            _isLoaded.update { true }
        }

        if (hasActiveSession) {
            _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Loading } }
            val job = viewModelScope.launch {
                observeAgentSessionState(effectiveChatId)
            }
            responseJobs.add(job)
        } else {
            // Session completed while we were away — just set idle to trigger save
            _loadingStates.update { List(_enabledPlatformsInChat.value.size) { LoadingState.Idle } }
        }
    }

    private fun startTurn(message: MessageV2, platformUids: List<String> = _enabledPlatformsInChat.value): ActiveTurnState {
        val startedAt = System.currentTimeMillis()
        val diagnosticChatId = resolveDiagnosticChatId(startedAt)
        val turnIndex = _groupedMessages.value.userMessages.size
        val turnContext = ChatTurnDiagnosticContext(
            diagnosticContext = DiagnosticContext(
                chatId = diagnosticChatId,
                projectId = _currentProjectId.value,
                turnId = "$diagnosticChatId-$turnIndex-$startedAt",
            ),
            turnIndex = turnIndex,
            isAgentMode = platformUids.size == 1 && _enabledPlatformsInApp.value
                .firstOrNull { it.uid == platformUids.firstOrNull() }
                ?.let { shouldUseAgentMode(resolvePlatformModel(it)) } == true,
            platformUids = platformUids,
            userTextChars = message.content.length,
            attachmentCount = message.files.size,
            attachmentKinds = message.files.mapNotNull(::attachmentKind).distinct(),
            startedAt = startedAt,
        )
        val activeState = ActiveTurnState(
            diagnosticChatId = diagnosticChatId,
            context = turnContext,
        )
        activeTurnState = activeState
        viewModelScope.launch {
            diagnosticLogger.logChatTurnStarted(turnContext)
        }
        return activeState
    }

    private fun resolveDiagnosticChatId(startedAt: Long): Int {
        val persistedChatId = _chatRoom.value.id.takeIf { it > 0 }
        if (persistedChatId != null) {
            return persistedChatId
        }
        val temporaryChatId = -((startedAt % Int.MAX_VALUE).toInt().coerceAtLeast(1))
        pendingUnsavedDiagnosticChatId = temporaryChatId
        return temporaryChatId
    }

    private suspend fun finalizeActiveTurnIfNeeded() {
        val turnState = activeTurnState ?: return
        val turnIndex = turnState.context.turnIndex - 1
        val assistantTurn = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty()
        val outputChars = assistantTurn.sumOf { it.content.length }
        val thinkingChars = assistantTurn.sumOf { it.thoughts.length }
        val failed = assistantTurn.any { it.content.startsWith("Error:") || it.thoughts.contains("[Agent Error]") }
        diagnosticLogger.logChatTurnFinished(
            context = turnState.context,
            success = !failed,
            finishReason = if (failed) "provider_error" else "success",
            outputChars = outputChars,
            thinkingChars = thinkingChars,
        )
        activeTurnState = null
    }

    private fun currentTurnOutputChars(turnIndex: Int): Int {
        return _groupedMessages.value.assistantMessages.getOrNull(turnIndex - 1).orEmpty().sumOf { it.content.length }
    }

    private fun currentTurnThinkingChars(turnIndex: Int): Int {
        return _groupedMessages.value.assistantMessages.getOrNull(turnIndex - 1).orEmpty().sumOf { it.thoughts.length }
    }

    private fun attachmentKind(path: String): String? {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg" -> "image"
            "pdf", "txt", "doc", "docx", "xls", "xlsx" -> "document"
            else -> null
        }
    }

    private fun sanitizeForFileName(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifBlank { "chat" }
    }

    private fun ungroupedMessages(): List<MessageV2> {
        // Flatten the grouped messages into a single list
        val merged = _groupedMessages.value.userMessages + _groupedMessages.value.assistantMessages.flatten()
        return merged.filter { it.content.isNotBlank() }.sortedBy { it.createdAt }
    }
}
