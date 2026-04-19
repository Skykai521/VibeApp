package com.vibe.build.runtime.process

/**
 * Stream event from a running native process.
 *
 * `Stdout`/`Stderr` payloads are byte-safe (no charset assumption).
 * The `equals`/`hashCode` implementations compare content, not array
 * identity, so tests can assert on specific bytes.
 */
sealed interface ProcessEvent {

    data class Stdout(val bytes: ByteArray) : ProcessEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stdout && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Stderr(val bytes: ByteArray) : ProcessEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stderr && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Terminal event. No further events follow.
     *   - 0-255: normal exit
     *   - 128+signal: killed by a signal
     *   - -1: waitpid failure
     */
    data class Exited(val code: Int) : ProcessEvent
}
