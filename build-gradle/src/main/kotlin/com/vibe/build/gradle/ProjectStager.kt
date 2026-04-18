package com.vibe.build.gradle

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a template project tree onto disk, substituting {{VAR}}
 * placeholders in any file whose name ends in `.tmpl`. The `.tmpl`
 * suffix is stripped in the destination.
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
            val rel = entry.relativeTo(source).path
            if (entry.isDirectory) {
                File(destinationDir, rel).mkdirs()
                return@forEach
            }
            val isTemplate = entry.name.endsWith(".tmpl")
            val destName = if (isTemplate) rel.removeSuffix(".tmpl") else rel
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
}
