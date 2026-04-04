package com.vibe.app.feature.agent.tool

import android.content.Context
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.service.BuildMutex
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.plugin.PluginManager
import com.vibe.build.engine.model.BuildResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val READ_PROJECT_FILE = "read_project_file"
private const val WRITE_PROJECT_FILE = "write_project_file"
private const val DELETE_PROJECT_FILE = "delete_project_file"
private const val LIST_PROJECT_FILES = "list_project_files"
private const val RUN_BUILD_PIPELINE = "run_build_pipeline"
private const val EDIT_PROJECT_FILE = "edit_project_file"
private const val RENAME_PROJECT = "rename_project"
private const val UPDATE_PROJECT_ICON = "update_project_icon"
private const val READ_RUNTIME_LOG = "read_runtime_log"
private const val FIX_CRASH_GUIDE = "fix_crash_guide"
private const val INSPECT_UI = "inspect_ui"
private const val INTERACT_UI = "interact_ui"
private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"

@Singleton
class ReadProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = READ_PROJECT_FILE,
        description = "Read one or more text files from the project workspace.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Relative file path. Use this OR paths, not both."))
                        },
                    )
                    put(
                        "paths",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("description", JsonPrimitive("Multiple relative file paths to read at once."))
                        },
                    )
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val singlePath = call.arguments.jsonObject["path"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
        val multiPaths = call.arguments.jsonObject["paths"]
            ?.let { (it as? JsonArray)?.mapNotNull { el -> el.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } } }

        val pathsToRead = multiPaths ?: listOfNotNull(singlePath)
        require(pathsToRead.isNotEmpty()) { "Provide either 'path' or 'paths'." }

        val workspace = projectManager.openWorkspace(context.projectId)

        if (pathsToRead.size == 1) {
            val path = pathsToRead.first()
            return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("content", JsonPrimitive(workspace.readTextFile(path)))
                },
            )
        }

        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
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

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = WRITE_PROJECT_FILE,
        description = "Create or overwrite a file in the project workspace with complete content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val content = call.arguments.requireString("content")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(path, content)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject { put("ok", JsonPrimitive(true)) },
        )
    }
}

@Singleton
class DeleteProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = DELETE_PROJECT_FILE,
        description = "Delete a file from the project workspace.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Relative file path to delete."))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"))))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.deleteFile(path)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject { put("ok", JsonPrimitive(true)) },
        )
    }
}

@Singleton
class ListProjectFilesTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = LIST_PROJECT_FILES,
        description = "List all files in the project workspace as relative paths.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val workspace = projectManager.openWorkspace(context.projectId)
        val files = workspace.listFiles()
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("files", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }
}

@Singleton
class RunBuildPipelineTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val buildMutex: BuildMutex,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = RUN_BUILD_PIPELINE,
        description = "Clean build cache and run the on-device Android build pipeline. Returns errors on failure.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "clean",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Clean build cache before building. Defaults to true."))
                        },
                    )
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val clean = call.arguments.jsonObject["clean"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val workspace = projectManager.openWorkspace(context.projectId)
        if (clean) {
            workspace.cleanBuildCache()
        }
        val result = buildMutex.withBuildLock {
            workspace.buildProject()
        }
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = result.toFilteredJson(),
            isError = result.errorMessage != null,
        )
    }
}

@Singleton
class EditProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = EDIT_PROJECT_FILE,
        description = "Apply search-and-replace edits to an existing project file. " +
            "More efficient than rewriting the whole file for small changes.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Relative file path to edit."))
                        },
                    )
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
                                            put(
                                                "old_string",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("string"))
                                                    put("description", JsonPrimitive("Exact text to find."))
                                                },
                                            )
                                            put(
                                                "new_string",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("string"))
                                                    put("description", JsonPrimitive("Replacement text."))
                                                },
                                            )
                                        },
                                    )
                                    put(
                                        "required",
                                        buildJsonArray {
                                            add(JsonPrimitive("old_string"))
                                            add(JsonPrimitive("new_string"))
                                        },
                                    )
                                },
                            )
                            put("description", JsonPrimitive("List of search-and-replace operations to apply in order."))
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("edits"))),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val editsArray = call.arguments.jsonObject["edits"]
            ?: throw IllegalArgumentException("Missing required field: edits")

        val workspace = projectManager.openWorkspace(context.projectId)
        var content = workspace.readTextFile(path)
        val results = mutableListOf<JsonObject>()

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

        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("path", JsonPrimitive(path))
                put("edits", buildJsonArray { results.forEach { add(it) } })
            },
        )
    }
}

