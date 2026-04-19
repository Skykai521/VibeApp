package com.vibe.app.plugin.v2

import android.content.Context
import android.util.Log
import com.vibe.app.plugin.IPluginInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2 plugin host backed by Tencent Shadow's
 * `PluginManagerThatUseDynamicLoader`. Exposes the same
 * `launchPlugin` / `getInspector` / `finishPluginAndReturn`
 * surface as the legacy [com.vibe.app.plugin.legacy.PluginManager] so
 * callers can switch implementations per-project with an import swap.
 *
 * Wiring happens in two layers:
 *   - **This file (Phase 5b-4 layer A):** scaffold — signatures + TODO
 *     markers. Nothing here actually calls Shadow yet.
 *   - **Phase 5b-4 layer B:** first-run extraction of
 *     `assets/shadow/{loader,runtime}.apk` into `filesDir/shadow/`;
 *     concrete `PluginManagerThatUseDynamicLoader` subclass;
 *     `PluginProcessService` declaration in the manifest;
 *     wiring `enter()` -> `loadRuntime` -> `loadPluginLoader` ->
 *     `loadPlugin` -> `convertActivityIntent` -> `startActivity`.
 *   - **Phase 5b-5:** Shadow's bytecode transform applied to the
 *     plugin build so plugin Activities extend
 *     `com.tencent.shadow.core.runtime.ShadowActivity` rather than
 *     framework `android.app.Activity`. Only then is this path
 *     actually launchable end-to-end.
 *
 * Inspector binding deserves a separate note. v1 injects the inspector
 * Service's Binder back through the host ServiceConnection. Under
 * Shadow the plugin Activity lives in a Shadow-controlled process, so
 * the inspector Service needs to be declared by the loader APK and
 * talked to over the same Shadow binder channel. Phase 5b-4 layer B
 * decides whether we (a) keep the v1-style `bindService` in the plugin
 * process or (b) multiplex through Shadow's existing PPS IPC.
 */
@Singleton
class ShadowPluginHost @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun launchPlugin(
        apkPath: String,
        packageName: String,
        projectId: String = apkPath,
        projectName: String? = null,
    ) {
        Log.w(TAG, "ShadowPluginHost.launchPlugin not yet implemented (apk=$apkPath pkg=$packageName)")
        TODO("Phase 5b-4 layer B: call into PluginManagerThatUseDynamicLoader")
    }

    fun getInspector(projectId: String): IPluginInspector? {
        Log.w(TAG, "ShadowPluginHost.getInspector not yet implemented (project=$projectId)")
        return null
    }

    fun finishPluginAndReturn(projectId: String) {
        Log.w(TAG, "ShadowPluginHost.finishPluginAndReturn not yet implemented (project=$projectId)")
    }

    companion object {
        private const val TAG = "ShadowPluginHost"
    }
}
