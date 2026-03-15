package com.vibe.build.engine.pipeline

import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.CompileInput

enum class BuildProgressState {
    STARTED,
    COMPLETED,
}

data class BuildProgressUpdate(
    val stage: BuildStage,
    val completedSteps: Int,
    val totalSteps: Int,
    val state: BuildProgressState,
)

fun interface BuildProgressListener {
    fun onProgress(update: BuildProgressUpdate)
}

interface Compiler {
    suspend fun compile(input: CompileInput): BuildResult
}

interface DexConverter {
    suspend fun convert(input: CompileInput): BuildResult
}

interface ResourceCompiler {
    suspend fun compile(input: CompileInput): BuildResult
}

interface ApkBuilder {
    suspend fun build(input: CompileInput): BuildResult
}

interface ApkSigner {
    suspend fun sign(input: CompileInput): BuildResult
}

interface BuildPipeline {
    suspend fun run(
        input: CompileInput,
        progressListener: BuildProgressListener? = null,
    ): BuildResult
}
