package com.vibe.build.engine.compiler

import android.content.Context
import android.util.Log
import com.sun.tools.javac.api.JavacTool
import com.sun.tools.javac.file.JavacFileManager
import com.vibe.build.engine.internal.BuildStep
import com.vibe.build.engine.internal.BuildWorkspace
import com.vibe.build.engine.internal.RecordingLogger
import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.CompileInput
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import javax.tools.StandardLocation

open class JavacCompiler(
    context: Context,
) : BuildStep(context, BuildStage.COMPILE), com.vibe.build.engine.pipeline.Compiler {

    private val tag = "BuildEngine-Javac"

    override suspend fun compile(input: CompileInput): BuildResult = run(input)

    override suspend fun execute(
        input: CompileInput,
        workspace: BuildWorkspace,
        logger: RecordingLogger,
    ): BuildResult {
        val javaSources = workspace.allJavaSources()
        require(javaSources.isNotEmpty()) { "No Java sources found under ${workspace.sourceDir.absolutePath}" }
        Log.d(
            tag,
            "Compiling ${javaSources.size} Java files from ${workspace.sourceDir.absolutePath} and ${workspace.generatedSourcesDir.absolutePath}",
        )

        if (workspace.classesDir.exists()) {
            workspace.classesDir.deleteRecursively()
        }
        workspace.classesDir.mkdirs()

        var hasErrors = false
        val tool = JavacTool.create()
        val diagnosticListener = javax.tools.DiagnosticListener<JavaFileObject> { diagnostic ->
            if (diagnostic.kind == Diagnostic.Kind.ERROR) {
                hasErrors = true
            }
            logger.debug(com.tyron.builder.model.DiagnosticWrapper(diagnostic))
        }
        val fileManager = tool.getStandardFileManager(
            diagnosticListener,
            Locale.getDefault(),
            StandardCharsets.UTF_8,
        )
        if (fileManager is JavacFileManager) {
            fileManager.setSymbolFileEnabled(false)
        }

        val classpath = input.classpathEntries.map(::File).filter { it.exists() } + workspace.classesDir
        Log.d(
            tag,
            "Classpath entries=${classpath.joinToString { it.absolutePath }}",
        )
        Log.d(
            tag,
            "Platform classpath=${workspace.bootstrapJar.absolutePath}, ${workspace.lambdaStubsJar.absolutePath}",
        )
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(workspace.classesDir))
        fileManager.setLocation(
            StandardLocation.PLATFORM_CLASS_PATH,
            listOf(workspace.bootstrapJar, workspace.lambdaStubsJar),
        )
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath)
        fileManager.setLocation(
            StandardLocation.SOURCE_PATH,
            listOf(workspace.sourceDir, workspace.generatedSourcesDir).filter { it.exists() },
        )

        val options = listOf(
            "-source", "1.8",
            "-target", "1.8",
            "-encoding", StandardCharsets.UTF_8.name(),
        )

        val success = try {
            val task = tool.getTask(
                null,
                fileManager,
                diagnosticListener,
                options,
                null,
                fileManager.getJavaFileObjectsFromFiles(javaSources),
            )
            task.call()
        } finally {
            closeQuietly(fileManager)
        }

        if (!success || hasErrors) {
            Log.e(
                tag,
                "JavacTool failed. success=$success, hasErrors=$hasErrors, sources=${javaSources.joinToString { it.absolutePath }}",
            )
        }
        check(success && !hasErrors) {
            "JavacTool compilation failed. See logcat tag $tag for source list and diagnostics."
        }

        return BuildResult.success(
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.COMPILE,
                    path = workspace.classesDir.absolutePath,
                    description = "JavacTool .class outputs",
                ),
            ),
            logs = logger.entries,
        )
    }

    private fun closeQuietly(fileManager: javax.tools.StandardJavaFileManager) {
        try {
            fileManager.close()
        } catch (_: IOException) {
        }
    }
}
