package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class GrepProjectFilesTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val engine: ProjectGrepEngine,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "grep_project_files",
        description = "Search project files by keyword or regex. Use this AFTER seeing " +
            "the project outline (from list_project_files or the auto-injected turn 2+ " +
            "listing) and BEFORE read_project_file. Returns matching lines with line " +
            "numbers in `file:line:text` form. Prefer literal search; set regex=true only " +
            "when you need a real pattern.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("pattern", stringProp("Search pattern. Literal by default."))
                    put("regex", booleanProp("Treat pattern as a Java regex. Default false."))
                    put("case_insensitive", booleanProp("Ignore case. Default false."))
                    put(
                        "path",
                        stringProp(
                            "Optional workspace-relative subdirectory to limit the " +
                                "search to (e.g. 'src/main/java' or 'res/values').",
                        ),
                    )
                    put(
                        "glob",
                        stringProp(
                            "Filename glob filter like '*.java', '*.xml', or " +
                                "'**/strings.xml'. Bare globs without '/' match any depth.",
                        ),
                    )
                    put(
                        "output_mode",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Output format: 'content' (default, file:line:text), " +
                                        "'files_with_matches' (just file paths), or 'count' " +
                                        "(per-file match counts).",
                                ),
                            )
                        },
                    )
                    put(
                        "context_lines",
                        intProp("Lines of context before and after each match (0-3). Default 0."),
                    )
                    put(
                        "max_results",
                        intProp(
                            "Max matches (content mode) or files (other modes) before " +
                                "truncation. Default 50, hard cap 200.",
                        ),
                    )
                },
            )
            put("required", requiredFields("pattern"))
        },
    )

    override suspend fun execute(
        call: AgentToolCall,
        context: AgentToolContext,
    ): AgentToolResult = withContext(Dispatchers.IO) {
        val pattern = call.arguments.requireString("pattern")
        val regex = call.arguments.optionalBoolean("regex", false)
        val caseInsensitive = call.arguments.optionalBoolean("case_insensitive", false)
        val path = call.arguments.optionalString("path").orEmpty()
        val glob = call.arguments.optionalString("glob").orEmpty()
        val outputModeRaw = call.arguments.optionalString("output_mode") ?: "content"
        val contextLines = call.arguments.optionalInt("context_lines", 0)
        val maxResults = call.arguments.optionalInt("max_results", 50)

        val outputMode = when (outputModeRaw.lowercase()) {
            "files_with_matches" -> GrepOutputMode.FILES_WITH_MATCHES
            "count" -> GrepOutputMode.COUNT
            else -> GrepOutputMode.CONTENT
        }

        val workspace = projectManager.openWorkspace(context.projectId)
        val projectRoot = workspace.rootDir
        val searchRoot = if (path.isBlank()) projectRoot else workspace.resolveFile(path)

        val args = GrepArgs(
            pattern = pattern,
            regex = regex,
            caseInsensitive = caseInsensitive,
            glob = glob,
            outputMode = outputMode,
            contextLines = contextLines,
            maxResults = maxResults,
        )

        val result = engine.search(searchRoot, projectRoot, args)

        call.result(
            buildJsonObject {
                when (result.mode) {
                    GrepOutputMode.CONTENT -> {
                        put("matches", JsonPrimitive(result.matchesText))
                        put("match_count", JsonPrimitive(result.matchCount))
                        put("file_count", JsonPrimitive(result.fileCount))
                    }
                    GrepOutputMode.FILES_WITH_MATCHES -> {
                        put(
                            "files",
                            buildJsonArray { result.files.forEach { add(JsonPrimitive(it)) } },
                        )
                        put("file_count", JsonPrimitive(result.fileCount))
                    }
                    GrepOutputMode.COUNT -> {
                        put("counts", JsonPrimitive(result.matchesText))
                        put("file_count", JsonPrimitive(result.fileCount))
                    }
                }
                put("truncated", JsonPrimitive(result.truncated))
            },
        )
    }
}
