package com.vibe.build.engine.model

enum class BuildStage {
    PRECHECK,
    COMPILE,
    DEX,
    RESOURCE,
    PACKAGE,
    SIGN,
    INSTALL,
}

enum class BuildStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
}

data class CompileInput(
    val projectId: String,
    val projectName: String,
    val packageName: String,
    val sourceFiles: Map<String, String>,
    val resourceFiles: Map<String, String>,
)

data class BuildArtifact(
    val stage: BuildStage,
    val path: String,
    val description: String,
)

data class BuildLogEntry(
    val stage: BuildStage,
    val message: String,
)

data class BuildResult(
    val status: BuildStatus,
    val artifacts: List<BuildArtifact>,
    val logs: List<BuildLogEntry>,
    val errorMessage: String? = null,
)
