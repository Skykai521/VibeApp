package com.vibe.app.feature.agent.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildMutex @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withBuildLock(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
