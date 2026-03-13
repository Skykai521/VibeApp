package com.vibe.build.engine.pipeline

import android.content.Context
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

    override suspend fun run(input: CompileInput): BuildResult {
        val workspace = BuildWorkspacePreparer.prepare(context, input)
        if (input.cleanOutput) {
            workspace.buildDir.deleteRecursively()
        }

        val steps = listOf(
            resourceCompiler.compile(input),
            compiler.compile(input),
            dexConverter.convert(input),
            apkBuilder.build(input),
            apkSigner.sign(input),
        )

        val firstFailure = steps.firstOrNull { it.status == BuildStatus.FAILED }
        if (firstFailure != null) {
            return BuildResult(
                status = BuildStatus.FAILED,
                artifacts = steps.flatMap { it.artifacts },
                logs = steps.flatMap { it.logs },
                errorMessage = firstFailure.errorMessage,
            )
        }

        return BuildResult.success(
            artifacts = steps.flatMap { it.artifacts } + BuildWorkspace.pipelineArtifacts(workspace),
            logs = steps.flatMap { it.logs },
        )
    }
}