@Singleton
class RenameProjectTool @Inject constructor(
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = RENAME_PROJECT,
        description = "Rename the current project.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("A short descriptive project name, max 30 characters."))
                            put("maxLength", JsonPrimitive(30))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val name = call.arguments.requireString("name").take(30)
        if (context.projectId.isBlank()) {
            return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject { put("error", JsonPrimitive("No project context available")) },
                isError = true,
            )
        }
        projectRepository.renameProject(context.projectId, name)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject { put("ok", JsonPrimitive(true)) },
        )
    }
}

@Singleton
class UpdateProjectIconTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = UPDATE_PROJECT_ICON,
        description = "Update the launcher icon with background and foreground vector drawable XML.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "backgroundXml",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Complete XML content for src/main/res/drawable/ic_launcher_background.xml"),
                            )
                        },
                    )
                    put(
                        "foregroundXml",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Complete XML content for src/main/res/drawable/ic_launcher_foreground.xml"),
                            )
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("backgroundXml"), JsonPrimitive("foregroundXml"))),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val backgroundXml = call.arguments.requireString("backgroundXml")
        val foregroundXml = call.arguments.requireString("foregroundXml")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, foregroundXml)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject { put("ok", JsonPrimitive(true)) },
        )
    }
}

@Singleton
class ReadRuntimeLogTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = READ_RUNTIME_LOG,
        description = "Read runtime app logs and/or crash stack traces.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "log_type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("app"))
                                    add(JsonPrimitive("crash"))
                                    add(JsonPrimitive("all"))
                                },
                            )
                            put(
                                "description",
                                JsonPrimitive(
                                    "Type of log to read: 'app' for runtime logs, " +
                                        "'crash' for crash stack traces, 'all' for everything. Defaults to 'all'.",
                                ),
                            )
                        },
                    )
                    put(
                        "tail",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("Number of most recent lines to return. Defaults to 200."),
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val logType = call.arguments.jsonObject["log_type"]?.jsonPrimitive?.content ?: "all"
        val tail = call.arguments.jsonObject["tail"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200
        val logDir = File(this.context.filesDir, "projects/${context.projectId}/logs")

        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                if (logType == "app" || logType == "all") {
                    put("app_log", JsonPrimitive(readTail(File(logDir, "app.log"), tail)))
                }
                if (logType == "crash" || logType == "all") {
                    put("crash_log", JsonPrimitive(readTail(File(logDir, "crash.log"), tail)))
                }
            },
        )
    }

    private fun readTail(file: File, maxLines: Int): String {
        if (!file.exists()) return ""
        val lines = file.readLines()
        return lines.takeLast(maxLines).joinToString("\n")
    }
}

@Singleton
class FixCrashGuideTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = FIX_CRASH_GUIDE,
        description = "Read crash log and auto-include source files referenced in the stack trace.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val logDir = File(appContext.filesDir, "projects/${context.projectId}/logs")
        val crashFile = File(logDir, "crash.log")

        if (!crashFile.exists()) {
            return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("crash_log", JsonPrimitive(""))
                    put("note", JsonPrimitive("No crash log found."))
                },
            )
        }

        val crashLog = crashFile.readText()
        val lastCrashIdx = crashLog.lines().indexOfLast { it.startsWith("--- CRASH") }
        val lastCrash = if (lastCrashIdx >= 0) {
            crashLog.lines().drop(lastCrashIdx).joinToString("\n")
        } else {
            crashLog
        }

        // Auto-resolve source files referenced in the stack trace
        val workspace = projectManager.openWorkspace(context.projectId)
        val projectFiles = workspace.listFiles()
        val referencedFiles = extractReferencedFiles(lastCrash, projectFiles)

        // Auto-read referenced source files (max 3 to avoid bloat)
        val fileContents = mutableListOf<JsonObject>()
        for (path in referencedFiles.take(3)) {
            val content = runCatching { workspace.readTextFile(path) }
            fileContents.add(
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

        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("crash_log", JsonPrimitive(lastCrash.take(2000)))
                if (fileContents.isNotEmpty()) {
                    put("source_files", buildJsonArray { fileContents.forEach { add(it) } })
                }
            },
        )
    }

    /**
     * Extracts project source files mentioned in a crash stack trace.
     * Matches lines like "at com.vibe.generated.p123.MainActivity(MainActivity.java:42)"
     * and resolves the .java filename against the project file listing.
     */
    private fun extractReferencedFiles(crashLog: String, projectFiles: List<String>): List<String> {
        val javaFileNames = Regex("""\((\S+\.java):\d+\)""")
            .findAll(crashLog)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Also match layout names from InflateException: "layout/activity_main"
        val layoutNames = Regex("""layout/(\w+)""")
            .findAll(crashLog)
            .map { "src/main/res/layout/${it.groupValues[1]}.xml" }
            .distinct()
            .toList()

        val resolved = mutableListOf<String>()

        // Resolve .java filenames to actual project paths
        for (fileName in javaFileNames) {
            val match = projectFiles.firstOrNull { it.endsWith(fileName) }
            if (match != null && match !in resolved) {
                resolved.add(match)
            }
        }

        // Add layout files if they exist in project
        for (layoutPath in layoutNames) {
            if (layoutPath in projectFiles && layoutPath !in resolved) {
                resolved.add(layoutPath)
            }
        }

        return resolved
    }
}

