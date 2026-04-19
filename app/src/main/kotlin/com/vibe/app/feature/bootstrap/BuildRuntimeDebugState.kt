package com.vibe.app.feature.bootstrap

import com.vibe.build.runtime.bootstrap.BootstrapState

/**
 * UI-facing state for the debug Build Runtime screen.
 *
 * [bootstrap] mirrors [com.vibe.build.runtime.bootstrap.BootstrapState].
 * [launchLog] accumulates stdout/stderr from the "launch test process"
 * button for quick diagnostic.
 */
data class BuildRuntimeDebugState(
    val bootstrap: BootstrapState = BootstrapState.NotInstalled,
    val manifestUrl: String = "",
    val devOverrideUrl: String = "",
    val launchLog: String = "",
    val launchRunning: Boolean = false,
)
