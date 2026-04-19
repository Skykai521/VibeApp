package com.vibe.app.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibe.app.feature.agent.service.AgentNotificationHelper
import com.vibe.app.plugin.v2.ShadowActivityTracker
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltAndroidApp
class VibeApp : Application() {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var notificationHelper: AgentNotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        // Register the foreground-Activity tracker in EVERY process
        // (including :shadow_plugin), so ShadowPluginInspectorService
        // can resolve the current PluginContainerActivity without a
        // static Activity handoff.
        ShadowActivityTracker.install(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App entered foreground — clear stale task result notifications
                notificationHelper.cancelAllResultNotifications()
            }
        })
    }
}
