package com.vibe.build.engine.pipeline

import android.content.Context
import android.util.Log
import com.vibe.build.engine.internal.BuildWorkspace
import com.vibe.build.engine.internal.BuildWorkspacePreparer
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStatus
import com.vibe.build.engine.model.CompileInput

class DefaultBuildPipeline(
    private val context: Context,
    private val resourceCompiler: ResourceCompiler,
    private val compiler: Compiler,
    private val dexConverter: DexConverter,
    private val apkBuilder: ApkBuilder,
    private val apkSigner: ApkSigner,
) : BuildPipeline {

    private val tag = "BuildEngine-Pipeline"

    override suspend fun run(input: CompileInput): BuildResult {
        Log.d(tag, "Pipeline start for ${input.projectId} at ${input.workingDirectory}")
        val workspace = BuildWorkspacePreparer.prepare(context, input)
        if (input.cleanOutput) {
            Log.d(tag, "Cleaning build directory: ${workspace.buildDir.absolutePath}")
            workspace.buildDir.deleteRecursively()
        }

        val stepRunners = listOf<suspend () -> BuildResult>(
            { resourceCompiler.compile(input) },
            { compiler.compile(input) },
            { dexConverter.convert(input) },
            { apkBuilder.build(input) },
            { apkSigner.sign(input) },
        )
        val stepResults = mutableListOf<BuildResult>()

        stepRunners.forEachIndexed { index, step ->
            val result = step()
            stepResults += result
            Log.d(
                tag,
                "Step ${index + 1}/${stepRunners.size} finished with status=${result.status}, error=${result.errorMessage}",
            )
            if (result.status == BuildStatus.FAILED) {
                Log.e(tag, "Pipeline failed: ${result.errorMessage}")
                return BuildResult(
                    status = BuildStatus.FAILED,
                    artifacts = stepResults.flatMap { it.artifacts },
                    logs = stepResults.flatMap { it.logs },
                    errorMessage = result.errorMessage,
                )
            }
        }

        Log.d(tag, "Pipeline succeeded")
        return BuildResult.success(
            artifacts = stepResults.flatMap { it.artifacts } + BuildWorkspace.pipelineArtifacts(workspace),
            logs = stepResults.flatMap { it.logs },
        )
    }
}
