package com.vibe.build.engine.internal

import android.content.Context
import android.util.Log
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import java.io.FileNotFoundException

abstract class BuildStep(
    private val context: Context,
    private val stage: BuildStage,
) {

    private val tag = "BuildEngine-${stage.name}"

    suspend fun run(input: com.vibe.build.engine.model.CompileInput): BuildResult {
        val logger = RecordingLogger(stage)
        return try {
            Log.d(
                tag,
                "Starting stage with projectId=${input.projectId}, workingDirectory=${input.workingDirectory}",
            )
            val workspace = BuildWorkspacePreparer.prepare(context, input)
            val result = execute(input, workspace, logger)
            Log.d(
                tag,
                "Stage finished with status=${result.status}, artifacts=${result.artifacts.size}, logs=${result.logs.size}",
            )
            result
        } catch (error: Throwable) {
            val message = error.message ?: when (error) {
                is FileNotFoundException -> "Required build input is missing"
                else -> "${stage.name.lowercase().replace('_', ' ')} failed"
            }
            Log.e(tag, "Stage failed: $message", error)
            logger.error(message)
            BuildResult.failure(
                logs = logger.entries,
                errorMessage = message,
            )
        }
    }

    protected abstract suspend fun execute(
        input: com.vibe.build.engine.model.CompileInput,
        workspace: BuildWorkspace,
        logger: RecordingLogger,
    ): BuildResult
}
