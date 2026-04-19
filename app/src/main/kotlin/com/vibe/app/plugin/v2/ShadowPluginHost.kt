package com.vibe.app.plugin.v2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.vibe.app.plugin.IPluginInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The v2 plugin host, backed by Tencent Shadow's
 * `PluginManagerThatUseDynamicLoader`. Single entry point for
 * launching a Shadow-transformed plugin APK, getting the inspector
 * binder, and bringing VibeApp back to the foreground.
 */
@Singleton
class ShadowPluginHost @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val extractor = ShadowApkExtractor(context)
    private val manager = ShadowPluginManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launchMutex = Mutex()

    /** Persisted per-project state so re-launching doesn't re-install. */
    private data class InstalledProject(val uuid: String, val partKey: String)

    private val installed = mutableMapOf<String /* projectId */, InstalledProject>()

    @Volatile
    private var serviceBound = false

    @Volatile
    private var inspector: IPluginInspector? = null

    private var inspectorConnection: ServiceConnection? = null

    fun launchPlugin(
        apkPath: String,
        packageName: String,
        projectId: String = apkPath,
        projectName: String? = null,
    ) {
        runBlocking {
            launchMutex.withLock {
                launchInternal(apkPath, packageName, projectId, projectName)
            }
        }
    }

    private suspend fun launchInternal(
        apkPath: String,
        packageName: String,
        projectId: String,
        projectName: String?,
    ) {
        Log.i(TAG, "launchPlugin project=$projectId pkg=$packageName apk=$apkPath")

        val apks = withContext(Dispatchers.IO) { extractor.extractIfNeeded() }

        val partKey = projectName ?: packageName

        val install = installed[projectId] ?: withContext(Dispatchers.IO) {
            installProjectZip(
                projectId = projectId,
                partKey = partKey,
                packageName = packageName,
                loader = apks.loader,
                runtime = apks.runtime,
                plugin = File(apkPath),
            ).also { installed[projectId] = it }
        }

        if (!serviceBound) {
            withContext(Dispatchers.IO) { bindAndWait() }
        }

        // Drive Shadow's load sequence. These calls are synchronous IPC
        // into the plugin process — must NOT run on the main thread.
        withContext(Dispatchers.IO) {
            try {
                manager.loadRunTime(install.uuid)
                manager.loadPluginLoader(install.uuid)
                val loader = checkNotNull(loaderBinder()) {
                    "Shadow PluginLoader binder unavailable after loadPluginLoader"
                }
                loader.loadPlugin(install.partKey)
                loader.callApplicationOnCreate(install.partKey)
            } catch (e: Exception) {
                // Anything from load/install — binder RemoteException,
                // LoadPluginException marshalled across, etc. This path
                // won't succeed end-to-end until Phase 5b-5 applies the
                // Shadow transform to plugin APKs.
                Log.e(TAG, "Shadow rejected plugin load — expected until 5b-5", e)
                throw e
            }
        }

        // Dispatch the plugin's launcher Activity. The loader rewrites
        // the Intent's component to Shadow's PluginContainerActivity in
        // the plugin process; we just start it as normal.
        val pluginIntent = Intent().apply {
            setClassName(packageName, resolveLauncherActivity(packageName, apkPath))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val hostIntent = withContext(Dispatchers.IO) {
            requireNotNull(loaderBinder()).convertActivityIntent(pluginIntent)
        } ?: error("convertActivityIntent returned null for $packageName")

        hostIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(hostIntent)
    }

    private fun installProjectZip(
        projectId: String,
        partKey: String,
        packageName: String,
        loader: File,
        runtime: File,
        plugin: File,
    ): InstalledProject {
        val zipDir = File(context.filesDir, "shadow/zips").apply { mkdirs() }
        val zipFile = File(zipDir, "${projectId.hashCode().toUInt().toString(16)}.szip")
        val built = ShadowPluginZipBuilder.build(
            outputZip = zipFile,
            loaderApk = loader,
            runtimeApk = runtime,
            pluginApk = plugin,
            partKey = partKey,
            packageName = packageName,
            uuidNickName = partKey,
        )
        val pluginConfig = manager.installPluginFromZip(built.zip, built.pluginHash)
        manager.onInstallCompleted(pluginConfig, /* soDirMap = */ emptyMap())
        return InstalledProject(uuid = built.uuid, partKey = partKey)
    }

    private fun bindAndWait() {
        manager.bindPluginProcessService(
            ShadowPluginProcessService::class.java.name,
        )
        manager.awaitServiceConnected()
        serviceBound = true
    }

    /**
     * Reflectively reach into the manager's inherited `mPluginLoader`
     * field. Shadow exposes it as `protected` — bridging through a
     * Kotlin property would require patching vendored code.
     */
    private fun loaderBinder(): com.tencent.shadow.dynamic.loader.PluginLoader? {
        val field = com.tencent.shadow.dynamic.manager.PluginManagerThatUseDynamicLoader::class.java
            .getDeclaredField("mPluginLoader")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(manager) as? com.tencent.shadow.dynamic.loader.PluginLoader
    }

    /**
     * Best-effort launcher-Activity resolver: parse the plugin APK's
     * AndroidManifest and return the first activity whose name lies
     * under [packageName]. If nothing matches, fall back to the
     * v2 template's conventional `$packageName.MainActivity`.
     */
    private fun resolveLauncherActivity(packageName: String, apkPath: String): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(
            apkPath,
            android.content.pm.PackageManager.GET_ACTIVITIES,
        )
        val launchName = info?.activities?.firstOrNull {
            it.name.startsWith("$packageName.")
        }?.name
        return launchName ?: "$packageName.MainActivity"
    }

    /**
     * Returns the [IPluginInspector] for the `:shadow_plugin` process.
     * Binds [ShadowPluginInspectorService] lazily on first call and
     * caches the resulting binder. The inspector is process-scoped,
     * not project-scoped — only one plugin runs at a time under
     * Shadow, and the same binder serves whichever plugin is resumed.
     */
    fun getInspector(projectId: String): IPluginInspector? {
        inspector?.let { return it }
        if (!installed.containsKey(projectId)) {
            // No plugin launched for this project yet. Nothing to inspect.
            return null
        }
        return bindInspectorBlocking()
    }

    private fun bindInspectorBlocking(): IPluginInspector? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var bound: IPluginInspector? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound = IPluginInspector.Stub.asInterface(service)
                inspector = bound
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                inspector = null
            }
        }
        val intent = Intent().apply {
            setClassName(context, ShadowPluginInspectorService::class.java.name)
        }
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bindService for ShadowPluginInspectorService returned false")
            return null
        }
        inspectorConnection = connection
        // 10 s gives the plugin process time to warm up on a cold start.
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return bound
    }

    fun finishPluginAndReturn(projectId: String) {
        // Bring VibeApp's main Activity back to foreground. That's
        // enough to pop the user out of the plugin UI — the plugin
        // keeps running in its own task, and `launchPlugin` for the
        // same projectId re-uses the existing install, so there's no
        // cost to leaving it around. Shadow's PluginLoader has no
        // finish() equivalent anyway.
        val hostIntent = Intent().apply {
            setClassName(context, VIBEAPP_MAIN_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(hostIntent)
            Log.i(TAG, "finishPluginAndReturn: brought host to foreground (project=$projectId)")
        } catch (e: Exception) {
            Log.e(TAG, "finishPluginAndReturn: startActivity failed (project=$projectId)", e)
        }
    }

    companion object {
        private const val TAG = "ShadowPluginHost"

        // Hard-coded because this module is inside :app; the host
        // launcher Activity's fully-qualified name is stable.
        private const val VIBEAPP_MAIN_ACTIVITY = "com.vibe.app.presentation.ui.main.MainActivity"
    }
}
