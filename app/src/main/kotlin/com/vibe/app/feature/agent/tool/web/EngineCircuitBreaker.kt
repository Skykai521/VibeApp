package com.vibe.app.feature.agent.tool.web

/** In-memory cooldown for engines that returned a BLOCKED response. */
class EngineCircuitBreaker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val blockedUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun recordBlocked(engine: String) {
        blockedUntil[engine] = clock() + cooldownMs
    }

    @Synchronized
    fun isOpen(engine: String): Boolean = clock() < (blockedUntil[engine] ?: 0L)

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
