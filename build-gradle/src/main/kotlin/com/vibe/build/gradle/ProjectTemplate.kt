package com.vibe.build.gradle

import java.io.File

/**
 * Where to find the template tree for a probe/staged project.
 *
 * Phase 2d only uses [FromDirectory] (instrumented test extracts assets
 * to a temp dir first). Phase 3's real project generator will likely
 * swap in a type that renders from in-memory templates — adding a new
 * subtype is additive.
 */
sealed class ProjectTemplate {
    data class FromDirectory(val root: File) : ProjectTemplate()
}
