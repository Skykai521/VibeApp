package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Agent tool: remove a Maven dependency previously added via
 * [AddDependencyV2Tool]. Deletes the matching `[versions]`,
 * `[libraries]`, and `implementation(libs.x)` lines.
 *
 * Atomic via in-memory backup like its add counterpart.
 */
@Singleton
class RemoveDependencyV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "remove_dependency_v2",
        description = "Remove a Maven dependency from the current v2 project that was " +
            "previously added via add_dependency_v2 (or already in the catalog with a " +
            "matching alias). Edits both libs.versions.toml and app/build.gradle.kts " +
            "atomically.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "alias",
                        stringProp(
                            "Catalog alias for the dependency to remove (the same kebab-case " +
                                "key used when calling add_dependency_v2).",
                        ),
                    )
                },
            )
            put("required", requiredFields("alias"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val alias = call.arguments.requireString("alias")
        val project = projectRepository.fetchProject(context.projectId)?.project
            ?: return call.errorResult("project not found: ${context.projectId}")
        if (project.engine != ProjectEngine.GRADLE_COMPOSE) {
            return call.errorResult(
                "current project is engine=${project.engine}; remove_dependency_v2 only edits v2 projects.",
            )
        }
        val rootDir = File(project.workspacePath)
        val versionsToml = File(rootDir, "gradle/libs.versions.toml")
        val appBuild = File(rootDir, "app/build.gradle.kts")
        if (!versionsToml.isFile) return call.errorResult("missing $versionsToml")
        if (!appBuild.isFile) return call.errorResult("missing $appBuild")

        val originalVersions = versionsToml.readText()
        val originalAppBuild = appBuild.readText()

        val (newVersions, removedToml) = removeFromToml(originalVersions, alias)
        if (!removedToml) {
            return call.errorResult("alias '$alias' not found in libs.versions.toml")
        }
        val libsAccessor = "libs." + alias.replace('-', '.')
        val (newAppBuild, removedImpl) = removeImplementationLine(originalAppBuild, libsAccessor)
        if (!removedImpl) {
            return call.errorResult(
                "no `implementation($libsAccessor)` line found in app/build.gradle.kts",
            )
        }

        try {
            versionsToml.writeText(newVersions)
            appBuild.writeText(newAppBuild)
        } catch (t: Throwable) {
            versionsToml.writeText(originalVersions)
            appBuild.writeText(originalAppBuild)
            return call.errorResult("write failed (rolled back): ${t.message}")
        }

        return call.result(
            buildJsonObject {
                put("status", JsonPrimitive("REMOVED"))
                put("alias", JsonPrimitive(alias))
            },
        )
    }

    private fun removeFromToml(toml: String, alias: String): Pair<String, Boolean> {
        var hit = false
        val out = toml.lines().filter { line ->
            val trimmed = line.trim()
            val isVersionLine = trimmed.startsWith("$alias = ") || trimmed.startsWith("$alias=")
            val isLibraryLine = trimmed.startsWith("$alias = {")
            if (isVersionLine || isLibraryLine) {
                hit = true
                false
            } else {
                true
            }
        }
        return out.joinToString("\n") to hit
    }

    private fun removeImplementationLine(buildScript: String, accessor: String): Pair<String, Boolean> {
        val target = "implementation($accessor)"
        if (!buildScript.contains(target)) return buildScript to false
        // Drop the line that contains the accessor; preserve other lines unchanged.
        val out = buildScript.lines().filter { !it.contains(target) }
        return out.joinToString("\n") to true
    }
}
