package com.vibe.app.feature.agent.service

import android.content.Context
import android.util.Log
import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.repository.ChatRepository
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentLoopCoordinator
import com.vibe.app.feature.agent.AgentLoopEvent
import com.vibe.app.feature.agent.AgentLoopRequest
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.diagnostic.DiagnosticContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the evolving message state for an active agent session.
 * This is the source of truth while the session is running.
 */
data class SessionMessageState(
    val userMessages: List<MessageV2>,
    val assistantMessages: List<List<MessageV2>>,
)

/**
 * Application-scoped manager that owns agent loop execution in a process-scoped CoroutineScope,
 * independent of any ViewModel. This allows agent work to survive ChatScreen navigation and
 * app backgrounding (when paired with the foreground service).
 *
 * The manager is the **source of truth** for message state while a session is running.
 * It processes agent events internally and exposes the evolving state as a StateFlow
 * that the ViewModel can observe. On session completion, it saves to Room directly.
 */
@Singleton
class AgentSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val agentLoopCoordinator: AgentLoopCoordinator,
    private val agentToolRegistry: AgentToolRegistry,
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val notificationHelper: AgentNotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<Map<Int, AgentSession>>(emptyMap())
    val sessions: StateFlow<Map<Int, AgentSession>> = _sessions.asStateFlow()

    /** Per-session message state (source of truth while session is active). */
    private val messageStates = ConcurrentHashMap<Int, MutableStateFlow<SessionMessageState>>()

    /** Per-session save context needed for persisting to Room on completion. */
    private val saveContexts = ConcurrentHashMap<Int, SessionSaveContext>()

    private val _hasActiveSessions = MutableStateFlow(false)
    val hasActiveSessions: StateFlow<Boolean> = _hasActiveSessions.asStateFlow()

    init {
        scope.launch {
            _sessions.collect { map ->
                _hasActiveSessions.value = map.isNotEmpty()
            }
        }
    }

    /**
     * Start an agent session for the given chat. If a session is already running for this chatId,
     * it will be stopped first.
     */
    fun startSession(
        chatId: Int,
        projectId: String?,
        platform: PlatformV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        systemPrompt: String?,
        diagnosticContext: DiagnosticContext?,
        chatRoom: ChatRoomV2,
        chatPlatformModels: Map<String, String>,
    ) {
        // Stop existing session for this chat if any
        stopSession(chatId)

        // Initialize message state — this is the source of truth while the session runs
        val stateFlow = MutableStateFlow(
            SessionMessageState(
                userMessages = userMessages,
                assistantMessages = assistantMessages,
            )
        )
        messageStates[chatId] = stateFlow

        // Store save context for persisting to Room on completion
        saveContexts[chatId] = SessionSaveContext(
            chatRoom = chatRoom,
            chatPlatformModels = chatPlatformModels,
        )

        val statusFlow = MutableStateFlow(AgentSessionStatus.RUNNING)

        val job = scope.launch {
            try {
                val request = AgentLoopRequest(
                    chatId = chatId,
                    projectId = projectId,
                    diagnosticContext = diagnosticContext,
                    platform = platform,
                    userMessages = userMessages,
                    assistantMessages = assistantMessages,
                    systemPrompt = systemPrompt,
                    tools = agentToolRegistry.listDefinitions(),
                )

                agentLoopCoordinator.run(request).collect { event ->
                    applyEvent(chatId, event)
                }

                statusFlow.value = AgentSessionStatus.COMPLETED
                saveToRoom(chatId)
                onSessionFinished(chatId, projectId, success = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                statusFlow.value = AgentSessionStatus.CANCELLED
                // Still save partial progress on cancellation
                try { saveToRoom(chatId) } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent session failed for chatId=$chatId", e)
                statusFlow.value = AgentSessionStatus.FAILED
                applyEvent(chatId, AgentLoopEvent.LoopFailed(message = e.message ?: "Unknown error"))
                saveToRoom(chatId)
                onSessionFinished(chatId, projectId, success = false)
            } finally {
                removeSession(chatId)
            }
        }

        val session = AgentSession(
            chatId = chatId,
            projectId = projectId,
            job = job,
            status = statusFlow,
        )
        _sessions.update { it + (chatId to session) }

        // Start foreground service to keep process alive
        AgentForegroundService.start(appContext)
    }

    fun stopSession(chatId: Int) {
        val session = _sessions.value[chatId] ?: return
        session.job.cancel()
        removeSession(chatId)
    }

    fun stopAllSessions() {
        _sessions.value.forEach { (_, session) -> session.job.cancel() }
        _sessions.update { emptyMap() }
        messageStates.clear()
        saveContexts.clear()
    }

    /**
     * Observe the evolving message state for a session.
     * Returns null if no session (active or recently completed) exists for this chatId.
     */
    fun getMessageState(chatId: Int): StateFlow<SessionMessageState>? {
        return messageStates[chatId]?.asStateFlow()
    }

    fun getSessionStatus(chatId: Int): StateFlow<AgentSessionStatus>? {
        return _sessions.value[chatId]?.status
    }

    fun isSessionRunning(chatId: Int): Boolean {
        return _sessions.value[chatId]?.status?.value == AgentSessionStatus.RUNNING
    }

    // -- Internal event processing --

    private fun applyEvent(chatId: Int, event: AgentLoopEvent) {
        val stateFlow = messageStates[chatId] ?: return
        when (event) {
            is AgentLoopEvent.ThinkingDelta -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(thoughts = msg.thoughts + event.delta)
                    }
                }
            }
            is AgentLoopEvent.OutputDelta -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(content = msg.content + event.delta)
                    }
                }
            }
            is AgentLoopEvent.ToolExecutionStarted -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(thoughts = msg.thoughts + "\n[Tool] ${event.call.name}\n")
                    }
                }
            }
            is AgentLoopEvent.ToolExecutionFinished -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(
                            thoughts = msg.thoughts +
                                "\n[Tool Result] ${event.result.toolName}: ${if (event.result.isError) "error" else "ok"}\n"
                        )
                    }
                }
            }
            is AgentLoopEvent.LoopCompleted -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(
                            content = msg.content.ifBlank { event.finalText.ifBlank { "Build completed." } },
                            createdAt = System.currentTimeMillis() / 1000,
                        )
                    }
                }
            }
            is AgentLoopEvent.LoopFailed -> {
                stateFlow.update { state ->
                    state.updateLastAssistant { msg ->
                        msg.copy(
                            content = if (msg.content.isBlank()) "Error: ${event.message}" else msg.content,
                            thoughts = msg.thoughts + "\n[Agent Error] ${event.message}",
                            createdAt = System.currentTimeMillis() / 1000,
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Helper to update the first (index 0) assistant message in the last turn.
     * Agent mode always has exactly 1 platform.
     */
    private inline fun SessionMessageState.updateLastAssistant(
        transform: (MessageV2) -> MessageV2,
    ): SessionMessageState {
        if (assistantMessages.isEmpty()) return this
        val lastTurn = assistantMessages.last().toMutableList()
        if (lastTurn.isEmpty()) return this
        lastTurn[0] = transform(lastTurn[0])
        val updated = assistantMessages.toMutableList()
        updated[updated.lastIndex] = lastTurn
        return copy(assistantMessages = updated)
    }

    // -- Persistence --

    private suspend fun saveToRoom(chatId: Int) {
        val state = messageStates[chatId]?.value ?: return
        val saveContext = saveContexts[chatId] ?: return

        val messages = (state.userMessages + state.assistantMessages.flatten())
            .filter { it.content.isNotBlank() }
            .sortedBy { it.createdAt }

        try {
            chatRepository.saveChat(
                chatRoom = saveContext.chatRoom,
                messages = messages,
                chatPlatformModels = saveContext.chatPlatformModels,
            )
            Log.d(TAG, "Saved session state to Room for chatId=$chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session to Room for chatId=$chatId", e)
        }
    }

    // -- Lifecycle --

    private fun removeSession(chatId: Int) {
        _sessions.update { it - chatId }
        // Keep messageStates around so reconnecting UI can still read final state.
        // They will be cleaned up on next startSession() for the same chatId.
        saveContexts.remove(chatId)
    }

    private suspend fun onSessionFinished(chatId: Int, projectId: String?, success: Boolean) {
        val projectName = projectId?.let {
            try {
                projectRepository.fetchProjectByChatId(chatId)?.name
            } catch (e: Exception) {
                null
            }
        }
        notificationHelper.showCompletionNotification(chatId, projectName, success)
    }

    private data class SessionSaveContext(
        val chatRoom: ChatRoomV2,
        val chatPlatformModels: Map<String, String>,
    )

    companion object {
        private const val TAG = "AgentSessionManager"
    }
}
