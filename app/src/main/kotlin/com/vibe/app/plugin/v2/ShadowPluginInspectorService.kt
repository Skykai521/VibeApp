package com.vibe.app.plugin.v2

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vibe.app.plugin.IPluginInspector

/**
 * v2 inspector Service running in the `:shadow_plugin` process. Binds
 * the [IPluginInspector] AIDL surface and delegates every call to a
 * [PluginInspectionCore] configured with [ShadowActivityTracker] —
 * which, in that process, always resolves to Shadow's current
 * `PluginContainerActivity`.
 *
 * Declared in `AndroidManifest.xml` with `android:process=":shadow_plugin"`
 * so it lives in the same process as Shadow's PPS and the running
 * plugin. Host-side binding happens in [ShadowPluginHost.getInspector].
 */
class ShadowPluginInspectorService : Service() {

    private val core = PluginInspectionCore(
        activityProvider = ShadowActivityTracker::foregroundActivity,
    )

    private val binder = object : IPluginInspector.Stub() {
        override fun dumpViewTree(optionsJson: String?): String =
            core.dumpViewTree(optionsJson)

        override fun executeAction(actionJson: String?): String =
            core.executeAction(actionJson)

        override fun captureScreenshot(optionsJson: String?): String =
            core.captureScreenshot(optionsJson)
    }

    override fun onBind(intent: Intent): IBinder = binder
}
