package com.vibe.build.runtime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 1 (native process
 * runtime: bootstrap, exec wrapper, NativeProcess API).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §3.
 */
@Singleton
class BuildRuntime @Inject constructor() {
    fun version(): String = "phase-0"
}
