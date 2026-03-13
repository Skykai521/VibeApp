package com.vibe.app.feature.projectinit

import android.content.Context
import android.util.Log
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.CompileInput
import com.vibe.build.engine.model.EngineBuildType
import com.vibe.build.engine.pipeline.BuildPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ProjectInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buildPipeline: BuildPipeline,
) {

    private val tag = "ProjectInitializer"

    suspend fun initProject(): BuildResult = withContext(Dispatchers.IO) {
        Log.d(tag, "initProject started")
        val appModuleDir = prepareTemplateProject()
        Log.d(tag, "Template project prepared at ${appModuleDir.absolutePath}")
        val result = buildPipeline.run(
            CompileInput(
                projectId = TEMPLATE_PROJECT_ID,
                projectName = TEMPLATE_PROJECT_NAME,
                packageName = TEMPLATE_PACKAGE_NAME,
                workingDirectory = appModuleDir.absolutePath,
                minSdk = TEMPLATE_MIN_SDK,
                targetSdk = TEMPLATE_TARGET_SDK,
                buildType = EngineBuildType.DEBUG,
            ),
        )
        Log.d(
            tag,
            "initProject finished with status=${result.status}, error=${result.errorMessage}, logs=${result.logs.size}",
        )
        result
    }

    private fun prepareTemplateProject(): File {
        val templatesRootDir = File(context.filesDir, TEMPLATE_ROOT_DIR)
        if (templatesRootDir.exists()) {
            Log.d(tag, "Deleting existing template root ${templatesRootDir.absolutePath}")
            templatesRootDir.deleteRecursively()
        }
        unzipTemplateArchive(File(context.filesDir, TEMPLATE_ROOT_DIR))

        val appModuleDir = File(context.filesDir, "$TEMPLATE_ROOT_DIR/EmptyActivity/app")
        require(appModuleDir.exists()) {
            "Template app module was not found after extraction: ${appModuleDir.absolutePath}"
        }

        rewriteTemplateProject(appModuleDir)
        return appModuleDir
    }

    private fun unzipTemplateArchive(destinationRoot: File) {
        destinationRoot.parentFile?.mkdirs()
        Log.d(tag, "Unzipping $TEMPLATE_ARCHIVE_NAME into ${destinationRoot.absolutePath}")
        context.assets.open(TEMPLATE_ARCHIVE_NAME).use { input ->
            ZipInputStream(input).use { zipInput ->
                var entry = zipInput.getNextEntry()
                while (entry != null) {
                    val entryName = entry.name
                    if (shouldSkipEntry(entryName)) {
                        zipInput.closeEntry()
                        entry = zipInput.getNextEntry()
                        continue
                    }

                    val outputFile = File(context.filesDir, entryName)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        Log.d(tag, "Extracting template entry $entryName")
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output ->
                            zipInput.copyTo(output)
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.getNextEntry()
                }
            }
        }
    }

    private fun rewriteTemplateProject(appModuleDir: File) {
        Log.d(tag, "Rewriting placeholders under ${appModuleDir.absolutePath}")
        deleteIgnoredFiles(appModuleDir)

        val placeholderPackageDir = File(appModuleDir, "src/main/java/\$packagename")
        val targetPackageDir = File(
            appModuleDir,
            "src/main/java/${TEMPLATE_PACKAGE_NAME.replace('.', '/')}",
        )
        if (placeholderPackageDir.exists()) {
            Log.d(
                tag,
                "Moving placeholder package ${placeholderPackageDir.absolutePath} -> ${targetPackageDir.absolutePath}",
            )
            targetPackageDir.parentFile?.mkdirs()
            placeholderPackageDir.copyRecursively(targetPackageDir, overwrite = true)
            placeholderPackageDir.deleteRecursively()
        }

        replacePlaceholders(File(appModuleDir, "src/main/AndroidManifest.xml"))
        replacePlaceholders(File(appModuleDir, "build.gradle"))

        val mainActivityFile = File(targetPackageDir, "MainActivity.java")
        replacePlaceholders(mainActivityFile)
    }

    private fun deleteIgnoredFiles(root: File) {
        root.walkTopDown()
            .filter { file ->
                file.name == ".DS_Store" || file.path.contains("__MACOSX")
            }
            .toList()
            .sortedByDescending { it.absolutePath.length }
            .forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
    }

    private fun replacePlaceholders(file: File) {
        if (!file.exists()) {
            Log.w(tag, "Skipping missing template file ${file.absolutePath}")
            return
        }
        Log.d(tag, "Replacing placeholders in ${file.absolutePath}")
        val contents = file.readText(StandardCharsets.UTF_8)
            .replace("\$packagename", TEMPLATE_PACKAGE_NAME)
            .replace("\${minSdkVersion}", TEMPLATE_MIN_SDK.toString())
            .replace("\${targetSdkVersion}", TEMPLATE_TARGET_SDK.toString())
        file.writeText(contents, StandardCharsets.UTF_8)
    }

    private fun shouldSkipEntry(entryName: String): Boolean {
        return entryName.contains("__MACOSX") || entryName.endsWith(".DS_Store")
    }

    private companion object {
        const val TEMPLATE_ARCHIVE_NAME = "templates.zip"
        const val TEMPLATE_ROOT_DIR = "templates"
        const val TEMPLATE_PROJECT_ID = "empty_activity"
        const val TEMPLATE_PROJECT_NAME = "EmptyActivity"
        const val TEMPLATE_PACKAGE_NAME = "com.vibe.generated.emptyactivity"
        const val TEMPLATE_MIN_SDK = 29
        const val TEMPLATE_TARGET_SDK = 36
    }
}
