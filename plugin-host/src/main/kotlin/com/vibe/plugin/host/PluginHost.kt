package com.vibe.plugin.host

import com.vibe.build.runtime.BuildRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 5 (Shadow host
 * integration, proxy activities, process slot manager, PluginRunner).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §7.
 */
@Singleton
class PluginHost @Inject constructor(
    private val runtime: BuildRuntime,
) {
    fun version(): String = "phase-0 (uses runtime=${runtime.version()})"
}
