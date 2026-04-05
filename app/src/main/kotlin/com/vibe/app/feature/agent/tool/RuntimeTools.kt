package com.vibe.app.feature.agent.tool

import android.content.Context
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class ReadRuntimeLogTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "read_runtime_log",
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
                    put("tail", intProp("Number of most recent lines to return. Defaults to 200."))
                },
            )
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val logType = call.arguments.optionalString("log_type") ?: "all"
        val tail = call.arguments.optionalInt("tail", default = 200)
        val logDir = File(appContext.filesDir, "projects/${context.projectId}/logs")

        return call.result(
            buildJsonObject {
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
        return file.readLines().takeLast(maxLines).joinToString("\n")
    }
}

@Singleton
class FixCrashGuideTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "fix_crash_guide",
        description = "Read crash log and auto-include source files referenced in the stack trace.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val crashFile = File(appContext.filesDir, "projects/${context.projectId}/logs/crash.log")

        if (!crashFile.exists()) {
            return call.result(
                buildJsonObject {
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

        val workspace = projectManager.openWorkspace(context.projectId)
        val projectFiles = workspace.listFiles()
        val referencedFiles = extractReferencedFiles(lastCrash, projectFiles)

        val fileContents = referencedFiles.take(3).map { path ->
            val content = runCatching { workspace.readTextFile(path) }
            buildJsonObject {
                put("path", JsonPrimitive(path))
                if (content.isSuccess) {
                    put("content", JsonPrimitive(content.getOrThrow()))
                } else {
                    put("error", JsonPrimitive(content.exceptionOrNull()?.message ?: "Read failed"))
                }
            }
        }

        return call.result(
            buildJsonObject {
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

        val layoutNames = Regex("""layout/(\w+)""")
            .findAll(crashLog)
            .map { "src/main/res/layout/${it.groupValues[1]}.xml" }
            .distinct()
            .toList()

        val resolved = mutableListOf<String>()

        for (fileName in javaFileNames) {
            val match = projectFiles.firstOrNull { it.endsWith(fileName) }
            if (match != null && match !in resolved) {
                resolved.add(match)
            }
        }

        for (layoutPath in layoutNames) {
            if (layoutPath in projectFiles && layoutPath !in resolved) {
                resolved.add(layoutPath)
            }
        }

        return resolved
    }
}
