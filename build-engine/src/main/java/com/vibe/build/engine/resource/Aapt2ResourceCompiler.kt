package com.vibe.build.engine.resource

import android.content.Context
import android.util.Log
import com.android.tools.aapt2.Aapt2Jni
import com.tyron.builder.model.DiagnosticWrapper
import com.vibe.build.engine.internal.BuildStep
import com.vibe.build.engine.internal.BuildWorkspace
import com.vibe.build.engine.internal.RecordingLogger
import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.CompileInput
import java.io.File

class Aapt2ResourceCompiler(
    context: Context,
) : BuildStep(context, BuildStage.RESOURCE), com.vibe.build.engine.pipeline.ResourceCompiler {

    private val tag = "BuildEngine-Aapt2"

    override suspend fun compile(input: CompileInput): BuildResult = run(input)

    override suspend fun execute(
        input: CompileInput,
        workspace: BuildWorkspace,
        logger: RecordingLogger,
    ): BuildResult {
        workspace.generatedSourcesDir.deleteRecursively()
        workspace.generatedSourcesDir.mkdirs()
        workspace.compiledResZip.parentFile?.mkdirs()
        workspace.resourcePackage.parentFile?.mkdirs()
        workspace.rTxtFile.parentFile?.mkdirs()

        if (workspace.compiledResZip.exists()) {
            workspace.compiledResZip.delete()
        }
        if (workspace.resourcePackage.exists()) {
            workspace.resourcePackage.delete()
        }
        if (workspace.rTxtFile.exists()) {
            workspace.rTxtFile.delete()
        }

        val hasProjectResources = workspace.resDir.exists() &&
            workspace.resDir.walkTopDown().any { it.isFile }
        Log.d(
            tag,
            "Preparing resources from ${workspace.resDir.absolutePath}, manifest=${workspace.manifestFile.absolutePath}, package=${input.packageName}",
        )
        if (hasProjectResources) {
            val compileArgs = mutableListOf(
                "--dir",
                workspace.resDir.absolutePath,
                "-o",
                workspace.compiledResZip.absolutePath,
            )
            Log.d(tag, "AAPT2 compile args=$compileArgs")
            val compileCode = Aapt2Jni.compile(compileArgs)
            recordAaptLogs(logger)
            check(compileCode == 0) { "AAPT2 resource compilation failed" }
        } else {
            logger.quiet("No Android resources found, skipping AAPT2 compile input collection")
        }

        val linkArgs = mutableListOf(
            "-I",
            workspace.bootstrapJar.absolutePath,
            "--manifest",
            workspace.manifestFile.absolutePath,
            "--java",
            workspace.generatedSourcesDir.absolutePath,
            "--custom-package",
            input.packageName,
            "-o",
            workspace.resourcePackage.absolutePath,
            "--output-text-symbols",
            workspace.rTxtFile.absolutePath,
            "--allow-reserved-package-id",
            "--auto-add-overlay",
            "--no-version-vectors",
            "--no-version-transitions",
            "--min-sdk-version",
            input.minSdk.toString(),
            "--target-sdk-version",
            input.targetSdk.toString(),
        )
        if (hasProjectResources) {
            linkArgs += listOf("-R", workspace.compiledResZip.absolutePath)
        }
        if (workspace.assetsDir.exists() && workspace.assetsDir.walkTopDown().any { it.isFile }) {
            linkArgs += listOf("-A", workspace.assetsDir.absolutePath)
        }

        Log.d(tag, "AAPT2 link args=$linkArgs")
        val linkCode = Aapt2Jni.link(linkArgs)
        recordAaptLogs(logger)
        check(linkCode == 0) { "AAPT2 link failed" }

        val generatedRJava = File(
            workspace.generatedSourcesDir,
            input.packageName.replace('.', '/') + "/R.java",
        )
        Log.d(
            tag,
            "AAPT2 outputs: resourcePackageExists=${workspace.resourcePackage.exists()}, rTxtExists=${workspace.rTxtFile.exists()}, generatedRJavaExists=${generatedRJava.exists()}",
        )
        check(workspace.resourcePackage.exists()) {
            "AAPT2 link reported success but resource package was not created: ${workspace.resourcePackage.absolutePath}"
        }
        check(workspace.rTxtFile.exists()) {
            "AAPT2 link reported success but R.txt was not created: ${workspace.rTxtFile.absolutePath}"
        }
        check(generatedRJava.exists()) {
            "AAPT2 link reported success but R.java was not created for package ${input.packageName}: ${generatedRJava.absolutePath}"
        }

        return BuildResult.success(
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.RESOURCE,
                    path = workspace.resourcePackage.absolutePath,
                    description = "AAPT2 linked resource package",
                ),
                BuildArtifact(
                    stage = BuildStage.RESOURCE,
                    path = workspace.generatedSourcesDir.absolutePath,
                    description = "Generated R.java sources",
                ),
                BuildArtifact(
                    stage = BuildStage.RESOURCE,
                    path = workspace.rTxtFile.absolutePath,
                    description = "Text symbols emitted by AAPT2",
                ),
            ),
            logs = logger.entries,
        )
    }

    private fun recordAaptLogs(logger: RecordingLogger) {
        Aapt2Jni.getLogs().forEach { diagnostic ->
            when (diagnostic.kind) {
                javax.tools.Diagnostic.Kind.ERROR -> logger.error(diagnostic.asMessage())
                javax.tools.Diagnostic.Kind.WARNING -> logger.warning(diagnostic)
                else -> logger.info(diagnostic)
            }
        }
    }

    private fun DiagnosticWrapper.asMessage(): String {
        return try {
            getMessage(null)
        } catch (_: Throwable) {
            "AAPT2 failed"
        }
    }
}
