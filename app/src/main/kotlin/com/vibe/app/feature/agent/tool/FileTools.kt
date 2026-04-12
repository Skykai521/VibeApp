package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Slice [content] by 1-based inclusive line numbers. `endLine = -1` means EOF.
 * Returns the sliced text and the (clamped) effective range, or empty + [IntRange.EMPTY]
 * when the requested range doesn't intersect the file.
 */
internal fun sliceByLines(content: String, startLine: Int, endLine: Int): Pair<String, IntRange> {
    val rawLines = content.lines()
    val lines = if (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
        rawLines.dropLast(1)
    } else {
        rawLines
    }
    val total = lines.size
    if (total == 0) return "" to IntRange.EMPTY

    val start = startLine.coerceAtLeast(1)
    val end = if (endLine == -1) total else endLine
    if (start > total || end < start) return "" to IntRange.EMPTY

    val actualEnd = end.coerceAtMost(total)
    val sliced = lines.subList(start - 1, actualEnd).joinToString("\n")
    return sliced to (start..actualEnd)
}

@Singleton
class ReadProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "read_project_file",
        description = "Read one or more text files from the project workspace. " +
            "For locating specific code, call `grep_project_files` first to find the " +
            "line range, then pass start_line/end_line here to avoid reading whole files.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringProp("Relative file path. Use this OR paths, not both."))
                    put(
                        "paths",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("description", JsonPrimitive("Multiple relative file paths to read at once."))
                        },
                    )
                    put(
                        "start_line",
                        intProp(
                            "Optional 1-based starting line (inclusive). Only applies to " +
                                "single-file `path` reads; ignored for batch `paths`.",
                        ),
                    )
                    put(
                        "end_line",
                        intProp(
                            "Optional 1-based ending line (inclusive). Use -1 or omit to " +
                                "read to EOF. Ignored for batch `paths`.",
                        ),
                    )
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val singlePath = call.arguments.optionalString("path")
        val multiPaths = call.arguments.jsonObject["paths"]
            ?.let { (it as? JsonArray)?.mapNotNull { el -> el.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } } }

        val pathsToRead = multiPaths ?: listOfNotNull(singlePath)
        require(pathsToRead.isNotEmpty()) { "Provide either 'path' or 'paths'." }

        val workspace = projectManager.openWorkspace(context.projectId)

        if (pathsToRead.size == 1) {
            val path = pathsToRead.first()
            val fullContent = workspace.readTextFile(path)
            val startLine = call.arguments.optionalInt("start_line", 1)
            val endLine = call.arguments.optionalInt("end_line", -1)
            val useRange = startLine != 1 || endLine != -1

            return call.result(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    if (useRange) {
                        val (sliced, range) = sliceByLines(fullContent, startLine, endLine)
                        put("content", JsonPrimitive(sliced))
                        val totalLines = fullContent.lines().let {
                            if (it.isNotEmpty() && it.last().isEmpty()) it.size - 1 else it.size
                        }
                        put("total_lines", JsonPrimitive(totalLines))
                        if (range != IntRange.EMPTY) {
                            put(
                                "range",
                                buildJsonObject {
                                    put("start", JsonPrimitive(range.first))
                                    put("end", JsonPrimitive(range.last))
                                },
                            )
                        }
                    } else {
                        put("content", JsonPrimitive(fullContent))
                    }
                },
            )
        }

        return call.result(
            buildJsonObject {
                put(
                    "files",
                    buildJsonArray {
                        pathsToRead.forEach { path ->
                            val content = runCatching { workspace.readTextFile(path) }
                            add(
                                buildJsonObject {
                                    put("path", JsonPrimitive(path))
                                    if (content.isSuccess) {
                                        put("content", JsonPrimitive(content.getOrThrow()))
                                    } else {
                                        put("error", JsonPrimitive(content.exceptionOrNull()?.message ?: "Read failed"))
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

@Singleton
class WriteProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "write_project_file",
        description = "Create or overwrite a file in the project workspace with complete content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringProp())
                    put("content", stringProp())
                },
            )
            put("required", requiredFields("path", "content"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val content = call.arguments.requireString("content")
        projectManager.openWorkspace(context.projectId).writeTextFile(path, content)
        return call.okResult()
    }
}

@Singleton
class EditProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "edit_project_file",
        description = "Apply search-and-replace edits to an existing project file. " +
            "More efficient than rewriting the whole file for small changes.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringProp("Relative file path to edit."))
                    put(
                        "edits",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("old_string", stringProp("Exact text to find."))
                                            put("new_string", stringProp("Replacement text."))
                                        },
                                    )
                                    put("required", requiredFields("old_string", "new_string"))
                                },
                            )
                            put("description", JsonPrimitive("List of search-and-replace operations to apply in order."))
                        },
                    )
                },
            )
            put("required", requiredFields("path", "edits"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val editsArray = call.arguments.jsonObject["edits"]
            ?: throw IllegalArgumentException("Missing required field: edits")

        val workspace = projectManager.openWorkspace(context.projectId)
        var content = workspace.readTextFile(path)
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (editElement in (editsArray as JsonArray)) {
            val edit = editElement.jsonObject
            val oldString = edit["old_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Each edit must have old_string")
            val newString = edit["new_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Each edit must have new_string")

            if (!content.contains(oldString)) {
                results.add(
                    buildJsonObject {
                        put("old_string", JsonPrimitive(oldString.take(80)))
                        put("matched", JsonPrimitive(false))
                    },
                )
                continue
            }
            content = content.replaceFirst(oldString, newString)
            results.add(
                buildJsonObject {
                    put("old_string", JsonPrimitive(oldString.take(80)))
                    put("matched", JsonPrimitive(true))
                },
            )
        }

        workspace.writeTextFile(path, content)

        return call.result(
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("edits", buildJsonArray { results.forEach { add(it) } })
            },
        )
    }
}

@Singleton
class DeleteProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "delete_project_file",
        description = "Delete a file from the project workspace.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringProp("Relative file path to delete."))
                },
            )
            put("required", requiredFields("path"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        projectManager.openWorkspace(context.projectId).deleteFile(path)
        return call.okResult()
    }
}

@Singleton
class ListProjectFilesTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val outlineBuilder: ProjectOutlineBuilder,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "list_project_files",
        description = "List project files with a compact symbol outline (Java classes/" +
            "methods, layout view ids, values keys, manifest activities). Use the outline " +
            "to pick keywords for `grep_project_files`.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "include_outline",
                        booleanProp(
                            "Include the symbol outline. Default true. Set false for a " +
                                "bare path list.",
                        ),
                    )
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val includeOutline = call.arguments.optionalBoolean("include_outline", true)
        val workspace = projectManager.openWorkspace(context.projectId)
        val files = workspace.listFiles()
        return call.result(
            buildJsonObject {
                put("files", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
                if (includeOutline) {
                    put("outline", JsonPrimitive(outlineBuilder.build(workspace.rootDir)))
                }
            },
        )
    }
}
