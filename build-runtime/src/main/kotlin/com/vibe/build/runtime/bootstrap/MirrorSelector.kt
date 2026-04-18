package com.vibe.build.runtime.bootstrap

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Two-URL primary/fallback rotation for bootstrap artifacts.
 * "Sticky" once we fall back: within a single session, once the primary
 * has failed we don't retry it for subsequent artifacts (avoid wasting
 * time re-failing).
 *
 * Both URLs omit a trailing slash; `currentUrlFor(fileName)` joins them.
 */
class MirrorSelector @Inject constructor(
    private val primaryBase: String,
    private val fallbackBase: String,
) {
    private val fallen = AtomicBoolean(false)

    fun currentUrlFor(artifactFileName: String): String =
        "${if (fallen.get()) fallbackBase else primaryBase}/$artifactFileName"

    fun currentMirrorName(): String = if (fallen.get()) "FALLBACK" else "PRIMARY"

    fun markPrimaryFailed() {
        fallen.set(true)
    }

    fun reset() {
        fallen.set(false)
    }
}
