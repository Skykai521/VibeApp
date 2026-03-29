package com.vibe.build.engine.dex

import android.content.Context
import android.util.Log
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

    private val tag = "BuildEngine-DEX"

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

        // Try to use pre-dexed library cache
        val preDexResult = PreDexCache.getOrCreateLibraryDex(context, input.minSdk)
        val usePreDex = preDexResult.dexFiles.isNotEmpty()

        if (usePreDex) {
            // Pre-dex cache available: only DEX user code
            Log.d(tag, "Using pre-dexed libraries (${preDexResult.dexFiles.size} files), DEXing user code only")
            val programFiles = classFiles.map { it.toPath() }

            // Library JARs go on classpath (for type resolution) instead of program files
            val classpathFiles = buildList {
                addAll(input.classpathEntries.map(::File).filter { it.exists() }.map { it.toPath() })
                workspace.androidxClassesJar?.let { add(it.toPath()) }
                workspace.shadowRuntimeJar?.let { add(it.toPath()) }
            }

            val command = D8Command.builder(diagnosticsHandler)
                .addProgramFiles(programFiles)
                .addClasspathFiles(classpathFiles)
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

            // Copy pre-dexed library DEX files into the bin dir with sequential names
            // D8 outputs classes.dex (maybe classes2.dex etc.) for user code.
            // We need to add library DEX files with non-conflicting names.
            val existingDexCount = workspace.binDir.listFiles { f ->
                f.isFile && f.name.endsWith(".dex")
            }?.size ?: 1

            preDexResult.dexFiles.forEachIndexed { index, dexFile ->
                val targetName = "classes${existingDexCount + index + 1}.dex"
                dexFile.copyTo(File(workspace.binDir, targetName), overwrite = true)
            }
            Log.d(tag, "Merged ${preDexResult.dexFiles.size} pre-dexed files into bin/")
        } else {
            // Fallback: DEX everything together (original behavior)
            Log.d(tag, "No pre-dex cache, DEXing all files together")
            val programFiles = classFiles.map { it.toPath() }.toMutableList()
            if (workspace.androidxClassesJar != null) {
                programFiles.add(workspace.androidxClassesJar.toPath())
            }
            if (workspace.shadowRuntimeJar != null) {
                programFiles.add(workspace.shadowRuntimeJar.toPath())
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
        }

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
