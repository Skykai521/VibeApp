package com.vibe.app.plugin.v2

import android.content.Context
import android.os.Bundle
import com.tencent.shadow.dynamic.host.EnterCallback
import com.tencent.shadow.dynamic.manager.PluginManagerThatUseDynamicLoader
import java.util.concurrent.TimeUnit

/**
 * Concrete [PluginManagerThatUseDynamicLoader] for VibeApp's v2 host.
 *
 * This class exists mainly to satisfy the abstract API — the real
 * orchestration (install plugin zip, load runtime/loader, launch
 * activity) lives in [ShadowPluginHost] and calls into this instance's
 * inherited methods directly.
 *
 * `enter()` is Shadow's opinionated single-entry pattern for sample
 * apps; we don't use it — our callers drive the sequence explicitly —
 * so the override is a no-op that callback-fires failure if invoked.
 */
class ShadowPluginManager(context: Context) : PluginManagerThatUseDynamicLoader(context) {

    override fun getName(): String = MANAGER_NAME

    override fun enter(
        context: Context?,
        fromId: Long,
        bundle: Bundle?,
        callback: EnterCallback?,
    ) {
        callback?.onCloseLoadingView()
        throw UnsupportedOperationException(
            "ShadowPluginManager.enter() is not used — drive launch via " +
                "ShadowPluginHost which calls install/load/start directly."
        )
    }

    /** Convenience: wait for PPS connection on a background thread. */
    fun awaitServiceConnected() {
        waitServiceConnected(SERVICE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    companion object {
        /**
         * Persisted under `filesDir/$MANAGER_NAME/` — keep stable; a
         * rename invalidates previously installed plugins.
         */
        const val MANAGER_NAME = "vibeapp_shadow"

        private const val SERVICE_CONNECT_TIMEOUT_SEC = 10
    }
}
