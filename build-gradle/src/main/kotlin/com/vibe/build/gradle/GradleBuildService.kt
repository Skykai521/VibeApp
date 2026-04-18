package com.vibe.build.gradle

import com.vibe.build.runtime.BuildRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 2 (GradleHost child
 * process + Tooling API client + JSON IPC).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §4.
 */
@Singleton
class GradleBuildService @Inject constructor(
    private val runtime: BuildRuntime,
) {
    fun version(): String = "phase-0 (uses runtime=${runtime.version()})"
}
