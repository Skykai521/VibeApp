package com.vibe.app.feature.projectinit

import android.content.Context
import android.util.Log
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.CompileInput
import com.vibe.build.engine.model.EngineBuildType
import com.vibe.build.engine.pipeline.BuildPipeline
import com.vibe.build.engine.pipeline.BuildProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
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

    data class TemplateProject(
        val projectId: String,
        val projectName: String,
        val packageName: String,
        val appModuleDir: File,
        val minSdk: Int,
        val targetSdk: Int,
    ) {
        fun toCompileInput(): CompileInput = CompileInput(
            projectId = projectId,
            projectName = projectName,
            packageName = packageName,
            workingDirectory = appModuleDir.absolutePath,
            minSdk = minSdk,
            targetSdk = targetSdk,
            buildType = EngineBuildType.DEBUG,
        )
    }

    suspend fun initProject(): BuildResult = withContext(Dispatchers.IO) {
        Log.d(tag, "initProject started")
        val project = prepareTemplateProject(forceReset = true)
        Log.d(tag, "Template project prepared at ${project.appModuleDir.absolutePath}")
        val result = buildProject(project)
        Log.d(
            tag,
            "initProject finished with status=${result.status}, error=${result.errorMessage}, logs=${result.logs.size}",
        )
        result
    }

    suspend fun ensureTemplateProject(): TemplateProject = withContext(Dispatchers.IO) {
        prepareTemplateProject(forceReset = false)
    }

    suspend fun buildTemplateProject(): BuildResult = withContext(Dispatchers.IO) {
        val project = ensureTemplateProject()
        buildProject(project)
    }

    /**
     * Prepares a project-specific workspace by copying the extracted template into
     * `filesDir/projects/{projectId}/app`. Does NOT build the project.
     *
     * Callers should call this from a background coroutine.
     */
    /**
     * Returns the Android package name for a given projectId.
     * e.g. projectId="20260314" → "com.vibe.generated.p20260314"
     * The "p" prefix ensures the last segment is a valid Java identifier.
     */
    fun projectPackageName(projectId: String): String = "com.vibe.generated.p$projectId"

    suspend fun prepareProjectWorkspace(
        projectId: String,
        projectName: String = "Demo",
    ): TemplateProject = withContext(Dispatchers.IO) {
        // Ensure the shared template is extracted first
        val template = ensureTemplateProject()

        val targetAppDir = File(context.filesDir, "projects/$projectId/app")
        if (targetAppDir.exists()) {
            Log.d(tag, "Workspace for $projectId already exists, skipping copy")
            ensureLauncherIconResources(targetAppDir, overwrite = false)
        } else {
            Log.d(tag, "Copying template to projects/$projectId/app")
            targetAppDir.parentFile?.mkdirs()
            template.appModuleDir.copyRecursively(targetAppDir, overwrite = true)
            deleteIgnoredFiles(targetAppDir)
            customizeProjectWorkspace(targetAppDir, projectId, projectName)
            ensureLauncherIconResources(targetAppDir, overwrite = false)
            Log.d(tag, "Workspace ready for $projectId (pkg=${projectPackageName(projectId)}, name=$projectName)")
        }

        TemplateProject(
            projectId = projectId,
            projectName = projectName,
            packageName = projectPackageName(projectId),
            appModuleDir = targetAppDir,
            minSdk = TEMPLATE_MIN_SDK,
            targetSdk = TEMPLATE_TARGET_SDK,
        )
    }

    /**
     * Returns a [TemplateProject] pointing to the project-specific workspace.
     * Throws if the workspace does not exist yet.
     */
    suspend fun ensureProject(projectId: String): TemplateProject = withContext(Dispatchers.IO) {
        val appModuleDir = File(context.filesDir, "projects/$projectId/app")
        require(appModuleDir.exists()) {
            "Workspace for project $projectId does not exist. Call prepareProjectWorkspace first."
        }
        ensureLauncherIconResources(appModuleDir, overwrite = false)
        TemplateProject(
            projectId = projectId,
            projectName = TEMPLATE_PROJECT_NAME,
            packageName = projectPackageName(projectId),
            appModuleDir = appModuleDir,
            minSdk = TEMPLATE_MIN_SDK,
            targetSdk = TEMPLATE_TARGET_SDK,
        )
    }

    /**
     * Searches for a signed APK in the project-specific workspace.
     * Returns the absolute path if found, null otherwise.
     */
    fun findSignedApkPath(projectId: String): String? {
        val projectDir = File(context.filesDir, "projects/$projectId")
        if (!projectDir.exists()) return null
        return projectDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "signed.apk" }
            ?.absolutePath
    }

    /**
     * Builds the project-specific workspace.
     */
    suspend fun buildProject(
        projectId: String,
        progressListener: BuildProgressListener? = null,
    ): BuildResult = withContext(Dispatchers.IO) {
        val project = ensureProject(projectId)
        buildProject(project, progressListener)
    }

    suspend fun ensureProjectLauncherResources(projectId: String) = withContext(Dispatchers.IO) {
        val appModuleDir = File(context.filesDir, "projects/$projectId/app")
        if (!appModuleDir.exists()) return@withContext
        ensureLauncherIconResources(appModuleDir, overwrite = false)
    }

    private suspend fun buildProject(
        project: TemplateProject,
        progressListener: BuildProgressListener? = null,
    ): BuildResult {
        return buildPipeline.run(project.toCompileInput(), progressListener)
    }

    private fun prepareTemplateProject(forceReset: Boolean): TemplateProject {
        val templatesRootDir = File(context.filesDir, TEMPLATE_ROOT_DIR)
        var extracted = false
        if (forceReset && templatesRootDir.exists()) {
            Log.d(tag, "Deleting existing template root ${templatesRootDir.absolutePath}")
            templatesRootDir.deleteRecursively()
        }
        if (!templatesRootDir.exists()) {
            copyTemplateFromAssets(File(context.filesDir, TEMPLATE_ROOT_DIR))
            extracted = true
        }

        val appModuleDir = File(context.filesDir, "$TEMPLATE_ROOT_DIR/EmptyActivity/app")
        require(appModuleDir.exists()) {
            "Template app module was not found after extraction: ${appModuleDir.absolutePath}"
        }
        ensureLauncherIconResources(appModuleDir, overwrite = true)

        val placeholderPackageDir = File(appModuleDir, "src/main/java/\$packagename")
        if (forceReset || extracted || placeholderPackageDir.exists()) {
            rewriteTemplateProject(appModuleDir)
        }

        return TemplateProject(
            projectId = TEMPLATE_PROJECT_ID,
            projectName = TEMPLATE_PROJECT_NAME,
            packageName = TEMPLATE_PACKAGE_NAME,
            appModuleDir = appModuleDir,
            minSdk = TEMPLATE_MIN_SDK,
            targetSdk = TEMPLATE_TARGET_SDK,
        )
    }

    private fun copyTemplateFromAssets(destRoot: File) {
        Log.d(tag, "Copying assets/$TEMPLATE_ASSET_DIR into ${destRoot.absolutePath}")
        copyAssetDir(TEMPLATE_ASSET_DIR, destRoot)
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = context.assets.list(assetPath) ?: emptyArray()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val subChildren = context.assets.list(childAssetPath)
            if (!subChildren.isNullOrEmpty()) {
                copyAssetDir(childAssetPath, childDest)
            } else {
                childDest.parentFile?.mkdirs()
                context.assets.open(childAssetPath).use { input ->
                    FileOutputStream(childDest).use { output ->
                        input.copyTo(output)
                    }
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

        targetPackageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".java")) {
                replacePlaceholders(file)
            }
        }
    }

    /**
     * After copying the shared template workspace to a project-specific directory:
     * 1. Renames the package directory from [TEMPLATE_PACKAGE_NAME] to [projectPackageName].
     * 2. Replaces all occurrences of [TEMPLATE_PACKAGE_NAME] with [projectPackageName] in text files.
     * 3. Replaces the `$appname` placeholder in strings.xml with [projectName].
     */
    private fun customizeProjectWorkspace(appModuleDir: File, projectId: String, projectName: String) {
        val newPackageName = projectPackageName(projectId)
        val oldPackageDir = File(appModuleDir, "src/main/java/${TEMPLATE_PACKAGE_NAME.replace('.', '/')}")
        val newPackageDir = File(appModuleDir, "src/main/java/${newPackageName.replace('.', '/')}")

        if (oldPackageDir.exists()) {
            newPackageDir.parentFile?.mkdirs()
            oldPackageDir.copyRecursively(newPackageDir, overwrite = true)
            oldPackageDir.deleteRecursively()
        }

        val textExtensions = setOf("xml", "java", "gradle", "kt", "properties")
        appModuleDir.walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            .forEach { file ->
                runCatching {
                    val original = file.readText(StandardCharsets.UTF_8)
                    val updated = original
                        .replace(TEMPLATE_PACKAGE_NAME, newPackageName)
                        .replace("\$appname", projectName)
                    if (updated != original) file.writeText(updated, StandardCharsets.UTF_8)
                }
            }
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

    private fun ensureLauncherIconResources(
        appModuleDir: File,
        overwrite: Boolean,
    ) {
        TEMPLATE_ICON_FILES.forEach { relativePath ->
            syncTemplateFileToWorkspace(
                assetRelativePath = relativePath,
                targetFile = File(appModuleDir, relativePath),
                overwrite = overwrite,
            )
        }
    }

    private fun syncTemplateFileToWorkspace(
        assetRelativePath: String,
        targetFile: File,
        overwrite: Boolean,
    ) {
        if (!overwrite && targetFile.exists()) return
        targetFile.parentFile?.mkdirs()
        context.assets.open("$TEMPLATE_ASSET_DIR/EmptyActivity/app/$assetRelativePath").use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private companion object {
        const val TEMPLATE_ASSET_DIR = "templates"
        const val TEMPLATE_ROOT_DIR = "templates"
        const val TEMPLATE_PROJECT_ID = "empty_activity"
        const val TEMPLATE_PROJECT_NAME = "EmptyActivity"
        const val TEMPLATE_PACKAGE_NAME = "com.vibe.generated.emptyactivity"
        const val TEMPLATE_MIN_SDK = 29
        const val TEMPLATE_TARGET_SDK = 36
        val TEMPLATE_ICON_FILES = listOf(
            "src/main/res/drawable/ic_launcher_background.xml",
            "src/main/res/drawable/ic_launcher_foreground.xml",
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
            "src/main/res/mipmap-anydpi/ic_launcher_round.xml",
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
        )
    }
}
