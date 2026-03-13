package com.vibe.build.engine.pipeline

import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import com.vibe.build.engine.model.CompileInput
import kotlinx.coroutines.delay

open class StubCompiler : Compiler {
    override suspend fun compile(input: CompileInput): BuildResult {
        delay(150)
        return BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.COMPILE,
                    path = "intermediates/${input.projectId}/classes",
                    description = "ECJ compiled .class outputs",
                ),
            ),
            logs = listOf(BuildLogEntry(BuildStage.COMPILE, "ECJ compilation completed")),
        )
    }
}

open class StubDexConverter : DexConverter {
    override suspend fun convert(input: CompileInput): BuildResult {
        delay(100)
        return BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.DEX,
                    path = "intermediates/${input.projectId}/classes.dex",
                    description = "D8 converted dex payload",
                ),
            ),
            logs = listOf(BuildLogEntry(BuildStage.DEX, "D8 dex conversion completed")),
        )
    }
}

open class StubResourceCompiler : ResourceCompiler {
    override suspend fun compile(input: CompileInput): BuildResult {
        delay(120)
        return BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.RESOURCE,
                    path = "intermediates/${input.projectId}/resources.apk",
                    description = "AAPT2 packaged resources",
                ),
            ),
            logs = listOf(BuildLogEntry(BuildStage.RESOURCE, "AAPT2 resource compilation completed")),
        )
    }
}

open class StubApkBuilder : ApkBuilder {
    override suspend fun build(input: CompileInput): BuildResult {
        delay(100)
        return BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.PACKAGE,
                    path = "outputs/${input.projectId}/unsigned.apk",
                    description = "Unsigned APK bundle",
                ),
            ),
            logs = listOf(BuildLogEntry(BuildStage.PACKAGE, "APK packaging completed")),
        )
    }
}

open class StubApkSigner : ApkSigner {
    override suspend fun sign(input: CompileInput): BuildResult {
        delay(80)
        return BuildResult(
            status = BuildStatus.SUCCESS,
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.SIGN,
                    path = "outputs/${input.projectId}/signed.apk",
                    description = "Signed APK ready for install",
                ),
            ),
            logs = listOf(BuildLogEntry(BuildStage.SIGN, "APK signing completed")),
        )
    }
}

class StubBuildPipeline(
    private val compiler: Compiler,
    private val dexConverter: DexConverter,
    private val resourceCompiler: ResourceCompiler,
    private val apkBuilder: ApkBuilder,
    private val apkSigner: ApkSigner,
) : BuildPipeline {
    override suspend fun run(input: CompileInput): BuildResult {
        val steps = listOf(
            compiler.compile(input),
            dexConverter.convert(input),
            resourceCompiler.compile(input),
            apkBuilder.build(input),
            apkSigner.sign(input),
        )
        val finalStatus = if (steps.all { it.status == BuildStatus.SUCCESS }) {
            BuildStatus.SUCCESS
        } else {
            BuildStatus.FAILED
        }
        return BuildResult(
            status = finalStatus,
            artifacts = steps.flatMap { it.artifacts },
            logs = steps.flatMap { it.logs },
            errorMessage = steps.firstOrNull { it.status == BuildStatus.FAILED }?.errorMessage,
        )
    }
}
