package com.vibe.app.feature.build

import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class BuildFailureAnalyzer @Inject constructor() {

    fun analyze(
        result: BuildResult,
        projectRoot: File? = null,
    ): BuildFailureAnalysis? {
        if (result.status != BuildStatus.FAILED) return null

        val errorLogs = result.logs.filter { it.level == BuildLogLevel.ERROR }
        val failedStage = errorLogs.lastOrNull()?.stage ?: result.logs.lastOrNull()?.stage
        if (errorLogs.isEmpty() && result.errorMessage.isNullOrBlank()) {
            return BuildFailureAnalysis(
                failedStage = failedStage,
                summary = "Build failed, but no structured diagnostics were captured.",
                rawErrorMessage = null,
            )
        }

        val analyzedErrors = if (errorLogs.isNotEmpty()) {
            errorLogs.map { analyzeEntry(it, projectRoot) }
        } else {
            listOf(
                AnalyzedBuildError(
                    stage = failedStage,
                    category = "build_failed",
                    message = result.errorMessage ?: "Build failed",
                    sourcePath = null,
                    line = null,
                    symbol = null,
                    hint = defaultHint(failedStage, "build_failed", result.errorMessage ?: ""),
                    signature = buildSignature(
                        failedStage,
                        "build_failed",
                        null,
                        null,
                        result.errorMessage ?: "Build failed",
                    ),
                ),
            )
        }

        val dedupedErrors = analyzedErrors
            .distinctBy { it.signature }
            .sortedWith(
                compareByDescending<AnalyzedBuildError> { it.sourcePath != null }
                    .thenByDescending { it.line != null }
                    .thenBy { stageRank(it.stage) }
            )

        val primaryErrors = dedupedErrors.take(MAX_PRIMARY_ERRORS)
        val suggestedReads = primaryErrors
            .mapNotNull { error ->
                val path = error.sourcePath ?: return@mapNotNull null
                val line = error.line ?: return@mapNotNull null
                SuggestedRead(
                    path = path,
                    startLine = (line - READ_CONTEXT_RADIUS).coerceAtLeast(1),
                    endLine = line + READ_CONTEXT_RADIUS,
                )
            }
            .distinctBy { Triple(it.path, it.startLine, it.endLine) }

        val summary = buildSummary(
            failedStage = failedStage,
            primaryErrors = primaryErrors,
            totalErrorCount = dedupedErrors.size,
            rawErrorMessage = result.errorMessage,
        )

        return BuildFailureAnalysis(
            failedStage = failedStage,
            summary = summary,
            primaryErrors = primaryErrors,
            secondaryErrorCount = (dedupedErrors.size - primaryErrors.size).coerceAtLeast(0),
            suggestedReads = suggestedReads,
            rawErrorMessage = result.errorMessage,
        )
    }

    private fun analyzeEntry(
        entry: BuildLogEntry,
        projectRoot: File?,
    ): AnalyzedBuildError {
        val category = classifyCategory(entry)
        val normalizedPath = normalizePath(entry.sourcePath, projectRoot)
        val symbol = extractSymbol(entry.message, category)
        val hint = defaultHint(entry.stage, category, entry.message, symbol)
        return AnalyzedBuildError(
            stage = entry.stage,
            category = category,
            message = collapseWhitespace(entry.message),
            sourcePath = normalizedPath,
            line = entry.line?.toInt(),
            symbol = symbol,
            hint = hint,
            signature = buildSignature(
                stage = entry.stage,
                category = category,
                sourcePath = normalizedPath,
                line = entry.line?.toInt(),
                message = entry.message,
            ),
        )
    }

    private fun classifyCategory(entry: BuildLogEntry): String {
        val probe = entry.message.lowercase()
        return when (entry.stage) {
            BuildStage.RESOURCE -> when {
                "theme.material3" in probe || "theme.appcompat" in probe -> "aapt_style_parent_invalid"
                "style attribute" in probe && "not found" in probe -> "aapt_style_parent_invalid"
                "resource" in probe && "not found" in probe -> "aapt_resource_not_found"
                "expected color" in probe || "expected drawable" in probe -> "aapt_expected_resource_type"
                "@android:" in probe || "android:attr" in probe -> "aapt_namespace_or_attr_error"
                "error inflating" in probe || "class not found" in probe -> "aapt_unknown_view_or_attr"
                else -> "aapt_error"
            }

            BuildStage.COMPILE -> when {
                "cannot find symbol" in probe && (MISSING_R_SYMBOL_REGEX.containsMatchIn(probe) ||
                    probe.startsWith("package r does not exist")) ->
                    "java_missing_r_import"
                "cannot find symbol" in probe -> "java_cannot_find_symbol"
                "package" in probe && "does not exist" in probe -> "java_package_not_found"
                "incompatible types" in probe -> "java_incompatible_types"
                "non-static" in probe && "static context" in probe -> "java_non_static_reference"
                "unreported exception" in probe -> "java_unreported_exception"
                "is public, should be declared in a file named" in probe -> "java_public_class_filename_mismatch"
                "does not override or implement a method from a supertype" in probe -> "java_override_contract_error"
                "cannot be applied to given types" in probe || "cannot find symbol" in probe && "method" in probe ->
                    "java_method_not_found"
                else -> "java_compile_error"
            }

            BuildStage.DEX -> when {
                "duplicate class" in probe -> "d8_duplicate_class"
                "invoke-customs are only supported" in probe || "default interface methods" in probe ->
                    "d8_desugar_or_min_api_issue"
                else -> "d8_error"
            }

            else -> "build_failed"
        }
    }

    private fun extractSymbol(
        message: String,
        category: String,
    ): String? {
        if (category != "java_cannot_find_symbol" && category != "java_method_not_found") return null
        val symbolMatch = SYMBOL_REGEX.find(message)
        if (symbolMatch != null) {
            return symbolMatch.groupValues[2]
        }
        val headerMatch = CANNOT_FIND_SYMBOL_HEADER_REGEX.find(message)
        return headerMatch?.groupValues?.get(1)
    }

    private fun defaultHint(
        stage: BuildStage?,
        category: String,
        message: String,
        symbol: String? = null,
    ): String {
        return when (category) {
            "java_cannot_find_symbol" -> when {
                symbol in UNSUPPORTED_WIDGETS ->
                    "Bundled library does not include $symbol. Replace it with a supported widget or simpler View."
                symbol == "R" ->
                    "Check the package declaration, R import, and whether AAPT2 generated resources successfully."
                else ->
                    "Read the file around this line. Verify imports, class names, and whether the symbol exists in bundled AndroidX/Material APIs."
            }

            "java_missing_r_import" ->
                "Do not change the package name. Ensure the file imports the app package R class and that resource generation succeeded."
            "java_package_not_found" ->
                "Remove unsupported imports or replace them with bundled AndroidX/Material classes already listed in the system prompt."
            "java_incompatible_types" ->
                "Inspect both sides of the assignment or method call and make the smallest type-compatible change."
            "java_method_not_found" ->
                "Check the receiver type and method signature. Prefer adapting the call over rewriting unrelated code."
            "java_non_static_reference" ->
                "Use an instance reference instead of static access, or move the access into the correct object lifecycle."
            "java_unreported_exception" ->
                "Catch the checked exception or move the risky call into a try/catch block."
            "java_public_class_filename_mismatch" ->
                "Rename the file to match the public class name, or rename the class to match the file."
            "java_override_contract_error" ->
                "Check the method signature and imports. The method name, parameters, and base class must match exactly."
            "aapt_style_parent_invalid" -> when {
                "theme.material3" in message.lowercase() || "theme.appcompat" in message.lowercase() ->
                    "Only Theme.MaterialComponents.DayNight.NoActionBar is supported here. Revert the parent theme to an allowed MaterialComponents theme."
                else ->
                    "Inspect themes.xml and style references. Use bundled MaterialComponents theme parents only."
            }

            "aapt_resource_not_found" ->
                "Inspect the referenced XML and the target resource name. Create the missing resource or rename the reference to an existing one."
            "aapt_expected_resource_type" ->
                "The referenced resource type is wrong. Replace it with the expected color/drawable/string resource."
            "aapt_namespace_or_attr_error" ->
                "Check android namespace usage and attribute syntax. Avoid invalid @android:attr or @ prefix combinations."
            "aapt_unknown_view_or_attr" ->
                "The XML references an unsupported widget or attribute. Replace it with a bundled Material/AndroidX component."
            "d8_duplicate_class" ->
                "Two classes with the same binary name are being packaged. Remove the duplicate source or conflicting generated class."
            "d8_desugar_or_min_api_issue" ->
                "Avoid Java language features that need unsupported desugaring here. Use Java 8-compatible source constructs only."
            else -> when (stage) {
                BuildStage.RESOURCE -> "Fix the XML or resource reference first, then rebuild."
                BuildStage.COMPILE -> "Read the reported source file slice, patch minimally, and rebuild immediately."
                BuildStage.DEX -> "Resolve the reported classpath or duplicate-class issue before rebuilding."
                else -> "Fix the primary reported error first, then rebuild."
            }
        }
    }

    private fun buildSummary(
        failedStage: BuildStage?,
        primaryErrors: List<AnalyzedBuildError>,
        totalErrorCount: Int,
        rawErrorMessage: String?,
    ): String {
        if (primaryErrors.isEmpty()) {
            val stageLabel = failedStage?.name ?: "UNKNOWN"
            return rawErrorMessage?.let { "$stageLabel failed: ${collapseWhitespace(it)}" }
                ?: "Build failed during $stageLabel."
        }
        val stageLabel = primaryErrors.firstOrNull()?.stage?.name ?: failedStage?.name ?: "UNKNOWN"
        val file = primaryErrors.firstNotNullOfOrNull { it.sourcePath }
        return buildString {
            append(stageLabel)
            append(" failed")
            if (file != null) {
                append(" in ")
                append(file.substringAfterLast('/'))
            }
            append(": ")
            append(primaryErrors.size)
            append(" primary error")
            if (primaryErrors.size != 1) append('s')
            if (totalErrorCount > primaryErrors.size) {
                append(" (")
                append(totalErrorCount - primaryErrors.size)
                append(" additional error")
                if (totalErrorCount - primaryErrors.size != 1) append('s')
                append(" hidden)")
            }
        }
    }

    private fun normalizePath(
        sourcePath: String?,
        projectRoot: File?,
    ): String? {
        if (sourcePath.isNullOrBlank()) return null
        val root = projectRoot?.let { runCatching { it.canonicalFile }.getOrNull() }
        val source = runCatching { File(sourcePath).canonicalFile }.getOrElse { return sourcePath }
        if (root == null) return source.path
        return if (source == root) {
            "."
        } else if (source.path.startsWith(root.path + File.separator)) {
            source.relativeTo(root).invariantSeparatorsPath
        } else {
            source.path
        }
    }

    private fun buildSignature(
        stage: BuildStage?,
        category: String,
        sourcePath: String?,
        line: Int?,
        message: String,
    ): String {
        val normalizedMessage = collapseWhitespace(message)
            .lowercase()
            .replace(Regex("""\b\d+\b"""), "#")
            .take(160)
        return listOf(
            stage?.name ?: "UNKNOWN",
            category,
            sourcePath ?: "-",
            line?.toString() ?: "-",
            normalizedMessage,
        ).joinToString(":")
    }

    private fun collapseWhitespace(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private fun stageRank(stage: BuildStage?): Int = when (stage) {
        BuildStage.RESOURCE -> 0
        BuildStage.COMPILE -> 1
        BuildStage.DEX -> 2
        BuildStage.PACKAGE -> 3
        BuildStage.SIGN -> 4
        else -> 5
    }

    companion object {
        private const val MAX_PRIMARY_ERRORS = 3
        private const val READ_CONTEXT_RADIUS = 10

        private val UNSUPPORTED_WIDGETS = setOf(
            "MaterialSwitch",
            "SwitchMaterial",
            "BottomAppBar",
        )

        private val MISSING_R_SYMBOL_REGEX = Regex("""symbol:\s*(class|variable)\s+r\b""")
        private val SYMBOL_REGEX = Regex("""symbol:\s*(class|variable|method)\s+([^\s(]+)""")
        private val CANNOT_FIND_SYMBOL_HEADER_REGEX = Regex("""cannot find symbol\s+([^\s:]+)""")
    }
}

data class BuildFailureAnalysis(
    val failedStage: BuildStage?,
    val summary: String,
    val primaryErrors: List<AnalyzedBuildError> = emptyList(),
    val secondaryErrorCount: Int = 0,
    val suggestedReads: List<SuggestedRead> = emptyList(),
    val rawErrorMessage: String? = null,
) {
    fun toJson() = buildJsonObject {
        failedStage?.let { put("failedStage", JsonPrimitive(it.name)) }
        put("summary", JsonPrimitive(summary))
        rawErrorMessage?.let { put("rawErrorMessage", JsonPrimitive(it)) }
        put("secondaryErrorCount", JsonPrimitive(secondaryErrorCount))
        put(
            "primaryErrors",
            buildJsonArray {
                primaryErrors.forEach { error ->
                    add(
                        buildJsonObject {
                            error.stage?.let { put("stage", JsonPrimitive(it.name)) }
                            put("category", JsonPrimitive(error.category))
                            put("message", JsonPrimitive(error.message))
                            error.sourcePath?.let { put("sourcePath", JsonPrimitive(it)) }
                            error.line?.let { put("line", JsonPrimitive(it)) }
                            error.symbol?.let { put("symbol", JsonPrimitive(it)) }
                            error.hint?.let { put("hint", JsonPrimitive(it)) }
                            put("signature", JsonPrimitive(error.signature))
                        },
                    )
                }
            },
        )
        put(
            "suggestedReads",
            buildJsonArray {
                suggestedReads.forEach { suggestion ->
                    add(
                        buildJsonObject {
                            put("path", JsonPrimitive(suggestion.path))
                            put("startLine", JsonPrimitive(suggestion.startLine))
                            put("endLine", JsonPrimitive(suggestion.endLine))
                        },
                    )
                }
            },
        )
    }

    fun toChatPrompt(): String = buildString {
        append("Build failed. Fix the primary errors first, patch minimally, then rebuild.\n\n")
        failedStage?.let {
            append("Failed stage: ")
            append(it.name)
            append('\n')
        }
        append("Summary: ")
        append(summary)
        append("\n\n")
        if (primaryErrors.isNotEmpty()) {
            append("Primary errors:\n")
            primaryErrors.forEachIndexed { index, error ->
                append(index + 1)
                append(". [")
                append(error.category)
                append("] ")
                append(error.message)
                append('\n')
                if (error.sourcePath != null) {
                    append("   File: ")
                    append(error.sourcePath)
                    error.line?.let {
                        append(':')
                        append(it)
                    }
                    append('\n')
                }
                error.hint?.let {
                    append("   Hint: ")
                    append(it)
                    append('\n')
                }
            }
            append('\n')
        }
        if (suggestedReads.isNotEmpty()) {
            append("Suggested reads:\n")
            suggestedReads.forEach {
                append("- ")
                append(it.path)
                append(" lines ")
                append(it.startLine)
                append('-')
                append(it.endLine)
                append('\n')
            }
            append('\n')
        }
        if (!rawErrorMessage.isNullOrBlank()) {
            append("Raw error message:\n")
            append(rawErrorMessage)
        }
    }.trim()
}

data class AnalyzedBuildError(
    val stage: BuildStage?,
    val category: String,
    val message: String,
    val sourcePath: String?,
    val line: Int?,
    val symbol: String?,
    val hint: String?,
    val signature: String,
)

data class SuggestedRead(
    val path: String,
    val startLine: Int,
    val endLine: Int,
)
