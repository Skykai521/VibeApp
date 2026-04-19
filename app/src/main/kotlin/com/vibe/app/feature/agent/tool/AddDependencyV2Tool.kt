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
 * Agent tool: add a Maven dependency to the current v2 project.
 *
 * Atomic in the sense that BOTH `gradle/libs.versions.toml` AND
 * `app/build.gradle.kts` are updated together — if either edit can't be
 * applied (alias collision, missing section), the original contents of
 * BOTH files are restored.
 *
 * Conventions:
 *  - `alias`: lowercase kebab-case identifier (e.g. `material-icons-ext`).
 *    Used as both the version key and the library key in libs.versions.toml.
 *  - The `implementation(libs.{alias-with-dots})` line gets appended in
 *    `app/build.gradle.kts`'s `dependencies {}` block.
 */
@Singleton
class AddDependencyV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "add_dependency_v2",
        description = "Add a Maven dependency to the current v2 (GRADLE_COMPOSE) project. " +
            "Edits both `gradle/libs.versions.toml` and `app/build.gradle.kts` atomically. " +
            "Use sparingly — each new dep slows the next build's Maven resolution. After " +
            "adding, run `assemble_debug_v2` to verify the dependency resolves.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "alias",
                        stringProp(
                            "Catalog alias for this dependency. Lowercase kebab-case, e.g. " +
                                "'material-icons-ext'. Used as the libs catalog key.",
                        ),
                    )
                    put("group", stringProp("Maven group, e.g. 'androidx.compose.material'."))
                    put("name", stringProp("Maven artifact name, e.g. 'material-icons-extended'."))
                    put("version", stringProp("Maven version, e.g. '1.7.0'."))
                },
            )
            put("required", requiredFields("alias", "group", "name", "version"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val alias = call.arguments.requireString("alias")
        val group = call.arguments.requireString("group")
        val artifact = call.arguments.requireString("name")
        val version = call.arguments.requireString("version")

        if (!alias.matches(Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*"))) {
            return call.errorResult("alias must be lowercase kebab-case, e.g. 'material-icons-ext'")
        }
        val project = projectRepository.fetchProject(context.projectId)?.project
            ?: return call.errorResult("project not found: ${context.projectId}")
        if (project.engine != ProjectEngine.GRADLE_COMPOSE) {
            return call.errorResult(
                "current project is engine=${project.engine}; add_dependency_v2 only edits v2 projects.",
            )
        }
        val rootDir = File(project.workspacePath)
        val versionsToml = File(rootDir, "gradle/libs.versions.toml")
        val appBuild = File(rootDir, "app/build.gradle.kts")
        if (!versionsToml.isFile) return call.errorResult("missing $versionsToml")
        if (!appBuild.isFile) return call.errorResult("missing $appBuild")

        val originalVersions = versionsToml.readText()
        val originalAppBuild = appBuild.readText()

        val newVersions = try {
            insertVersionAndLibrary(originalVersions, alias, group, artifact, version)
        } catch (e: IllegalStateException) {
            return call.errorResult("libs.versions.toml: ${e.message}")
        }
        val libsAccessor = "libs." + alias.replace('-', '.')
        val newAppBuild = try {
            insertImplementation(originalAppBuild, libsAccessor)
        } catch (e: IllegalStateException) {
            return call.errorResult("app/build.gradle.kts: ${e.message}")
        }

        // Commit both. If anything goes wrong below, restore both files.
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
                put("status", JsonPrimitive("ADDED"))
                put("alias", JsonPrimitive(alias))
                put("coordinate", JsonPrimitive("$group:$artifact:$version"))
                put("hint", JsonPrimitive("Run assemble_debug_v2 to verify resolution."))
            },
        )
    }

    /**
     * Insert one [versions] entry and one [libraries] entry into a
     * libs.versions.toml string. Section order in the file is preserved.
     * Throws if the alias already exists in either section.
     */
    private fun insertVersionAndLibrary(
        toml: String,
        alias: String,
        group: String,
        artifact: String,
        version: String,
    ): String {
        if (toml.lineSequence().any { it.trim().startsWith("$alias = ") || it.trim().startsWith("$alias=") }) {
            error("alias '$alias' already exists in catalog")
        }
        val lines = toml.lines().toMutableList()
        // Append to [versions] block
        val versionsHdr = lines.indexOfFirst { it.trim() == "[versions]" }
        if (versionsHdr < 0) error("[versions] section not found")
        val nextSectionAfterVersions = lines.subList(versionsHdr + 1, lines.size)
            .indexOfFirst { it.trimStart().startsWith("[") }
            .let { if (it < 0) lines.size else versionsHdr + 1 + it }
        // Insert at the end of the [versions] block (before the next section header
        // or before any trailing blank lines).
        var insertVersion = nextSectionAfterVersions
        while (insertVersion > versionsHdr + 1 && lines[insertVersion - 1].isBlank()) insertVersion--
        lines.add(insertVersion, "$alias = \"$version\"")

        // Append to [libraries] block
        val librariesHdr = lines.indexOfFirst { it.trim() == "[libraries]" }
        if (librariesHdr < 0) error("[libraries] section not found")
        val nextSectionAfterLibraries = lines.subList(librariesHdr + 1, lines.size)
            .indexOfFirst { it.trimStart().startsWith("[") }
            .let { if (it < 0) lines.size else librariesHdr + 1 + it }
        var insertLib = nextSectionAfterLibraries
        while (insertLib > librariesHdr + 1 && lines[insertLib - 1].isBlank()) insertLib--
        val libLine = """$alias = { group = "$group", name = "$artifact", version.ref = "$alias" }"""
        lines.add(insertLib, libLine)

        return lines.joinToString("\n")
    }

    /**
     * Append an `implementation({accessor})` line at the end of the
     * top-level `dependencies {` block. Throws if no such block exists.
     */
    private fun insertImplementation(buildScript: String, libsAccessor: String): String {
        val target = "implementation($libsAccessor)"
        if (buildScript.contains(target)) {
            error("dependency line already present: $target")
        }
        // Find `dependencies {` and the matching `}`. Naive brace count from
        // the dependencies opening brace. Sufficient for the canonical
        // KotlinComposeApp template — caller controls the file shape.
        val open = buildScript.indexOf("dependencies {")
        if (open < 0) error("dependencies { ... } block not found")
        var depth = 0
        var i = buildScript.indexOf('{', open)
        while (i >= 0 && i < buildScript.length) {
            when (buildScript[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (depth != 0 || i < 0) error("could not find closing brace of dependencies {}")
        val close = i
        val before = buildScript.substring(0, close)
        val after = buildScript.substring(close)
        val trimmedBefore = before.trimEnd()
        return trimmedBefore + "\n    $target\n" + after
    }
}
