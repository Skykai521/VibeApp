package com.vibe.build.engine.internal

import android.content.Context
import com.tyron.builder.BuildModule
import com.vibe.build.engine.model.CompileInput
import java.io.File
import java.nio.charset.StandardCharsets

object BuildWorkspacePreparer {

    fun prepare(context: Context, input: CompileInput): BuildWorkspace {
        BuildModule.initialize(context.applicationContext)

        val workspace = BuildWorkspace.from(input)
        ensureDirectory(workspace.rootDir)
        ensureDirectory(workspace.sourceDir)
        ensureDirectory(workspace.resDir)
        ensureDirectory(workspace.assetsDir)
        ensureDirectory(workspace.nativeLibsDir)
        ensureDirectory(workspace.javaResourcesDir)

        materializeFiles(workspace.sourceDir, input.sourceFiles)
        materializeFiles(workspace.resDir, input.resourceFiles)
        materializeFiles(workspace.assetsDir, input.assetFiles)
        materializeManifest(workspace, input)

        return workspace
    }

    private fun materializeFiles(baseDir: File, files: Map<String, String>) {
        files.forEach { (relativePath, contents) ->
            val target = File(baseDir, relativePath)
            ensureDirectory(target.parentFile)
            target.writeText(contents, StandardCharsets.UTF_8)
        }
    }

    private fun materializeManifest(workspace: BuildWorkspace, input: CompileInput) {
        when {
            input.manifestContents != null -> {
                ensureDirectory(workspace.manifestFile.parentFile)
                workspace.manifestFile.writeText(input.manifestContents, StandardCharsets.UTF_8)
            }

            input.manifestFilePath != null -> {
                val source = File(input.manifestFilePath)
                if (source.exists()) {
                    ensureDirectory(workspace.manifestFile.parentFile)
                    source.copyTo(workspace.manifestFile, overwrite = true)
                }
            }

            !workspace.manifestFile.exists() -> {
                ensureDirectory(workspace.manifestFile.parentFile)
                workspace.manifestFile.writeText(defaultManifest(input), StandardCharsets.UTF_8)
            }
        }
    }

    private fun defaultManifest(input: CompileInput): String {
        return """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="${input.packageName}">
                <uses-sdk
                    android:minSdkVersion="${input.minSdk}"
                    android:targetSdkVersion="${input.targetSdk}" />
                <application
                    android:allowBackup="true"
                    android:label="${xmlEscape(input.projectName)}"
                    android:supportsRtl="true" />
            </manifest>
        """.trimIndent()
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun ensureDirectory(directory: File?) {
        if (directory != null && !directory.exists()) {
            directory.mkdirs()
        }
    }
}
