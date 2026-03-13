package com.vibe.build.engine.internal

import android.content.Context
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import java.io.FileNotFoundException

abstract class BuildStep(
    private val context: Context,
    private val stage: BuildStage,
) {

    suspend fun run(input: com.vibe.build.engine.model.CompileInput): BuildResult {
        val logger = RecordingLogger(stage)
        return try {
            val workspace = BuildWorkspacePreparer.prepare(context, input)
            execute(input, workspace, logger)
        } catch (error: Throwable) {
            val message = error.message ?: when (error) {
                is FileNotFoundException -> "Required build input is missing"
                else -> "${stage.name.lowercase().replace('_', ' ')} failed"
            }
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
