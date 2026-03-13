package com.vibe.build.engine.internal

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
        _entries += BuildLogEntry(
            stage = stage,
            level = BuildLogLevel.INFO,
            message = s,
        )
    }

    private fun record(level: BuildLogLevel, wrapper: DiagnosticWrapper) {
        _entries += BuildLogEntry(
            stage = stage,
            level = level,
            message = wrapper.messageOrFallback(),
            sourcePath = wrapper.source?.absolutePath,
            line = wrapper.lineNumber.takeIf { it > 0 },
        )
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
