package com.vibe.build.engine.dex

import android.content.Context
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.vibe.build.engine.internal.BuildStep
import com.vibe.build.engine.internal.BuildWorkspace
import com.vibe.build.engine.internal.RecordingLogger
import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.CompileInput
import com.vibe.build.engine.model.EngineBuildType
import java.io.File

class D8DexConverter(
    context: Context,
) : BuildStep(context, BuildStage.DEX), com.vibe.build.engine.pipeline.DexConverter {

    override suspend fun convert(input: CompileInput): BuildResult = run(input)

    override suspend fun execute(
        input: CompileInput,
        workspace: BuildWorkspace,
        logger: RecordingLogger,
    ): BuildResult {
        val classFiles = workspace.allClassFiles()
        require(classFiles.isNotEmpty()) { "No compiled .class files found under ${workspace.classesDir.absolutePath}" }
        workspace.binDir.mkdirs()

        val diagnosticsHandler = object : DiagnosticsHandler {
            override fun error(diagnostic: Diagnostic) {
                logger.error(diagnostic.diagnosticMessage)
            }

            override fun warning(diagnostic: Diagnostic) {
                logger.warning(diagnostic.diagnosticMessage)
            }

            override fun info(diagnostic: Diagnostic) {
                logger.info(diagnostic.diagnosticMessage)
            }
        }

        val programFiles = classFiles.map { it.toPath() }.toMutableList()
        if (workspace.androidxClassesJar != null) {
            programFiles.add(workspace.androidxClassesJar.toPath())
        }

        val command = D8Command.builder(diagnosticsHandler)
            .addProgramFiles(programFiles)
            .addClasspathFiles(
                input.classpathEntries.map(::File)
                    .filter { it.exists() }
                    .map { it.toPath() },
            )
            .addLibraryFiles(
                listOf(workspace.bootstrapJar.toPath(), workspace.lambdaStubsJar.toPath()),
            )
            .setMinApiLevel(input.minSdk)
            .setMode(
                if (input.buildType == EngineBuildType.RELEASE) {
                    CompilationMode.RELEASE
                } else {
                    CompilationMode.DEBUG
                },
            )
            .setOutput(workspace.binDir.toPath(), OutputMode.DexIndexed)
            .build()
        D8.run(command)

        return BuildResult.success(
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.DEX,
                    path = workspace.binDir.absolutePath,
                    description = "D8 dex outputs",
                ),
            ),
            logs = logger.entries,
        )
    }
}
