package com.vibe.build.engine.internal

import com.tyron.builder.BuildModule
import com.vibe.build.engine.model.BuildArtifact
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.CompileInput
import java.io.File

data class BuildWorkspace(
    val rootDir: File,
    val sourceDir: File,
    val resDir: File,
    val assetsDir: File,
    val nativeLibsDir: File,
    val javaResourcesDir: File,
    val manifestFile: File,
    val buildDir: File,
    val binDir: File,
    val generatedSourcesDir: File,
    val classesDir: File,
    val compiledResZip: File,
    val resourcePackage: File,
    val rTxtFile: File,
    val unsignedApk: File,
    val signedApk: File,
    val bootstrapJar: File,
    val lambdaStubsJar: File,
    val androidxClassesJar: File?,
    val androidxResCompiledDir: File?,
) {
    fun allJavaSources(): List<File> {
        return collectFiles(sourceDir, ".java") + collectFiles(generatedSourcesDir, ".java")
    }

    fun allClassFiles(): List<File> = collectFiles(classesDir, ".class")

    fun additionalDexFiles(): List<File> {
        return binDir.listFiles { file ->
            file.isFile && file.name.endsWith(".dex") && file.name != "classes.dex"
        }?.sortedBy { it.name }.orEmpty()
    }

    companion object {
        fun from(input: CompileInput): BuildWorkspace {
            val rootDir = File(input.workingDirectory)
            val sourceDir = File(rootDir, "src/main/java")
            val resDir = File(rootDir, "src/main/res")
            val assetsDir = File(rootDir, "src/main/assets")
            val nativeLibsDir = File(rootDir, "src/main/jniLibs")
            val javaResourcesDir = File(rootDir, "src/main/resources")
            val manifestFile = File(rootDir, "src/main/AndroidManifest.xml")
            val buildDir = File(rootDir, "build")
            val binDir = File(buildDir, "bin")
            val generatedSourcesDir = File(buildDir, "gen")
            val classesDir = File(buildDir, "bin/java/classes")
            val compiledResZip = File(buildDir, "bin/res/project.zip")
            val resourcePackage = File(binDir, "generated.apk.res")
            val rTxtFile = File(buildDir, "bin/res/R.txt")
            val unsignedApk = File(binDir, "generated.apk")
            val signedApk = File(binDir, "signed.apk")

            return BuildWorkspace(
                rootDir = rootDir,
                sourceDir = sourceDir,
                resDir = resDir,
                assetsDir = assetsDir,
                nativeLibsDir = nativeLibsDir,
                javaResourcesDir = javaResourcesDir,
                manifestFile = manifestFile,
                buildDir = buildDir,
                binDir = binDir,
                generatedSourcesDir = generatedSourcesDir,
                classesDir = classesDir,
                compiledResZip = compiledResZip,
                resourcePackage = resourcePackage,
                rTxtFile = rTxtFile,
                unsignedApk = unsignedApk,
                signedApk = signedApk,
                bootstrapJar = BuildModule.getAndroidJar(),
                lambdaStubsJar = BuildModule.getLambdaStubs(),
                androidxClassesJar = BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() },
                androidxResCompiledDir = BuildModule.getAndroidxResCompiledDir()?.takeIf { it.exists() && it.isDirectory },
            )
        }

        fun pipelineArtifacts(workspace: BuildWorkspace): List<BuildArtifact> {
            return listOf(
                BuildArtifact(
                    stage = BuildStage.PREPARE,
                    path = workspace.rootDir.absolutePath,
                    description = "Build workspace root",
                ),
            )
        }

        private fun collectFiles(root: File, extension: String): List<File> {
            if (!root.exists()) {
                return emptyList()
            }
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(extension) }
                .toList()
        }
    }
}
