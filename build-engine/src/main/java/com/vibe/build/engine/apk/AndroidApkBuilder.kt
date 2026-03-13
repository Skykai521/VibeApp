package com.vibe.build.engine.apk

import android.content.Context
import com.android.sdklib.build.ApkBuilder
import com.android.sdklib.build.ApkCreationException
import com.android.sdklib.build.DuplicateFileException
import com.android.sdklib.build.SealedApkException
import com.vibe.build.engine.internal.BuildStep
import com.vibe.build.engine.internal.BuildWorkspace
import com.vibe.build.engine.internal.RecordingLogger
import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.CompileInput
import java.io.File

class AndroidApkBuilder(
    context: Context,
) : BuildStep(context, BuildStage.PACKAGE), com.vibe.build.engine.pipeline.ApkBuilder {

    override suspend fun build(input: CompileInput): BuildResult = run(input)

    override suspend fun execute(
        input: CompileInput,
        workspace: BuildWorkspace,
        logger: RecordingLogger,
    ): BuildResult {
        val mainDex = File(workspace.binDir, "classes.dex")
        require(workspace.resourcePackage.exists()) { "Resource package not found: ${workspace.resourcePackage.absolutePath}" }
        require(mainDex.exists()) { "Main dex not found: ${mainDex.absolutePath}" }

        if (workspace.unsignedApk.exists()) {
            workspace.unsignedApk.delete()
        }

        try {
            val builder = ApkBuilder(
                workspace.unsignedApk.absolutePath,
                workspace.resourcePackage.absolutePath,
                mainDex.absolutePath,
                null,
                null,
            )

            workspace.additionalDexFiles().forEachIndexed { index, file ->
                builder.addFile(file, "classes${index + 2}.dex")
            }

            input.classpathEntries.map(::File)
                .filter { it.exists() && it.isFile && it.extension == "jar" }
                .forEach { builder.addResourcesFromJar(it) }

            if (workspace.nativeLibsDir.exists()) {
                builder.addNativeLibraries(workspace.nativeLibsDir)
            }
            if (workspace.javaResourcesDir.exists()) {
                builder.addSourceFolder(workspace.javaResourcesDir)
            }
            if (input.buildType == com.vibe.build.engine.model.EngineBuildType.DEBUG) {
                builder.setDebugMode(true)
            }

            builder.sealApk()
        } catch (error: DuplicateFileException) {
            throw IllegalStateException(
                "Duplicate file detected while packaging APK: ${error.archivePath}",
                error,
            )
        } catch (error: ApkCreationException) {
            throw IllegalStateException("APK packaging failed", error)
        } catch (error: SealedApkException) {
            throw IllegalStateException("APK sealing failed", error)
        }

        return BuildResult.success(
            artifacts = listOf(
                BuildArtifact(
                    stage = BuildStage.PACKAGE,
                    path = workspace.unsignedApk.absolutePath,
                    description = "Unsigned APK",
                ),
            ),
            logs = logger.entries,
        )
    }
}
