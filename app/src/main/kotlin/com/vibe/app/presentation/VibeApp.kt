package com.vibe.app.presentation

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibe.app.feature.agent.service.AgentNotificationHelper
import com.vibe.app.plugin.v2.ShadowActivityTracker
import com.vibe.app.plugin.v2.ShadowLogBridge
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class VibeApp : Application() {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var notificationHelper: AgentNotificationHelper

    @Inject
    lateinit var bootstrapper: RuntimeBootstrapper

    @Inject
    lateinit var bootstrapStateStore: BootstrapStateStore

    @Inject
    lateinit var bootstrapFs: BootstrapFileSystem

    @Inject
    @Named("bootstrapManifestUrl")
    lateinit var bootstrapManifestUrl: String

    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Register Shadow's ILoggerFactory BEFORE anything triggers
        // Hilt's SingletonComponent build-out. BasePluginManager and
        // BaseDynamicPluginManager grab a static logger in their
        // <clinit> via LoggerFactory.getLogger(Class); without a
        // factory registered, Shadow throws from the static init and
        // crashes the host before the first composition even starts.
        // Runs in every process (host + :shadow_plugin + any
        // :plugin*), idempotent via an AtomicBoolean guard.
        ShadowLogBridge.install()
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

        // Kick off the on-device toolchain bootstrap in the background
        // on fresh installs. Every v2 build needs Gradle / JDK / Android
        // SDK / aapt2 under filesDir/usr/opt; doing it during app idle
        // means by the time the user hits "编译" the first time, the
        // heavy 1–2 GB download is already in flight (or done). If the
        // store already reports Ready, we skip — the bootstrap itself
        // is also idempotent via SHA-256 checks so re-running is cheap,
        // but there's no reason to wake the network.
        bootstrapScope.launch {
            try {
                val state = bootstrapStateStore.current()
                val gradleDist = bootstrapFs.componentInstallDir("gradle-9.3.1")
                if (state is BootstrapState.Ready && gradleDist.isDirectory) {
                    Log.d(TAG, "bootstrap already Ready (v${state.manifestVersion})")
                    return@launch
                }
                Log.i(TAG, "bootstrap state=$state, triggering background bootstrap")
                bootstrapper.bootstrap(bootstrapManifestUrl) { s ->
                    Log.d(TAG, "bootstrap progress: $s")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "background bootstrap threw — will retry on first build", t)
            }
        }
    }

    companion object {
        private const val TAG = "VibeApp"
    }
}