@Singleton
class InspectUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = INSPECT_UI,
        description = "Get the View tree of the currently running plugin UI.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )
        return try {
            val viewTree = inspector.dumpViewTree()
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = JsonPrimitive(viewTree),
            )
        } catch (e: Exception) {
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive(e.message ?: "inspection failed"))
                },
                isError = true,
            )
        }
    }
}

@Singleton
class InteractUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = INTERACT_UI,
        description = "Perform a UI action (click, input, scroll) on the running plugin and return the updated View tree.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action type: click, input, scroll"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("click"))
                        add(JsonPrimitive("input"))
                        add(JsonPrimitive("scroll"))
                    })
                })
                put("selector", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("""JSON selector: {"type":"id","value":"btn_submit"} or {"type":"text","value":"Submit"}"""))
                })
                put("value", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Text to input (for action=input) or scroll direction up/down/left/right (for action=scroll)"))
                })
                put("amount", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Scroll distance in pixels (for action=scroll, default 500)"))
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("action"))
                add(JsonPrimitive("selector"))
            })
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )

        val args = call.arguments.jsonObject
        val action = args["action"]?.jsonPrimitive?.content ?: ""
        val selector = args["selector"]?.jsonPrimitive?.content ?: ""
        val value = args["value"]?.jsonPrimitive?.content ?: ""
        val amount = args["amount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 500

        return try {
            val actionResult = when (action) {
                "click" -> inspector.performClick(selector)
                "input" -> inspector.inputText(selector, value)
                "scroll" -> inspector.scroll(selector, value, amount)
                else -> """{"success":false,"error":"unknown action: $action"}"""
            }

            // Auto-append updated View tree after action
            val viewTree = try { inspector.dumpViewTree() } catch (_: Exception) { null }

            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("result", JsonPrimitive(actionResult))
                    if (viewTree != null) {
                        put("view_tree", JsonPrimitive(viewTree))
                    }
                },
            )
        } catch (e: Exception) {
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive(e.message ?: "interaction failed"))
                },
                isError = true,
            )
        }
    }
}

@Singleton
class DefaultAgentToolRegistry @Inject constructor(
    readProjectFileTool: ReadProjectFileTool,
    writeProjectFileTool: WriteProjectFileTool,
    editProjectFileTool: EditProjectFileTool,
    deleteProjectFileTool: DeleteProjectFileTool,
    listProjectFilesTool: ListProjectFilesTool,
    runBuildPipelineTool: RunBuildPipelineTool,
    renameProjectTool: RenameProjectTool,
    updateProjectIconTool: UpdateProjectIconTool,
    readRuntimeLogTool: ReadRuntimeLogTool,
    fixCrashGuideTool: FixCrashGuideTool,
    inspectUiTool: InspectUiTool,
    interactUiTool: InteractUiTool,
) : AgentToolRegistry {

    private val tools = listOf(
        readProjectFileTool,
        writeProjectFileTool,
        editProjectFileTool,
        deleteProjectFileTool,
        listProjectFilesTool,
        runBuildPipelineTool,
        renameProjectTool,
        updateProjectIconTool,
        readRuntimeLogTool,
        fixCrashGuideTool,
        inspectUiTool,
        interactUiTool,
    )

    override fun listDefinitions(): List<AgentToolDefinition> = tools.map { it.definition }

    override fun findTool(name: String): AgentTool? = tools.firstOrNull { it.definition.name == name }
}

private fun JsonElement.requireString(key: String): String {
    val obj = this.jsonObject
    return obj[key]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string field: $key")
}

private fun BuildResult.toFilteredJson(): JsonObject {
    val isSuccess = errorMessage == null
    return buildJsonObject {
        put("status", JsonPrimitive(status.name))
        errorMessage?.let { put("errorMessage", JsonPrimitive(it)) }
        // Only include WARNING/ERROR logs, and skip entirely on success
        val filteredLogs = logs.filter {
            it.level == com.vibe.build.engine.model.BuildLogLevel.WARNING ||
                it.level == com.vibe.build.engine.model.BuildLogLevel.ERROR
        }
        if (!isSuccess && filteredLogs.isNotEmpty()) {
            put(
                "logs",
                buildJsonArray {
                    filteredLogs.forEach { log ->
                        add(
                            buildJsonObject {
                                put("stage", JsonPrimitive(log.stage.name))
                                put("level", JsonPrimitive(log.level.name))
                                put("message", JsonPrimitive(log.message))
                                log.sourcePath?.let { put("sourcePath", JsonPrimitive(it)) }
                                log.line?.let { put("line", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        }
    }
}
