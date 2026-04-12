package com.vibe.app.feature.agent.tool

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a compact symbol outline for a project workspace: Java classes/methods,
 * layout view ids, values resource keys, and AndroidManifest activities. Used by
 * both `list_project_files` and the turn 2+ auto-injection so the agent can pick
 * grep keywords without reading whole files.
 */
@Singleton
class ProjectOutlineBuilder @Inject constructor() {

    fun build(root: File): String {
        if (!root.exists() || !root.isDirectory) return ""

        val files = root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in EXCLUDED_DIRS }
            .filter { it.isFile }
            .sortedBy { it.toRelativeString(root).replace(File.separatorChar, '/') }
            .toList()

        val sb = StringBuilder()
        var truncated = false
        for (file in files) {
            val relative = file.toRelativeString(root).replace(File.separatorChar, '/')
            val section = runCatching { renderSection(relative, file) }
                .getOrElse { "$relative\n" }
            if (sb.length + section.length > MAX_OUTLINE_BYTES) {
                truncated = true
                break
            }
            sb.append(section)
        }
        if (truncated) {
            sb.append("… outline truncated (limit ${MAX_OUTLINE_BYTES / 1024} KB)\n")
        }
        return sb.toString()
    }

    private fun renderSection(relative: String, file: File): String = when {
        relative.endsWith("AndroidManifest.xml") -> renderManifest(relative, file)
        relative.endsWith(".java") -> renderJava(relative, file)
        LAYOUT_PATH_REGEX.containsMatchIn(relative) -> renderLayout(relative, file)
        VALUES_PATH_REGEX.containsMatchIn(relative) -> renderValues(relative, file)
        else -> "$relative\n"
    }

    private fun renderManifest(relative: String, file: File): String {
        val text = file.readText()
        val pkg = MANIFEST_PACKAGE_REGEX.find(text)?.groupValues?.get(1).orEmpty()
        val activities = MANIFEST_ACTIVITY_REGEX.findAll(text)
            .map { it.groupValues[1].substringAfterLast('.') }
            .distinct()
            .toList()
        val tail = if (activities.isEmpty()) "" else activities.joinToString(", ", prefix = " [", postfix = "]")
        val prefix = if (pkg.isEmpty() && tail.isEmpty()) "" else ": $pkg$tail"
        return "$relative$prefix\n"
    }

    private fun renderJava(relative: String, file: File): String {
        val text = file.readText()
        val classMatch = JAVA_CLASS_REGEX.find(text)
        val className = classMatch?.groupValues?.get(1)
        val extends = classMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

        val allMethods = JAVA_METHOD_REGEX.findAll(text)
            .map { it.groupValues[1] }
            .filter { it != className } // drop constructors
            .toList()
        val methods = allMethods.take(MAX_JAVA_METHODS)
        val methodTruncated = allMethods.size > MAX_JAVA_METHODS

        return buildString {
            append(relative).append('\n')
            if (className != null) {
                append("  class ").append(className)
                if (extends != null) append(" extends ").append(extends)
                append('\n')
            }
            if (methods.isNotEmpty()) {
                append("  methods: ").append(methods.joinToString(", "))
                if (methodTruncated) append(", …")
                append('\n')
            }
        }
    }

    private fun renderLayout(relative: String, file: File): String {
        val text = file.readText()
        val allIds = LAYOUT_ID_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()
        val ids = allIds.take(MAX_LAYOUT_IDS)
        val truncated = allIds.size > MAX_LAYOUT_IDS
        return buildString {
            append(relative).append('\n')
            if (ids.isNotEmpty()) {
                append("  ids: ").append(ids.joinToString(", "))
                if (truncated) append(", …")
                append('\n')
            }
        }
    }

    private fun renderValues(relative: String, file: File): String {
        val text = file.readText()
        val keys = VALUES_NAME_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()
        return buildString {
            append(relative).append('\n')
            if (keys.isNotEmpty()) {
                append("  keys: ").append(keys.joinToString(", ")).append('\n')
            }
        }
    }

    companion object {
        private const val MAX_JAVA_METHODS = 20
        private const val MAX_LAYOUT_IDS = 30
        private const val MAX_OUTLINE_BYTES = 8 * 1024

        private val EXCLUDED_DIRS = setOf("build", ".gradle", ".idea")

        private val LAYOUT_PATH_REGEX = Regex("""(^|/)res/layout[^/]*/[^/]+\.xml$""")
        private val VALUES_PATH_REGEX = Regex("""(^|/)res/values[^/]*/[^/]+\.xml$""")

        private val JAVA_CLASS_REGEX = Regex(
            """\b(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?""",
        )

        // Matches `public` or `protected` method declarations.
        // [^(;={}]*? lets return type (incl. generics) match lazily without crossing
        // field initializers, block bodies, or statement terminators.
        private val JAVA_METHOD_REGEX = Regex(
            """(?m)^\s*(?:public|protected)\s+[^(;={}]*?\b(\w+)\s*\(""",
        )

        private val LAYOUT_ID_REGEX = Regex("""android:id="@\+id/(\w+)"""")
        private val VALUES_NAME_REGEX = Regex("""\bname="([^"]+)"""")
        private val MANIFEST_PACKAGE_REGEX = Regex("""\bpackage="([^"]+)"""")
        private val MANIFEST_ACTIVITY_REGEX = Regex("""<activity[^>]*android:name="([^"]+)"""")
    }
}
