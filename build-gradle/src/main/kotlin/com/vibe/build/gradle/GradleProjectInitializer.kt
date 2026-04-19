package com.vibe.build.gradle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a buildable Android project from a template under
 * `app/src/main/assets/templates/<templateName>/`.
 *
 * Pipeline:
 *   1. Recursively extract the template tree from the APK assets to a
 *      working directory under [Context.getCacheDir].
 *   2. Compute the canonical variable map (PACKAGE_NAME, PACKAGE_PATH,
 *      PROJECT_NAME, SDK_DIR, GRADLE_USER_HOME) from the caller's input.
 *   3. Hand off to [ProjectStager.stage] which copies + substitutes
 *      `{{VAR}}` placeholders in both file contents (`.tmpl` files) and
 *      path components.
 *
 * The output is a project directory ready to feed straight into
 * `GradleBuildService.runBuild(projectDirectory = ...)`.
 */
@Singleton
class GradleProjectInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stager: ProjectStager,
) {

    fun initialize(input: Input): File {
        val template = extractTemplate(input.templateName)
        val variables = mapOf(
            "PROJECT_NAME" to input.projectName,
            "PACKAGE_NAME" to input.packageName,
            "PACKAGE_PATH" to input.packageName.replace('.', '/'),
            "SDK_DIR" to input.sdkDir.absolutePath,
            "GRADLE_USER_HOME" to input.gradleUserHome.absolutePath,
            "SHADOW_PLUGIN_REPO" to input.shadowPluginRepo.absolutePath,
        )
        return stager.stage(
            template = ProjectTemplate.FromDirectory(template),
            destinationDir = input.destinationDir,
            variables = variables,
        )
    }

    /**
     * Recursively copy `assets/templates/<templateName>/` into a fresh
     * directory under cacheDir. Returns the root of the extracted tree.
     */
    private fun extractTemplate(templateName: String): File {
        val assetRoot = "templates/$templateName"
        val dest = File(context.cacheDir, "template-$templateName-${System.nanoTime()}")
        dest.deleteRecursively()
        dest.mkdirs()
        copyAssetTree(assetRoot, dest)
        return dest
    }

    private fun copyAssetTree(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val child = "$assetPath/$entry"
            val childList = context.assets.list(child) ?: emptyArray()
            if (childList.isEmpty()) {
                // Leaf: AssetManager.list on a file returns empty.
                context.assets.open(child).use { input ->
                    File(destDir, entry).outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetTree(child, File(destDir, entry))
            }
        }
    }

    data class Input(
        val templateName: String,
        val projectName: String,
        val packageName: String,
        val sdkDir: File,
        val gradleUserHome: File,
        val destinationDir: File,
        /**
         * Local Maven repo containing Shadow's Gradle plugin + its
         * vendored transform deps. Produced by
         * `ShadowPluginRepoExtractor.extractIfNeeded()` before this
         * call. Templates reference the absolute path via the
         * `{{SHADOW_PLUGIN_REPO}}` placeholder in their
         * `settings.gradle.kts.tmpl`.
         */
        val shadowPluginRepo: File,
    )
}
