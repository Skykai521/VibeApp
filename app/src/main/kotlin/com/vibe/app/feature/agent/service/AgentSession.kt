package com.vibe.app.feature.agent.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

enum class AgentSessionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AgentSession(
    val chatId: Int,
    val projectId: String?,
    val job: Job,
    val status: StateFlow<AgentSessionStatus>,
)
