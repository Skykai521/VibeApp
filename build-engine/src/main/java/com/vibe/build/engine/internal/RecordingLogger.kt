package com.vibe.build.engine.internal

import android.util.Log
import com.tyron.builder.log.ILogger
import com.tyron.builder.model.DiagnosticWrapper
import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildStage
import java.util.Collections
import javax.tools.Diagnostic

class RecordingLogger(
    private val stage: BuildStage,
) : ILogger {

    private val tag = "BuildEngine-${stage.name}"

    private val _entries = Collections.synchronizedList(mutableListOf<BuildLogEntry>())

    val entries: List<BuildLogEntry>
        get() = _entries.toList()

    override fun info(wrapper: DiagnosticWrapper) {
        record(BuildLogLevel.INFO, wrapper)
    }

    override fun debug(wrapper: DiagnosticWrapper) {
        record(BuildLogLevel.DEBUG, wrapper)
    }

    override fun warning(wrapper: DiagnosticWrapper) {
        record(BuildLogLevel.WARNING, wrapper)
    }

    override fun error(wrapper: DiagnosticWrapper) {
        record(BuildLogLevel.ERROR, wrapper)
    }

    override fun quiet(s: String) {
        val entry = BuildLogEntry(
            stage = stage,
            level = BuildLogLevel.INFO,
            message = s,
        )
        _entries += entry
        printToLogcat(entry)
    }

    private fun record(level: BuildLogLevel, wrapper: DiagnosticWrapper) {
        val entry = BuildLogEntry(
            stage = stage,
            level = level,
            message = wrapper.messageOrFallback(),
            sourcePath = wrapper.source?.absolutePath,
            line = wrapper.lineNumber.takeIf { it > 0 },
        )
        _entries += entry
        printToLogcat(entry)
    }

    private fun printToLogcat(entry: BuildLogEntry) {
        val rendered = buildString {
            append(entry.message)
            entry.sourcePath?.let { source ->
                append(" | source=")
                append(source)
            }
            entry.line?.let { line ->
                append(" | line=")
                append(line)
            }
        }
        when (entry.level) {
            BuildLogLevel.DEBUG -> Log.d(tag, rendered)
            BuildLogLevel.INFO -> Log.i(tag, rendered)
            BuildLogLevel.WARNING -> Log.w(tag, rendered)
            BuildLogLevel.ERROR -> Log.e(tag, rendered)
        }
    }

    private fun DiagnosticWrapper.messageOrFallback(): String {
        val explicitMessage = try {
            getMessage(null)
        } catch (_: Throwable) {
            null
        }
        if (!explicitMessage.isNullOrBlank()) {
            return explicitMessage
        }
        return when (kind) {
            Diagnostic.Kind.ERROR -> "Compilation error"
            Diagnostic.Kind.WARNING -> "Compilation warning"
            else -> "Build note"
        }
    }
}
