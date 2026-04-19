package com.vibe.app.feature.project

import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy object for v1 (LEGACY) vs. v2 (GRADLE_COMPOSE) projects.
 *
 * Today all capabilities are permissive — v1 projects still build,
 * run, and accept agent tools the same as v2. That keeps v1 as a
 * working fallback while the v2 Shadow path gets device-validated
 * (see `docs/superpowers/specs/2026-04-19-v2-phase-5b-exit-log.md`).
 *
 * The centralisation is the point: once the v2 checklist passes and
 * we flip v1 to read-only, every call site that should gate behaviour
 * already goes through this class — no scattered `if engine == ...`
 * checks to hunt down.
 */
@Singleton
class LegacyProjectGuard @Inject constructor() {

    fun isLegacy(project: Project): Boolean = project.engine == ProjectEngine.LEGACY

    /**
     * Whether the user can still build / run / edit this project
     * through the normal chat flow. Permissive today; will flip
     * to `!isLegacy(project)` once v2 is the only supported path.
     */
    fun canBuildAndRun(@Suppress("UNUSED_PARAMETER") project: Project): Boolean = true

    /**
     * Whether source-code export should be offered from the project
     * detail UI. We always offer it — for v1 projects because they're
     * on the migration path, for v2 projects because it's handy too.
     */
    fun canExportSource(@Suppress("UNUSED_PARAMETER") project: Project): Boolean = true
}
