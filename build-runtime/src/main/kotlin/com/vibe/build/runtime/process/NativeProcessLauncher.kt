package com.vibe.build.runtime.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeProcessLauncher @Inject constructor(
    private val envBuilder: ProcessEnvBuilder,
) : ProcessLauncher {

    override suspend fun launch(
        executable: String,
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
    ): NativeProcess = withContext(Dispatchers.IO) {
        require(cwd.isDirectory) { "cwd must be an existing directory: $cwd" }

        val argv = (listOf(executable) + args).toTypedArray()
        val envMap = envBuilder.build(cwd = cwd, extra = env)
        val envp = envMap.entries.map { (k, v) -> "$k=$v" }.toTypedArray()

        val result = NativeProcessBridge.nativeLaunch(
            executable = executable,
            argv = argv,
            envp = envp,
            cwd = cwd.absolutePath,
        )

        val pid = result[0]
        if (pid <= 0) {
            throw ProcessLaunchException(
                "nativeLaunch failed for executable=$executable argv=${argv.joinToString(" ")}",
            )
        }

        NativeProcessImpl(
            pid = pid,
            stdoutFd = result[1],
            stderrFd = result[2],
            stdinFd = result[3],
        )
    }
}
