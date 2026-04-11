package com.vibe.app.feature.agent.tool

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

enum class GrepOutputMode { CONTENT, FILES_WITH_MATCHES, COUNT }

data class GrepArgs(
    val pattern: String,
    val regex: Boolean = false,
    val caseInsensitive: Boolean = false,
    val glob: String = "",
    val outputMode: GrepOutputMode = GrepOutputMode.CONTENT,
    val contextLines: Int = 0,
    val maxResults: Int = 50,
)

data class GrepResult(
    val mode: GrepOutputMode,
    val matchesText: String = "",
    val files: List<String> = emptyList(),
    val matchCount: Int = 0,
    val fileCount: Int = 0,
    val truncated: Boolean = false,
)

/**
 * Pure file-tree grep. Walks [searchRoot] (which must be inside [projectRoot])
 * and returns matches using project-relative paths.
 *
 * All limits (file size, output bytes, line length, max results) are hard-coded
 * to protect agent context budget — see GrepProjectFilesTool design doc §5.
 */
@Singleton
class ProjectGrepEngine @Inject constructor() {

    fun search(searchRoot: File, projectRoot: File, args: GrepArgs): GrepResult {
        if (!searchRoot.exists()) return GrepResult(mode = args.outputMode)

        val matcher = buildMatcher(args)
        val globPredicate = buildGlobPredicate(args.glob)
        val projectRootCanonical = projectRoot.canonicalFile
        val searchRootCanonical = searchRoot.canonicalFile
        val maxResults = args.maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val contextLines = args.contextLines.coerceIn(0, 3)

        val sb = StringBuilder()
        val filesWithMatchesOrdered = linkedSetOf<String>()
        var totalMatchCount = 0
        var truncated = false

        val files = searchRootCanonical.walkTopDown()
            .onEnter { dir -> dir == searchRootCanonical || dir.name !in EXCLUDED_DIRS }
            .filter { it.isFile }
            .filter { !isBinaryFile(it) }
            .filter { it.length() <= MAX_FILE_SIZE }

        outer@ for (file in files) {
            if (truncated) break
            val relative = file.toRelativeString(projectRootCanonical)
                .replace(File.separatorChar, '/')
            if (globPredicate != null && !globPredicate(relative)) continue

            val lines = runCatching { file.readLines() }.getOrNull() ?: continue

            // Collect (lineIndex, firstMatchPos) for lines that match.
            val matches = mutableListOf<Pair<Int, Int>>()
            for ((idx, line) in lines.withIndex()) {
                val pos = matcher.firstMatch(line)
                if (pos >= 0) matches += idx to pos
            }
            if (matches.isEmpty()) continue
            filesWithMatchesOrdered += relative

            when (args.outputMode) {
                GrepOutputMode.FILES_WITH_MATCHES -> {
                    if (filesWithMatchesOrdered.size >= maxResults) truncated = true
                }

                GrepOutputMode.COUNT -> {
                    val line = "$relative:${matches.size}\n"
                    if (sb.length + line.length > MAX_OUTPUT_BYTES) {
                        truncated = true
                    } else {
                        sb.append(line)
                        if (filesWithMatchesOrdered.size >= maxResults) truncated = true
                    }
                }

                GrepOutputMode.CONTENT -> {
                    val matchIdxToPos = matches.toMap()
                    val emitOrder = sortedSetOf<Int>()
                    for ((idx, _) in matches) {
                        val from = (idx - contextLines).coerceAtLeast(0)
                        val to = (idx + contextLines).coerceAtMost(lines.lastIndex)
                        for (k in from..to) emitOrder.add(k)
                    }
                    for (idx in emitOrder) {
                        val matchPos = matchIdxToPos[idx] ?: -1
                        val isMatch = matchPos >= 0
                        val sep = if (isMatch) ':' else '-'
                        val text = truncateLine(lines[idx], matchPos)
                        val lineStr = "$relative:${idx + 1}$sep$text\n"
                        if (sb.length + lineStr.length > MAX_OUTPUT_BYTES) {
                            truncated = true
                            break@outer
                        }
                        sb.append(lineStr)
                        if (isMatch) {
                            totalMatchCount++
                            if (totalMatchCount >= maxResults) {
                                truncated = true
                                break@outer
                            }
                        }
                    }
                }
            }
        }

        val fileList = filesWithMatchesOrdered.toList()
        return when (args.outputMode) {
            GrepOutputMode.CONTENT -> GrepResult(
                mode = GrepOutputMode.CONTENT,
                matchesText = sb.toString().trimEnd('\n'),
                matchCount = totalMatchCount,
                fileCount = fileList.size,
                truncated = truncated,
            )
            GrepOutputMode.FILES_WITH_MATCHES -> GrepResult(
                mode = GrepOutputMode.FILES_WITH_MATCHES,
                files = fileList,
                fileCount = fileList.size,
                truncated = truncated,
            )
            GrepOutputMode.COUNT -> GrepResult(
                mode = GrepOutputMode.COUNT,
                matchesText = sb.toString().trimEnd('\n'),
                fileCount = fileList.size,
                truncated = truncated,
            )
        }
    }

    // ── Matching ────────────────────────────────────────────────────────

    private fun interface LineMatcher {
        /** Returns the index of the first match in [line], or -1 if no match. */
        fun firstMatch(line: String): Int
    }

    private fun buildMatcher(args: GrepArgs): LineMatcher = when {
        args.regex -> {
            val flags = if (args.caseInsensitive) Pattern.CASE_INSENSITIVE else 0
            val pattern = Pattern.compile(args.pattern, flags)
            LineMatcher { line ->
                val m = pattern.matcher(line)
                if (m.find()) m.start() else -1
            }
        }
        args.caseInsensitive -> LineMatcher { line ->
            line.indexOf(args.pattern, ignoreCase = true)
        }
        else -> LineMatcher { line -> line.indexOf(args.pattern) }
    }

    // ── Glob ────────────────────────────────────────────────────────────

    private fun buildGlobPredicate(glob: String): ((String) -> Boolean)? {
        if (glob.isBlank()) return null
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        // Bare glob like `*.java` (no slash) → match against basename at any depth.
        val basenameMode = '/' !in glob
        return { relative ->
            val target = if (basenameMode) relative.substringAfterLast('/') else relative
            matcher.matches(Paths.get(target))
        }
    }

    // ── Filters ─────────────────────────────────────────────────────────

    private fun isBinaryFile(file: File): Boolean =
        file.extension.lowercase() in BINARY_EXTENSIONS

    private fun truncateLine(line: String, matchPos: Int): String {
        if (line.length <= MAX_LINE_LENGTH) return line
        if (matchPos < 0) return line.take(MAX_LINE_LENGTH) + "…"
        val half = MAX_LINE_LENGTH / 2
        val start = (matchPos - half).coerceAtLeast(0)
        val end = (start + MAX_LINE_LENGTH).coerceAtMost(line.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < line.length) "…" else ""
        return prefix + line.substring(start, end) + suffix
    }

    companion object {
        private const val MAX_FILE_SIZE = 1L * 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 32 * 1024
        private const val MAX_LINE_LENGTH = 500
        private const val HARD_MAX_RESULTS = 200

        private val EXCLUDED_DIRS = setOf("build", ".gradle", ".idea")

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico",
            "ttf", "otf", "woff", "woff2",
            "zip", "jar", "aar", "dex", "apk",
            "so", "dylib", "dll",
            "mp3", "mp4", "wav", "ogg", "flac",
            "pdf", "class",
        )
    }
}
