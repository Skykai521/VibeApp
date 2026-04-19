package com.vibe.build.runtime.process

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Private implementation of [NativeProcess]. Constructed by
 * [NativeProcessLauncher.launch]. Takes ownership of the three fds
 * returned by [NativeProcessBridge.nativeLaunch] and wraps them as
 * [ParcelFileDescriptor] instances.
 */
internal class NativeProcessImpl(
    override val pid: Int,
    stdoutFd: Int,
    stderrFd: Int,
    stdinFd: Int,
) : NativeProcess {

    private val stdoutPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stdoutFd)
    private val stderrPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stderrFd)
    private val stdinPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stdinFd)

    private val stdoutStream: FileInputStream = FileInputStream(stdoutPfd.fileDescriptor)
    private val stderrStream: FileInputStream = FileInputStream(stderrPfd.fileDescriptor)
    private val stdinStream: FileOutputStream = FileOutputStream(stdinPfd.fileDescriptor)

    override val events: Flow<ProcessEvent> = channelFlow {
        val outJob = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = stdoutStream.read(buffer)
                    if (n <= 0) break
                    send(ProcessEvent.Stdout(buffer.copyOfRange(0, n)))
                }
            } catch (_: Throwable) {
                // pipe closed or process terminated
            }
        }
        val errJob = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = stderrStream.read(buffer)
                    if (n <= 0) break
                    send(ProcessEvent.Stderr(buffer.copyOfRange(0, n)))
                }
            } catch (_: Throwable) {
                // pipe closed or process terminated
            }
        }

        outJob.join()
        errJob.join()

        val exitCode = withContext(Dispatchers.IO) {
            NativeProcessBridge.nativeWaitFor(pid)
        }
        send(ProcessEvent.Exited(exitCode))

        // Close streams. PFDs close their wrapped fds.
        try { stdoutPfd.close() } catch (_: Throwable) {}
        try { stderrPfd.close() } catch (_: Throwable) {}
        try { stdinPfd.close() } catch (_: Throwable) {}
    }.flowOn(Dispatchers.IO)

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        NativeProcessBridge.nativeWaitFor(pid)
    }

    override fun signal(signum: Int): Int =
        NativeProcessBridge.nativeSignal(pid, signum)

    override fun writeStdin(bytes: ByteArray) {
        stdinStream.write(bytes)
        stdinStream.flush()
    }

    override fun closeStdin() {
        try { stdinStream.close() } catch (_: Throwable) {}
        try { stdinPfd.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
