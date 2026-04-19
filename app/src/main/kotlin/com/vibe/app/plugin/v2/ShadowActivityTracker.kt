package com.vibe.app.plugin.v2

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently resumed Activity in whatever process we're in.
 * Registered from `VibeApp.Application.onCreate()`, so this singleton
 * gets installed in every process the host app spawns — including
 * Shadow's `:shadow_plugin` process where Shadow's
 * `PluginContainerActivity` hosts plugin UI.
 *
 * [ShadowPluginInspectorService] calls [foregroundActivity] to decide
 * which Activity's view tree to dump / dispatch events into. In the
 * `:shadow_plugin` process that will always be a
 * `PluginContainerActivity` instance (the framework-visible Activity
 * that embeds the plugin's real ShadowActivity).
 */
object ShadowActivityTracker {

    private var currentRef: WeakReference<Activity>? = null

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                currentRef = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentRef?.get() === activity) currentRef = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (currentRef?.get() === activity) currentRef = null
            }
        })
    }

    /** Returns the most recently resumed Activity in this process, or null. */
    fun foregroundActivity(): Activity? = currentRef?.get()
}
