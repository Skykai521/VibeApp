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
        description = "Diagnose a runtime crash from crash.log and return fix instructions.",
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
                    put("diagnosis", JsonPrimitive("No crash log found. The app may not have crashed, or logs were cleared."))
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

        // Match known crash patterns and generate fix instructions
        val diagnosis = diagnoseCrash(lastCrash, context)

        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("crash_log", JsonPrimitive(lastCrash.take(2000)))
                put("diagnosis", JsonPrimitive(diagnosis.summary))
                put("fix_instructions", JsonPrimitive(diagnosis.instructions))
                diagnosis.filesToRead?.let { files ->
                    put("files_to_read", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
                }
                diagnosis.filesToFix?.let { files ->
                    put("files_to_fix", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
                }
            },
        )
    }

    private data class CrashDiagnosis(
        val summary: String,
        val instructions: String,
        val filesToRead: List<String>? = null,
        val filesToFix: List<String>? = null,
    )

    private suspend fun diagnoseCrash(crashLog: String, context: AgentToolContext): CrashDiagnosis {
        // Pattern 1: Not a ShadowActivity subclass
        if (crashLog.contains("is not a ShadowActivity subclass")) {
            val activityName = Regex("""(\S+) is not a ShadowActivity subclass""")
                .find(crashLog)?.groupValues?.get(1)
            val simpleClassName = activityName?.substringAfterLast('.') ?: "MainActivity"
            val packagePath = "{{PACKAGE_PATH}}".let {
                // Try to find the actual file
                val workspace = projectManager.openWorkspace(context.projectId)
                val files = workspace.listFiles()
                files.firstOrNull { it.endsWith("$simpleClassName.java") }
            }

            return CrashDiagnosis(
                summary = "FATAL: $simpleClassName does not extend ShadowActivity. " +
                    "All Activity classes MUST extend com.tencent.shadow.core.runtime.ShadowActivity, " +
                    "NOT AppCompatActivity or Activity directly.",
                instructions = """
Step 1: Read the file containing $simpleClassName (use read_project_file).
Step 2: Change the class declaration from:
  public class $simpleClassName extends AppCompatActivity {
  (or extends Activity, or extends FragmentActivity)
TO:
  public class $simpleClassName extends ShadowActivity {
Step 3: Change the import from:
  import androidx.appcompat.app.AppCompatActivity;
TO:
  import com.tencent.shadow.core.runtime.ShadowActivity;
Step 4: Keep everything else unchanged. ShadowActivity extends AppCompatActivity internally, so all AppCompatActivity APIs (setSupportActionBar, getSupportFragmentManager, etc.) still work.
Step 5: Write the fixed file with write_project_file, then run_build_pipeline.
""".trimIndent(),
                filesToRead = listOfNotNull(packagePath),
                filesToFix = listOfNotNull(packagePath),
            )
        }

        // Pattern 2: InflateException with Toolbar
        if (crashLog.contains("InflateException") && crashLog.contains("Toolbar")) {
            return CrashDiagnosis(
                summary = "FATAL: Toolbar inflation failed. This is usually caused by a theme conflict " +
                    "in the plugin runtime. Using androidx.appcompat.widget.Toolbar directly causes crashes.",
                instructions = """
Step 1: Read the layout XML file mentioned in the crash log (use read_project_file).
Step 2: Replace any <androidx.appcompat.widget.Toolbar> or <android.widget.Toolbar> with:
  <com.google.android.material.appbar.MaterialToolbar
      android:id="@+id/toolbar"
      android:layout_width="match_parent"
      android:layout_height="?attr/actionBarSize"
      android:background="?attr/colorPrimary"
      app:titleTextColor="@android:color/white" />
Step 3: In the Activity's onCreate, after setContentView, add:
  MaterialToolbar toolbar = findViewById(R.id.toolbar);
  setSupportActionBar(toolbar);
Step 4: Make sure the Activity imports:
  import com.google.android.material.appbar.MaterialToolbar;
Step 5: Do NOT modify themes.xml. The theme must stay as Theme.MaterialComponents.DayNight.NoActionBar.
Step 6: Write the fixed files, then run_build_pipeline.
""".trimIndent(),
                filesToRead = listOf("src/main/res/layout/activity_main.xml"),
                filesToFix = listOf("src/main/res/layout/activity_main.xml"),
            )
        }

        // Pattern 3: InflateException with theme errors
        if (crashLog.contains("InflateException")) {
            val layoutMatch = Regex("""in (\S+):layout/(\S+):""").find(crashLog)
            val layoutName = layoutMatch?.groupValues?.get(2) ?: "activity_main"
            val classMatch = Regex("""Error inflating class (\S+)""").find(crashLog)
            val className = classMatch?.groupValues?.get(1)

            return CrashDiagnosis(
                summary = "FATAL: Layout inflation error${className?.let { " for class $it" } ?: ""}. " +
                    "A view in layout/$layoutName cannot be created, usually due to a missing style, " +
                    "wrong widget class, or theme misconfiguration.",
                instructions = """
Step 1: Read src/main/res/layout/$layoutName.xml to find the problematic view.
Step 2: Read src/main/res/values/themes.xml to verify the theme is correct.
Step 3: Check that:
  - themes.xml parent is Theme.MaterialComponents.DayNight.NoActionBar (do NOT change it)
  - All widgets use Material Components or standard Android widgets
  - No Material3 widgets are used (MaterialSwitch, etc.)
  - All custom attributes (app:...) are valid for the widget type
  - No android:cx, android:cy, or android:r attributes are used
${className?.let { "Step 4: If '$it' is not a valid widget, replace it with the correct Material Components equivalent." } ?: ""}
Step 5: Write the fixed files, then run_build_pipeline.
""".trimIndent(),
                filesToRead = listOf("src/main/res/layout/$layoutName.xml", "src/main/res/values/themes.xml"),
                filesToFix = listOf("src/main/res/layout/$layoutName.xml"),
            )
        }

        // Pattern 4: NullPointerException
        if (crashLog.contains("NullPointerException")) {
            val atLine = Regex("""at (\S+)\((\S+\.java):(\d+)\)""").find(crashLog)
            val fileName = atLine?.groupValues?.get(2)
            val lineNum = atLine?.groupValues?.get(3)

            return CrashDiagnosis(
                summary = "NullPointerException${fileName?.let { " in $it at line $lineNum" } ?: ""}. " +
                    "A variable is null when it should not be.",
                instructions = """
Step 1: Read the crash stack trace to find the exact file and line number.
Step 2: Read the source file with read_project_file.
Step 3: Check for common causes:
  - findViewById() returning null — is the ID correct? Is it in the right layout?
  - Calling methods on objects before they are initialized
  - Missing null checks on optional data
Step 4: Add null checks or fix the initialization order.
Step 5: Write the fixed file, then run_build_pipeline.
""".trimIndent(),
                filesToRead = fileName?.let {
                    val workspace = projectManager.openWorkspace(context.projectId)
                    val files = workspace.listFiles()
                    files.filter { f -> f.endsWith(it) }.take(1)
                },
                filesToFix = null,
            )
        }

        // Pattern 5: ClassNotFoundException or NoClassDefFoundError
        if (crashLog.contains("ClassNotFoundException") || crashLog.contains("NoClassDefFoundError")) {
            val classMatch = Regex("""(?:ClassNotFoundException|NoClassDefFoundError):\s*(\S+)""").find(crashLog)
            val missingClass = classMatch?.groupValues?.get(1)

            return CrashDiagnosis(
                summary = "Class not found: ${missingClass ?: "unknown"}. " +
                    "This class is not available in the bundled libraries.",
                instructions = """
Step 1: The class '${missingClass ?: "unknown"}' does not exist in the available libraries.
Step 2: Only these libraries are available — do NOT try to import anything else:
  - Standard Android SDK (android.widget.*, android.view.*, etc.)
  - AndroidX (androidx.appcompat, androidx.recyclerview, androidx.constraintlayout, etc.)
  - Material Components (com.google.android.material.*)
  - ShadowActivity (com.tencent.shadow.core.runtime.ShadowActivity)
Step 3: Replace the missing class with an equivalent from the available libraries.
Step 4: Write the fixed file, then run_build_pipeline.
""".trimIndent(),
                filesToRead = null,
                filesToFix = null,
            )
        }

        // Generic fallback
        return CrashDiagnosis(
            summary = "Runtime crash detected. Read the stack trace below and fix the root cause.",
            instructions = """
Step 1: Read the crash stack trace in the crash_log field.
Step 2: Identify the exception type and the file/line where it occurred.
Step 3: Use read_project_file to read the relevant source files.
Step 4: Fix the issue and rebuild with run_build_pipeline.
Important reminders:
  - All Activities MUST extend ShadowActivity (not AppCompatActivity)
  - Do NOT modify themes.xml
  - Do NOT use libraries that are not bundled
""".trimIndent(),
            filesToRead = null,
            filesToFix = null,
        )
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
