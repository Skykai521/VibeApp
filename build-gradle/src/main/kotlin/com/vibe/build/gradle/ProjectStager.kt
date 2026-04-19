package com.vibe.build.gradle

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a template project tree onto disk, substituting {{VAR}}
 * placeholders in:
 *   - file contents of any file whose name ends in `.tmpl`
 *     (the `.tmpl` suffix is stripped in the destination)
 *   - every path component (so `{{PACKAGE_PATH}}/MainActivity.kt`
 *     renders to e.g. `com/vibe/example/MainActivity.kt`).
 *
 * Idempotent: re-staging overwrites unconditionally. Cheap enough that
 * we don't bother with incremental hashing (probe projects have ~10
 * files, ~30 KB total).
 */
@Singleton
class ProjectStager @Inject constructor() {

    fun stage(
        template: ProjectTemplate,
        destinationDir: File,
        variables: Map<String, String>,
    ): File {
        val source = when (template) {
            is ProjectTemplate.FromDirectory -> template.root
        }
        require(source.isDirectory) { "template source not a directory: $source" }

        destinationDir.deleteRecursively()
        destinationDir.mkdirs()

        source.walkTopDown().forEach { entry ->
            if (entry == source) return@forEach
            val relPath = entry.relativeTo(source).path
            val resolvedRel = substitutePath(relPath, variables)
            if (entry.isDirectory) {
                File(destinationDir, resolvedRel).mkdirs()
                return@forEach
            }
            val isTemplate = entry.name.endsWith(".tmpl")
            val destName = if (isTemplate) resolvedRel.removeSuffix(".tmpl") else resolvedRel
            val destFile = File(destinationDir, destName)
            destFile.parentFile?.mkdirs()
            if (isTemplate) {
                destFile.writeText(substitute(entry.readText(), variables))
            } else {
                entry.copyTo(destFile, overwrite = true)
            }
            // Preserve executable bit for shell wrappers in the template.
            if (entry.canExecute()) destFile.setExecutable(true, /* ownerOnly = */ false)
        }
        return destinationDir
    }

    private fun substitute(input: String, variables: Map<String, String>): String {
        var out = input
        for ((k, v) in variables) {
            out = out.replace("{{$k}}", v)
        }
        return out
    }

    /**
     * Substitute `{{VAR}}` in each path component independently. Variables
     * that contain `/` (notably PACKAGE_PATH = `com/vibe/example`) expand
     * cleanly into multi-segment paths.
     */
    private fun substitutePath(relPath: String, variables: Map<String, String>): String =
        relPath.split(File.separatorChar)
            .joinToString(File.separator) { substitute(it, variables) }
}
