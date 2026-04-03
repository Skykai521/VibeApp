package com.vibe.build.engine.model

enum class BuildStage {
    PREPARE,
    RESOURCE,
    COMPILE,
    DEX,
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

enum class BuildLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

enum class EngineBuildType {
    DEBUG,
    RELEASE,
}

enum class BuildMode {
    STANDALONE,
}

data class SigningConfig(
    val privateKeyPk8Path: String? = null,
    val certificatePemPath: String? = null,
)

data class CompileInput(
    val projectId: String,
    val projectName: String,
    val packageName: String,
    val workingDirectory: String,
    val sourceFiles: Map<String, String> = emptyMap(),
    val resourceFiles: Map<String, String> = emptyMap(),
    val assetFiles: Map<String, String> = emptyMap(),
    val manifestContents: String? = null,
    val manifestFilePath: String? = null,
    val classpathEntries: List<String> = emptyList(),
    val minSdk: Int = 29,
    val targetSdk: Int = 36,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val buildType: EngineBuildType = EngineBuildType.DEBUG,
    val cleanOutput: Boolean = true,
    val signingConfig: SigningConfig = SigningConfig(),
    val buildMode: BuildMode = BuildMode.STANDALONE,
)

data class BuildArtifact(
    val stage: BuildStage,
    val path: String,
    val description: String,
)

data class BuildLogEntry(
    val stage: BuildStage,
    val level: BuildLogLevel,
    val message: String,
    val sourcePath: String? = null,
    val line: Long? = null,
)

data class BuildResult(
    val status: BuildStatus,
    val artifacts: List<BuildArtifact>,
    val logs: List<BuildLogEntry>,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(
            artifacts: List<BuildArtifact>,
            logs: List<BuildLogEntry>,
        ): BuildResult = BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = artifacts,
            logs = logs,
        )

        fun failure(
            logs: List<BuildLogEntry>,
            errorMessage: String,
            artifacts: List<BuildArtifact> = emptyList(),
        ): BuildResult = BuildResult(
            status = BuildStatus.FAILED,
            artifacts = artifacts,
            logs = logs,
            errorMessage = errorMessage,
        )
    }
}
