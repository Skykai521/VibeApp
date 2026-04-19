package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Agent tool: create a fresh v2 Kotlin + Compose project tied to the
 * current chat. The new project's files land under
 * `filesDir/projects/{newId}/` and a Project row with
 * `engine = GRADLE_COMPOSE` is inserted.
 *
 * Returns the generated `project_id`, `workspace_path`, plus a hint
 * telling the model to follow up with `assemble_debug_v2` to actually
 * build the APK.
 */
@Singleton
class CreateComposeProjectTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "create_compose_project",
        description = "Create a new Kotlin + Jetpack Compose Android project from the bundled " +
            "KotlinComposeApp template (v2 on-device Gradle pipeline). The project is tied to " +
            "the current chat. After creation, the next step is usually `assemble_debug_v2` to " +
            "build it. Use this for any 'make a Compose app' / 'create a counter' / similar " +
            "request — DO NOT use the legacy `init_project` for v2 builds.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "project_name",
                        stringProp("Display name shown in launcher (e.g. 'Counter')."),
                    )
                    put(
                        "package_name",
                        stringProp(
                            "Reverse-DNS Android package name; lowercase letters, digits, " +
                                "underscores. Must contain at least one dot. Example: " +
                                "'com.vibe.counter'.",
                        ),
                    )
                },
            )
            put("required", requiredFields("project_name", "package_name"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val projectName = call.arguments.requireString("project_name")
        val packageName = call.arguments.requireString("package_name")
        val created = try {
            projectManager.createV2GradleComposeProject(
                chatId = context.chatId,
                projectName = projectName,
                packageName = packageName,
            )
        } catch (e: IllegalArgumentException) {
            return call.errorResult(e.message ?: "invalid input")
        } catch (t: Throwable) {
            return call.errorResult("create_compose_project failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return call.result(
            buildJsonObject {
                put("project_id", JsonPrimitive(created.projectId))
                put("project_name", JsonPrimitive(created.name))
                put("package_name", JsonPrimitive(packageName))
                put("workspace_path", JsonPrimitive(created.workspacePath))
                put(
                    "hint",
                    JsonPrimitive(
                        "Project created. Files are under workspace_path. " +
                            "Edit MainActivity.kt as needed (use write_project_file / " +
                            "edit_project_file with paths relative to workspace_path), " +
                            "then call assemble_debug_v2 to build.",
                    ),
                )
            },
        )
    }
}
