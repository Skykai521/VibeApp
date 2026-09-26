package com.vibe.app.feature.project

import android.content.Context
import android.os.FileObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Watches a project's crash.log via inotify (FileObserver) and emits the
 * latest crash summary whenever the file is written.
 *
 * Watches the logs DIRECTORY (not the file): crash.log may not exist yet,
 * and FileObserver on a non-existent path never fires. Works across
 * processes — plugin processes and DebugReportProvider write the same path.
 */
@Singleton
class CrashLogWatcher @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    fun watch(projectId: String): Flow<String> = callbackFlow {
        val logDir = File(appContext.filesDir, "projects/$projectId/logs")
        logDir.mkdirs()
        val crashFile = File(logDir, "crash.log")
        var lastSize = if (crashFile.exists()) crashFile.length() else 0L

        val observer = object : FileObserver(logDir, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != "crash.log") return
                val size = crashFile.length()
                if (size <= lastSize) return
                lastSize = size
                extractLatestCrash(crashFile)?.let { trySend(it) }
            }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }

    companion object {
        /** Returns the last "--- CRASH" block (max 15 lines), or null. */
        fun extractLatestCrash(crashFile: File): String? {
            if (!crashFile.exists()) return null
            val lines = crashFile.readLines()
            val lastCrashIdx = lines.indexOfLast { it.startsWith("--- CRASH") }
            if (lastCrashIdx < 0) return null
            return lines.drop(lastCrashIdx).take(15).joinToString("\n")
        }
    }
}
